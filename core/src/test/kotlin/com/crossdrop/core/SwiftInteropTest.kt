package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.delta.FileDeltaStore
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Wire-compat check against the Swift engine (ios/CrossDropCore). Skipped unless
 * CROSSDROP_SWIFT_CLI points at a built `crossdrop-cli` binary:
 *
 *   (cd ios/CrossDropCore && swift build -c release)
 *   CROSSDROP_SWIFT_CLI=$PWD/ios/CrossDropCore/.build/release/crossdrop-cli ./gradlew :core:test --tests '*SwiftInterop*'
 */
class SwiftInteropTest {

    private val cli: String? = System.getenv("CROSSDROP_SWIFT_CLI")?.takeIf { File(it).canExecute() }

    private fun engine(name: String, version: Int) = TransferEngine(
        identity = SessionIdentity(), deviceName = { name },
        instanceId = "1a2b3c4d", protocolVersion = version)

    private fun content(size: Int, seed: Int) = Random(seed).nextBytes(size)

    private fun startReceiver(inbox: File, version: Int): Pair<Process, Int> {
        val p = ProcessBuilder(cli, "recv", inbox.absolutePath, "--version", version.toString())
            .redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        var port = -1
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("LISTENING ")) { port = line.removePrefix("LISTENING ").trim().toInt(); break }
        }
        check(port > 0) { "swift receiver did not start" }
        return p to port
    }

    private fun finish(p: Process, label: String): String {
        assertTrue("$label: swift process timed out", p.waitFor(60, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("$label: swift exit ($out)", 0, p.exitValue())
        return out
    }

    /** Both humans compare the short auth string — the Swift and Kotlin HKDF derivations must agree. */
    private fun assertSasMatches(kotlinEvents: List<TransferEvent>, swiftOut: String, label: String) {
        val kotlinSas = kotlinEvents.filterIsInstance<TransferEvent.Secured>().single().sas
        val swiftSas = swiftOut.lines().first { it.startsWith("EVENT secured") }.trim().split(" ").last()
        assertEquals("$label: SAS differs between implementations", kotlinSas, swiftSas)
    }

    private fun kotlinSendsToSwift(senderV: Int, swiftV: Int, inbox: File, files: Map<String, ByteArray>): List<TransferEvent> {
        val (proc, port) = startReceiver(inbox, swiftV)
        val outgoing = files.map { (n, b) -> OutgoingFile(FileMeta(n, b.size.toLong())) { ByteArrayInputStream(b) } }
        val events = runBlocking { engine("kotlin", senderV).send(Endpoint("127.0.0.1", port), outgoing).toList() }
        val swiftOut = finish(proc, "k→s v$senderV/v$swiftV")
        assertTrue("sender: ${events.last()}", events.last() is TransferEvent.Completed)
        assertSasMatches(events, swiftOut, "k→s v$senderV/v$swiftV")
        return events
    }

    private fun swiftSendsToKotlin(swiftV: Int, kotlinV: Int, inbox: File, files: List<File>): List<TransferEvent> = runBlocking {
        val listener = ServerSocket(0)
        val store = FileDeltaStore(inbox)
        val job = async {
            engine("kotlin", kotlinV).receive(listener.accept()) { _, _ ->
                ReceiveDecision.Accept(
                    sinkFor = { meta -> File(inbox, meta.name).outputStream() },
                    delta = store)
            }.toList()
        }
        val args = listOf(cli!!, "send", "127.0.0.1", listener.localPort.toString(), "--version", swiftV.toString()) +
            files.map { it.absolutePath }
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val events = job.await()
        val swiftOut = finish(proc, "s→k v$swiftV/v$kotlinV")
        listener.close()
        assertTrue("receiver: ${events.last()}", events.last() is TransferEvent.Completed)
        assertSasMatches(events, swiftOut, "s→k v$swiftV/v$kotlinV")
        events
    }

    @Test
    fun `kotlin sender to swift receiver across protocol versions`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        for (v in listOf(3, 2, 1)) {
            val inbox = Files.createTempDirectory("swrx").toFile()
            val files = mapOf("big.bin" to content(2_500_000, v), "tiny.txt" to "hi".toByteArray(), "empty.bin" to ByteArray(0))
            kotlinSendsToSwift(3, v, inbox, files)
            for ((n, b) in files) assertArrayEquals("v$v $n", b, File(inbox, n).readBytes())
        }
    }

    @Test
    fun `swift sender to kotlin receiver across protocol versions`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        for (v in listOf(3, 2, 1)) {
            val src = Files.createTempDirectory("swsrc").toFile()
            val inbox = Files.createTempDirectory("ktrx").toFile()
            val a = File(src, "a.bin").apply { writeBytes(content(2_500_000, 10 + v)) }
            val b = File(src, "b.txt").apply { writeBytes("hello".toByteArray()) }
            val e = File(src, "empty.bin").apply { writeBytes(ByteArray(0)) }
            swiftSendsToKotlin(v, 3, inbox, listOf(a, b, e))
            for (f in listOf(a, b, e)) assertArrayEquals("v$v ${f.name}", f.readBytes(), File(inbox, f.name).readBytes())
        }
    }

    @Test
    fun `delta resend against a swift receiver ships nothing`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        val inbox = Files.createTempDirectory("swrx").toFile()
        val files = mapOf("doc.bin" to content(1_800_000, 5))
        kotlinSendsToSwift(3, 3, inbox, files)                       // first: push
        val events = kotlinSendsToSwift(3, 3, inbox, files)          // second: manifest, all cached
        val last = events.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals(1_800_000L, last.bytesSkipped)
    }

    @Test
    fun `swift delta resend against kotlin receiver ships nothing`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        val src = Files.createTempDirectory("swsrc").toFile()
        val inbox = Files.createTempDirectory("ktrx").toFile()
        val a = File(src, "doc.bin").apply { writeBytes(content(1_800_000, 6)) }
        swiftSendsToKotlin(3, 3, inbox, listOf(a))
        val events = swiftSendsToKotlin(3, 3, inbox, listOf(a))
        val last = events.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals(1_800_000L, last.bytesSkipped)
        assertArrayEquals(a.readBytes(), File(inbox, "doc.bin").readBytes())
    }

    /** ANNOUNCE across languages: an iPhone that scanned an Android phone's code, and the other way round. */
    @Test
    fun `swift announces itself to kotlin`() = runBlocking {
        assumeTrue(cli != null)
        val listener = ServerSocket(0)
        var seen: List<Any>? = null
        val handler = object : com.crossdrop.core.transfer.ControlHandler {
            override fun announced(name: String, instanceId: String, host: String, port: Int, os: String?) { seen = listOf(name, instanceId, host, port) }
        }
        val served = async(kotlinx.coroutines.Dispatchers.IO) { engine("kotlin", 3).receiveRequest(listener.accept(), handler) { ReceiveDecision.Reject() }.toList() }
        val p = ProcessBuilder(cli, "announce", "--to", "127.0.0.1:${listener.localPort}", "--port", "43210", "--name", "iPhone", "--id", "5e1f7c11")
            .redirectErrorStream(true).start()
        val out = finish(p, "s→k announce")
        val events = served.await()
        listener.close()
        assertTrue(out, out.lines().any { it.trim() == "ANNOUNCED kotlin 1a2b3c4d" })
        assertEquals(listOf("iPhone", "5e1f7c11", "127.0.0.1", 43210), seen)
        assertTrue("no transfer events", events.none { it is TransferEvent.Failed || it is TransferEvent.Completed })
    }

    @Test
    fun `kotlin announces itself to swift`() = runBlocking {
        assumeTrue(cli != null)
        val inbox = Files.createTempDirectory("ann").toFile()
        val (proc, port) = startReceiver(inbox, 3)
        try {
            val (name, id) = engine("kotlin", 3).control(Endpoint("127.0.0.1", port)) { announce(40999); peerName to peerInstanceId }
            assertEquals("swift-cli", name)
            assertEquals("5e1f7c11", id)
            val line = proc.inputStream.bufferedReader().let { r ->
                generateSequence { r.readLine() }.first { it.startsWith("ANNOUNCED-FROM") }
            }
            assertEquals("ANNOUNCED-FROM kotlin 1a2b3c4d 127.0.0.1 40999", line.trim())
        } finally {
            proc.destroy()
            inbox.deleteRecursively()
        }
    }

    /** SHAKE across languages (shake to connect between an iPhone and an Android phone). */
    @Test
    fun `swift shake reaches kotlin and kotlin shake reaches swift`() = runBlocking {
        assumeTrue(cli != null)
        // Swift → Kotlin
        val listener = ServerSocket(0)
        var seen: List<Any>? = null
        val handler = object : com.crossdrop.core.transfer.ControlHandler {
            override fun shaken(name: String, instanceId: String, host: String, port: Int, os: String?) { seen = listOf(name, instanceId, host, port) }
        }
        val served = async(kotlinx.coroutines.Dispatchers.IO) { engine("kotlin", 3).receiveRequest(listener.accept(), handler) { ReceiveDecision.Reject() }.toList() }
        val p = ProcessBuilder(cli, "shake", "--to", "127.0.0.1:${listener.localPort}", "--port", "41000", "--name", "iPhone", "--id", "5e1f7c11")
            .redirectErrorStream(true).start()
        val out = finish(p, "s→k shake")
        served.await()
        listener.close()
        assertTrue(out, out.lines().any { it.trim() == "SHOOK kotlin 1a2b3c4d" })
        assertEquals(listOf("iPhone", "5e1f7c11", "127.0.0.1", 41000), seen)

        // Kotlin → Swift
        val inbox = Files.createTempDirectory("shake").toFile()
        val (proc, port) = startReceiver(inbox, 3)
        try {
            engine("kotlin", 3).control(Endpoint("127.0.0.1", port)) { shake(42000) }
            val line = proc.inputStream.bufferedReader().let { r -> generateSequence { r.readLine() }.first { it.startsWith("SHAKE-FROM") } }
            assertEquals("SHAKE-FROM kotlin 1a2b3c4d 127.0.0.1 42000", line.trim())
        } finally {
            proc.destroy()
            inbox.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ "My devices" across languages --

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A Swift `recv` with extra flags; its output is read line by line through the returned reader. */
    private fun startSwift(inbox: File, vararg extra: String): Triple<Process, Int, BufferedReader> {
        val p = ProcessBuilder(listOf(cli!!, "recv", inbox.absolutePath) + extra).redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        val port = generateSequence { reader.readLine() }.first { it.startsWith("LISTENING ") }.removePrefix("LISTENING ").trim().toInt()
        return Triple(p, port, reader)
    }

    private fun BufferedReader.lineStarting(prefix: String): String =
        generateSequence { readLine() }.firstOrNull { it.startsWith(prefix) }?.trim() ?: error("no line starting with $prefix")

    /** An Android phone or computer pairs with an iPhone, then shares its clipboard and sends files that prove the pairing. */
    @Test
    fun `kotlin pairs with swift, shares its clipboard and sends as one of my devices`() = runBlocking<Unit> {
        assumeTrue(cli != null)
        val inbox = Files.createTempDirectory("pair-ks").toFile()
        val (proc, port, out) = startSwift(inbox, "--agree", "--clips", "--count", "1")
        try {
            val kotlin = engine("kotlin", 3)
            val id = com.crossdrop.core.pairing.PairingStore.newId()
            val (sas, key) = kotlin.control(Endpoint("127.0.0.1", port), readTimeoutMs = 20_000) {
                sas to pair(id, purpose = com.crossdrop.core.protocol.PairPurpose.DEVICES)
            }
            assertNotNull("swift agreed", key)
            assertEquals("PAIR-ASKED kotlin $sas devices", out.lineStarting("PAIR-ASKED"))
            assertEquals("both derive the same key", "PAIRED-FROM $id ${hex(key!!)}", out.lineStarting("PAIRED-FROM"))

            val secret = com.crossdrop.core.transfer.PairSecret(id, key)
            val taken = kotlin.control(Endpoint("127.0.0.1", port)) {
                assertEquals(id, pairCheck(listOf(secret))?.id)
                clipboard("გამარჯობა 👋 https://example.com/a?b=c")
            }
            assertTrue("swift took the copy", taken)
            assertEquals("CLIP-FROM $id გამარჯობა 👋 https://example.com/a?b=c", out.lineStarting("CLIP-FROM"))

            // A stranger's key: not recognised, so no copy can go.
            val stranger = kotlin.control(Endpoint("127.0.0.1", port)) { pairCheck(listOf(com.crossdrop.core.transfer.PairSecret(id, ByteArray(32)))) }
            assertNull(stranger)

            // Files that prove the pairing: the iPhone proves it back, which the sender checks before sending a byte.
            val bytes = content(300_000, 11)
            val events = kotlin.send(Endpoint("127.0.0.1", port), listOf(OutgoingFile(FileMeta("paired.bin", bytes.size.toLong())) { ByteArrayInputStream(bytes) }),
                com.crossdrop.core.transfer.SendOptions(pairKey = key)).toList()
            assertTrue("sender: ${events.last()}", events.last() is TransferEvent.Completed)
            assertEquals("PAIRED-OFFER $id", out.lineStarting("PAIRED-OFFER"))
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
            assertArrayEquals(bytes, File(inbox, "paired.bin").readBytes())
        } finally {
            proc.destroy()
            inbox.deleteRecursively()
        }
    }

    /** A paired iPhone's send to a computer that can't prove the pairing (it borrowed the name) moves nothing. */
    @Test
    fun `a swift send to one of my devices fails unless the receiver proves the pairing`() = runBlocking<Unit> {
        assumeTrue(cli != null)
        val listener = ServerSocket(0)
        val inbox = Files.createTempDirectory("pair-imp").toFile()
        val served = async(kotlinx.coroutines.Dispatchers.IO) {
            engine("impostor", 3).receive(listener.accept()) { _, _ ->
                ReceiveDecision.Accept(sinkFor = { meta -> File(inbox, meta.name).outputStream() })
            }.toList()
        }
        val f = File(inbox.parentFile, "to-impostor-${System.nanoTime()}.bin").apply { writeBytes(content(50_000, 3)) }
        val p = ProcessBuilder(cli, "send", "127.0.0.1", listener.localPort.toString(), "--pairing", "${"ab".repeat(16)}:${"cd".repeat(32)}", f.absolutePath)
            .redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        served.await()
        listener.close()
        assertTrue(out, out.contains("EVENT failed This isn't your paired device"))
        assertFalse("nothing arrived", File(inbox, f.name).exists() && File(inbox, f.name).length() > 0)
        f.delete(); inbox.deleteRecursively()
    }

    /** An iPhone asks a computer or Android phone to pair, then shares its clipboard and sends files as one of its devices. */
    @Test
    fun `swift pairs with kotlin, shares its clipboard and sends as one of my devices`() = runBlocking<Unit> {
        assumeTrue(cli != null)
        val listener = ServerSocket(0)
        val inbox = Files.createTempDirectory("pair-sk").toFile()
        val held = java.util.concurrent.CopyOnWriteArrayList<com.crossdrop.core.transfer.PairSecret>()
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val clips = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handler = object : com.crossdrop.core.transfer.ControlHandler {
            override suspend fun pairRequested(name: String, sas: String, purpose: String?): Boolean { asked += "$name $sas $purpose"; return true }
            override fun paired(pairId: String, name: String, key: ByteArray, purpose: String?) { held += com.crossdrop.core.transfer.PairSecret(pairId, key) }
            override fun pairings() = held.toList()
            override fun clipboard(pairId: String, clipId: String, text: String): Boolean { clips += "$pairId $text"; return true }
        }
        val kotlin = engine("kotlin", 3)
        val proved = java.util.concurrent.CopyOnWriteArrayList<String>()
        val served = async(kotlinx.coroutines.Dispatchers.IO) {
            repeat(3) {
                val sock = listener.accept()
                kotlin.receiveRequest(sock, handler) { req ->
                    val match = req.pairedWith(held.toList())
                    match?.let { proved += it.id }
                    ReceiveDecision.Accept(sinkFor = { meta -> File(inbox, meta.name).outputStream() }, pairKey = match?.key)
                }.toList()
            }
        }
        try {
            val pairOut = finish(ProcessBuilder(cli, "pair", "--to", "127.0.0.1:${listener.localPort}", "--name", "iPhone").redirectErrorStream(true).start(), "s→k pair")
            val code = pairOut.lines().first { it.startsWith("CODE ") }.removePrefix("CODE ").trim()
            val (id, keyHex) = pairOut.lines().first { it.startsWith("PAIRED ") }.trim().split(" ").drop(1)
            assertEquals(listOf("iPhone $code devices"), asked.toList())
            assertEquals("both derive the same key", keyHex, hex(held.single().key))
            assertEquals(id, held.single().id)

            val clipOut = finish(ProcessBuilder(cli, "clip", "--to", "127.0.0.1:${listener.localPort}", "--pairing", "$id:$keyHex", "--text", "from the iPhone ✓")
                .redirectErrorStream(true).start(), "s→k clip")
            assertTrue(clipOut, clipOut.lines().any { it.trim() == "CLIP accepted" })
            assertEquals(listOf("$id from the iPhone ✓"), clips.toList())

            val f = File(inbox.parentFile, "from-iphone-${System.nanoTime()}.bin").apply { writeBytes(content(120_000, 5)) }
            val sendOut = finish(ProcessBuilder(cli, "send", "127.0.0.1", listener.localPort.toString(), "--pairing", "$id:$keyHex", f.absolutePath)
                .redirectErrorStream(true).start(), "s→k paired send")
            assertTrue(sendOut, sendOut.contains("EVENT completed"))
            served.await()
            assertEquals(listOf(id), proved.toList())
            assertArrayEquals(f.readBytes(), File(inbox, f.name).readBytes())
            f.delete()
        } finally {
            listener.close()
            inbox.deleteRecursively()
        }
    }
}
