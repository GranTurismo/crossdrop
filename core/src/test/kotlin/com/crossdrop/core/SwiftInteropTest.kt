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
}
