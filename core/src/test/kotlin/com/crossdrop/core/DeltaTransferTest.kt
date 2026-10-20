package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.delta.ChunkEntry
import com.crossdrop.core.delta.FastCdc
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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

class DeltaTransferTest {

    private fun engine(name: String, version: Int = com.crossdrop.core.protocol.PROTOCOL_VERSION) =
        TransferEngine(
            identity = SessionIdentity(),
            deviceName = { name },
            instanceId = UUID.randomUUID().toString().take(8),
            protocolVersion = version,
        )

    /** Deterministic pseudo-random content (seeded) — repeatable across runs. */
    private fun content(size: Int, seed: Int = 7) = Random(seed).nextBytes(size)

    // ------------------------------------------------------------ FastCdc --

    @Test
    fun `cdc boundaries are deterministic and within bounds`() {
        val data = content(1024 * 1024)
        val a = FastCdc.split(data)
        val b = FastCdc.split(data)
        assertEquals(a.map { it.second }, b.map { it.second })
        for ((chunk, _) in a.dropLast(1)) {
            assertTrue(chunk.size >= FastCdc.MIN)
            assertTrue(chunk.size <= FastCdc.MAX)
        }
        // Reassembles exactly
        val joined = ByteArrayOutputStream()
        a.forEach { (c, _) -> joined.write(c) }
        assertArrayEquals(data, joined.toByteArray())
    }

    @Test
    fun `cdc localizes edits — most chunk hashes survive an insertion`() {
        val base = content(1024 * 1024)
        val edited = ByteArrayOutputStream().apply {
            write(base, 0, 500_000)
            write(Random(99).nextBytes(4096))        // splice in 4 KiB
            write(base, 500_000, base.size - 500_000)
        }.toByteArray()

        val h1 = FastCdc.split(base).map { com.crossdrop.core.delta.Blake3.hash(it.first).toHex() }.toSet()
        val h2 = FastCdc.split(edited).map { com.crossdrop.core.delta.Blake3.hash(it.first).toHex() }.toSet()

        val kept = h2.count { it in h1 }.toDouble() / h2.size
        assertTrue("expected >60%% boundary reuse after mid-file insert, got $kept", kept > 0.6)
    }

    // ------------------------------------------------------- delta engine --

    private class Sink {
        val streams = mutableMapOf<String, ByteArrayOutputStream>()
        val factory: (FileMeta) -> ByteArrayOutputStream = { meta ->
            ByteArrayOutputStream().also { streams[meta.name] = it }
        }
    }

    /** One loopback send→receive through the v2 delta path. Returns client events. */
    private fun runDelta(
        inboxDir: File, files: List<OutgoingFile>,
        senderVersion: Int = com.crossdrop.core.protocol.PROTOCOL_VERSION,
        receiverVersion: Int = com.crossdrop.core.protocol.PROTOCOL_VERSION,
        store: com.crossdrop.core.delta.DeltaReceiver = FileDeltaStore(inboxDir),
    ): Pair<List<TransferEvent>, List<TransferEvent>> = runBlocking {
        val listener = ServerSocket(0)
        val sink = Sink()
        val serverJob = async {
            engine("receiver", receiverVersion).receive(listener.accept()) { _, _ ->
                ReceiveDecision.Accept(sink.factory, delta = store)
            }.toList()
        }
        val clientEvents = engine("sender", senderVersion).send(
            Endpoint("127.0.0.1", listener.localPort), files).toList()
        val serverEvents = serverJob.await()
        listener.close()
        clientEvents to serverEvents
    }

    @Test
    fun `v2 loopback assembles identical bytes`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val payload = content(700_000)
        val (client, server) = runDelta(dir, listOf(
            OutgoingFile(FileMeta("v.bin", payload.size.toLong()), { ByteArrayInputStream(payload) }),
        ))
        assertTrue(client.last() is TransferEvent.Completed)
        assertTrue(server.last() is TransferEvent.Completed)
        assertArrayEquals(payload, File(dir, "v.bin").readBytes())
    }

    @Test
    fun `re-sharing unmodified file sends zero bytes`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val payload = content(500_000)
        val meta = FileMeta("same.bin", payload.size.toLong())
        val file = { OutgoingFile(meta, { ByteArrayInputStream(payload) }) }

        runDelta(dir, listOf(file()))
        // publishIndex wrote .idx, so a second offer of identical content
        // should fully reuse the baseline.
        val (client, _) = runDelta(dir, listOf(file()))

        val last = client.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals(meta.size, last.bytesSkipped)
        assertTrue(client.last() is TransferEvent.Completed)
    }

    @Test
    fun `modified re-share only sends changed chunks`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val v1 = content(800_000)
        val meta = FileMeta("doc.bin", v1.size.toLong())

        runDelta(dir, listOf(OutgoingFile(meta) { ByteArrayInputStream(v1) }))

        // v2 of the file: same tail, different head chunk + small insert
        val v2 = ByteArrayOutputStream().apply {
            write(Random(42).nextBytes(50_000))
            write(v1, 50_000, v1.size - 50_000)
        }.toByteArray()
        val (client, _) = runDelta(dir, listOf(
            OutgoingFile(FileMeta("doc.bin", v2.size.toLong())) { ByteArrayInputStream(v2) },
        ))

        val last = client.filterIsInstance<TransferEvent.Progress>().last()
        assertTrue("expected substantial skip, got ${last.bytesSkipped}",
            last.bytesSkipped > v2.size / 2)
        assertArrayEquals(v2, File(dir, "doc.bin").readBytes())
        assertTrue(client.last() is TransferEvent.Completed)
    }

    @Test
    fun `identical chunks cross the wire only once`() {
        val dir = Files.createTempDirectory("delta").toFile()
        // All-zero content: FastCDC produces repeated identical chunks, so a
        // 400 KiB file should satisfy most of itself from one wire chunk.
        // Replay one whole chunk: content-defined cuts depend only on bytes
        // since the last boundary, so c0+c0 deterministically yields [c0, c0].
        // (Different seed for the tail — same seed would prefix-collide.)
        val c0 = FastCdc.split(content(300_000)).first().first
        val payload = c0 + c0 + content(100_000, seed = 99)
        val meta = FileMeta("dup.bin", payload.size.toLong())
        // Intra-file dedupe is a manifest-path feature (v2) — push mode streams
        // blind, so pin both ends to v2 here.
        val (client, _) = runDelta(dir, listOf(
            OutgoingFile(meta) { ByteArrayInputStream(payload) },
        ), senderVersion = 2, receiverVersion = 2)
        assertTrue(client.last() is TransferEvent.Completed)
        assertArrayEquals(payload, File(dir, "dup.bin").readBytes())
        val last = client.filterIsInstance<TransferEvent.Progress>().last()
        // The second copy was satisfied locally — exactly c0's bytes skipped.
        assertEquals(c0.size.toLong(), last.bytesSkipped)
    }

    @Test
    fun `corrupt staged chunk is dropped and re-requested`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val payload = content(600_000)
        val meta = FileMeta("heal.bin", payload.size.toLong())

        // Seed the pool: first chunk corrupt (right name, wrong bytes), rest good.
        val pool = FileDeltaStore(dir).stagingPool()
        var firstLen = 0
        ByteArrayInputStream(payload).use { input ->
            FastCdc.chunks(input).forEachIndexed { i, (bytes, _) ->
                val e = ChunkEntry(bytes.size, com.crossdrop.core.delta.Blake3.hash(bytes))
                if (i == 0) firstLen = e.len
                File(pool, "${e.key}.bin").writeBytes(
                    if (i == 0) Random(1).nextBytes(e.len) else bytes)
            }
        }

        val (client, _) = runDelta(dir, listOf(
            OutgoingFile(meta) { ByteArrayInputStream(payload) },
        ))
        assertTrue(client.last() is TransferEvent.Completed)
        assertArrayEquals(payload, File(dir, "heal.bin").readBytes())
        // The corrupt chunk came over the wire; everything else was skipped.
        val last = client.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals((meta.size - firstLen), last.bytesSkipped)
    }

    @Test
    fun `corrupt baseline fails once then self-heals`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val payload = content(500_000)
        val meta = FileMeta("base.bin", payload.size.toLong())
        val offer = { OutgoingFile(meta) { ByteArrayInputStream(payload) } }
        runDelta(dir, listOf(offer()))

        // Corrupt the received file in place, spoofing mtime so the .idx
        // still validates — the worst case for a stale baseline.
        val f = File(dir, "base.bin")
        val mtime = f.lastModified()
        val corrupted = payload.copyOf()
        Random(9).nextBytes(corrupted, 0, 30_000)
        f.writeBytes(corrupted)
        f.setLastModified(mtime)

        // Attempt 1: index validates, but assembly chunk verification fails.
        val (c1, _) = runDelta(dir, listOf(offer()))
        assertTrue(c1.last() is TransferEvent.Failed)

        // Attempt 2: index was invalidated → baseline re-chunked → the bad
        // region is simply requested over the wire.
        val (c2, _) = runDelta(dir, listOf(offer()))
        assertTrue(c2.last() is TransferEvent.Completed)
        assertArrayEquals(payload, f.readBytes())
    }

    @Test
    fun `v1 peer without version field falls back to DATA path`() = runBlocking {
        val dir = Files.createTempDirectory("delta").toFile()
        val store = FileDeltaStore(dir)
        val listener = ServerSocket(0)
        val payload = content(300_000)
        val meta = FileMeta("old-peer.bin", payload.size.toLong())

        val sink = Sink()
        val serverJob = async {
            engine("receiver").receive(listener.accept()) { _, _ ->
                ReceiveDecision.Accept(sink.factory, delta = store)
            }.toList()
        }

        // Hand-rolled v1 sender: HELLO carries NO "version" key at all.
        // Raw socket ops must run on Dispatchers.IO — they'd block the
        // runBlocking event loop the server coroutine is queued on.
        val clientJob = async(kotlinx.coroutines.Dispatchers.IO) {
        val id = SessionIdentity()
        val socket = java.net.Socket("127.0.0.1", listener.localPort)
        val io = com.crossdrop.core.protocol.FrameIO(socket.getInputStream(), socket.getOutputStream())
        val pub = java.util.Base64.getEncoder().encodeToString(id.publicKey)
        io.write(com.crossdrop.core.protocol.FrameType.HELLO,
            """{"deviceName":"old","publicKeyB64":"$pub","instanceId":"x"}""".toByteArray())
        val (at, ap) = io.read()
        assertEquals(com.crossdrop.core.protocol.FrameType.HELLO_ACK, at)
        val ack = com.crossdrop.core.protocol.protocolJson.decodeFromString<
            com.crossdrop.core.protocol.HelloAck>(ap.toString(Charsets.UTF_8))
        val keys = com.crossdrop.core.crypto.SessionCrypto.deriveSession(
            id, java.util.Base64.getDecoder().decode(ack.publicKeyB64), isInitiator = true)
        io.writeCipher = com.crossdrop.core.crypto.RecordCipher(keys.sendKey)
        io.readCipher = com.crossdrop.core.crypto.RecordCipher(keys.recvKey)

        io.write(com.crossdrop.core.protocol.FrameType.OFFER,
            com.crossdrop.core.protocol.Offer("t1", listOf(meta)).let {
                com.crossdrop.core.protocol.protocolJson.encodeToString(
                    com.crossdrop.core.protocol.Offer.serializer(), it).toByteArray()
            })
        val (act, acp) = io.read()
        assertEquals(com.crossdrop.core.protocol.FrameType.ACCEPT, act)
        // A v1-style ACCEPT carries no delta flag — receiver must not opt in.
        assertFalse(com.crossdrop.core.protocol.protocolJson.decodeFromString<
            com.crossdrop.core.protocol.Accept>(acp.toString(Charsets.UTF_8)).delta)

        // v1 DATA stream: [u32 fileIndex][bytes], empty body = file start
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        io.write(com.crossdrop.core.protocol.FrameType.DATA,
            java.nio.ByteBuffer.allocate(4).putInt(0).array())
        var off = 0
        while (off < payload.size) {
            val n = minOf(64_000, payload.size - off)
            io.write(com.crossdrop.core.protocol.FrameType.DATA,
                java.nio.ByteBuffer.allocate(4).putInt(0).array() +
                    payload.copyOfRange(off, off + n))
            sha.update(payload, off, n); off += n
        }
        io.write(com.crossdrop.core.protocol.FrameType.DONE,
            com.crossdrop.core.protocol.protocolJson.encodeToString(
                com.crossdrop.core.protocol.Done.serializer(),
                com.crossdrop.core.protocol.Done("t1",
                    sha.digest().joinToString("") { "%02x".format(it) })).toByteArray())
        val (dt, _) = io.read()
        assertEquals(com.crossdrop.core.protocol.FrameType.DONE, dt)
        socket.close()
        }
        clientJob.await()

        val server = serverJob.await()
        assertTrue(server.last() is TransferEvent.Completed)
        // v1 path delivers through sinkFor, not the delta store.
        assertArrayEquals(payload, sink.streams["old-peer.bin"]!!.toByteArray())
        listener.close()
    }

    @Test
    fun `staging pool lets an interrupted transfer resume`() {
        val dir = Files.createTempDirectory("delta").toFile()
        val payload = content(600_000)
        val meta = FileMeta("resume.bin", payload.size.toLong())

        // Pre-seed the pool with the first half of the file's chunks, as if a
        // previous session died mid-flight.
        val pool = FileDeltaStore(dir).stagingPool()
        val half = mutableListOf<ChunkEntry>()
        ByteArrayInputStream(payload).use { input ->
            FastCdc.chunks(input).forEach { (bytes, off) ->
                if (off < payload.size / 2) {
                    val e = ChunkEntry(bytes.size, com.crossdrop.core.delta.Blake3.hash(bytes))
                    File(pool, "${e.key}.bin").writeBytes(bytes)
                    half += e
                }
            }
        }
        val seededBytes = half.sumOf { it.len }

        val (client, _) = runDelta(dir, listOf(
            OutgoingFile(meta) { ByteArrayInputStream(payload) },
        ))
        val last = client.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals(seededBytes.toLong(), last.bytesSkipped)
        assertArrayEquals(payload, File(dir, "resume.bin").readBytes())
        assertTrue(client.last() is TransferEvent.Completed)
    }

    // ------------------------------------------------------------- v3 push --

    /** Wraps a store to record whether the receiver chose push mode. */
    private class SpyStore(private val inner: FileDeltaStore) : com.crossdrop.core.delta.DeltaReceiver by inner {
        var pushChosen: Boolean? = null
        override fun canPush(files: List<FileMeta>) = inner.canPush(files).also { pushChosen = it }
    }

    @Test
    fun `first-time multi-file send takes the push path and lands identical bytes`() {
        val dir = Files.createTempDirectory("push").toFile()
        val a = content(900_000, seed = 1)
        val b = content(0)                                  // zero-byte file
        val c = content(300_000, seed = 3)
        val spy = SpyStore(FileDeltaStore(dir))
        val (client, server) = runDelta(dir, listOf(
            OutgoingFile(FileMeta("a.bin", a.size.toLong())) { ByteArrayInputStream(a) },
            OutgoingFile(FileMeta("empty.bin", 0)) { ByteArrayInputStream(b) },
            OutgoingFile(FileMeta("c.bin", c.size.toLong())) { ByteArrayInputStream(c) },
        ), store = spy)
        assertEquals(true, spy.pushChosen)
        assertTrue(client.last() is TransferEvent.Completed)
        assertTrue(server.last() is TransferEvent.Completed)
        assertArrayEquals(a, File(dir, "a.bin").readBytes())
        assertEquals(0L, File(dir, "empty.bin").length())
        assertArrayEquals(c, File(dir, "c.bin").readBytes())
        // No half-written leftovers and the pool stays untouched on success.
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        assertTrue(File(dir, ".staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `push output is indexed so a re-share sends zero bytes`() {
        val dir = Files.createTempDirectory("push").toFile()
        val payload = content(500_000)
        val meta = FileMeta("again.bin", payload.size.toLong())
        val file = { OutgoingFile(meta) { ByteArrayInputStream(payload) } }
        runDelta(dir, listOf(file()))                       // push (nothing local)
        val spy = SpyStore(FileDeltaStore(dir))
        val (client, _) = runDelta(dir, listOf(file()), store = spy)
        assertEquals(false, spy.pushChosen)                 // baseline exists → manifest path
        assertEquals(meta.size, client.filterIsInstance<TransferEvent.Progress>().last().bytesSkipped)
        assertTrue(client.last() is TransferEvent.Completed)
    }

    @Test
    fun `first byte does not wait for the whole file to be hashed`() {
        val dir = Files.createTempDirectory("push").toFile()
        val payload = content(24_000_000)
        val meta = FileMeta("big.bin", payload.size.toLong())
        var firstProgressAt = 0L
        var startedAt = 0L
        val (client, _) = runBlocking {
            val listener = ServerSocket(0)
            val store = FileDeltaStore(dir)
            val serverJob = async {
                engine("receiver").receive(listener.accept()) { _, _ ->
                    ReceiveDecision.Accept({ ByteArrayOutputStream() }, delta = store)
                }.toList()
            }
            startedAt = System.nanoTime()
            val events = mutableListOf<TransferEvent>()
            engine("sender").send(Endpoint("127.0.0.1", listener.localPort),
                listOf(OutgoingFile(meta) { ByteArrayInputStream(payload) })).collect {
                if (it is TransferEvent.Progress && firstProgressAt == 0L) firstProgressAt = System.nanoTime()
                events += it
            }
            listener.close()
            events to serverJob.await()
        }
        assertTrue(client.last() is TransferEvent.Completed)
        val total = System.nanoTime() - startedAt
        val ttfb = firstProgressAt - startedAt
        println("push TTFB=${ttfb / 1_000_000}ms of ${total / 1_000_000}ms total")
        assertTrue("first progress should land early, was ${ttfb / 1_000_000}ms of ${total / 1_000_000}ms",
            ttfb < total / 3)
    }

    /** Stream that serves [limit] bytes then fails — simulates a dying sender. */
    private class DyingStream(data: ByteArray, private val limit: Int) : java.io.InputStream() {
        private val inner = ByteArrayInputStream(data)
        private var served = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) throw java.io.IOException("simulated disconnect")
            val n = inner.read(b, off, minOf(len, limit - served))
            if (n > 0) served += n
            return n
        }
    }

    @Test
    fun `interrupted push is salvaged into the pool and resumes on retry`() {
        val dir = Files.createTempDirectory("push").toFile()
        val payload = content(1_200_000)
        val meta = FileMeta("dies.bin", payload.size.toLong())

        val (c1, _) = runDelta(dir, listOf(
            OutgoingFile(meta) { DyingStream(payload, 700_000) }))
        assertTrue(c1.last() is TransferEvent.Failed)
        assertFalse(File(dir, "dies.bin").exists())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        val pooled = File(dir, ".staging").listFiles().orEmpty().count { it.name.endsWith(".bin") }
        assertTrue("expected salvaged chunks in the pool", pooled > 0)

        val spy = SpyStore(FileDeltaStore(dir))
        val (c2, _) = runDelta(dir, listOf(
            OutgoingFile(meta) { ByteArrayInputStream(payload) }), store = spy)
        assertEquals(false, spy.pushChosen)                 // pool non-empty → resume path
        assertTrue(c2.last() is TransferEvent.Completed)
        assertArrayEquals(payload, File(dir, "dies.bin").readBytes())
        val skipped = c2.filterIsInstance<TransferEvent.Progress>().last().bytesSkipped
        assertTrue("expected the salvaged prefix to be skipped, got $skipped", skipped > 400_000)
    }

    @Test
    fun `v2 peers on either side fall back to the manifest path`() {
        val payload = content(400_000)
        val meta = FileMeta("mixed.bin", payload.size.toLong())
        for ((sv, rv) in listOf(2 to 3, 3 to 2)) {
            val dir = Files.createTempDirectory("mixed").toFile()
            val spy = SpyStore(FileDeltaStore(dir))
            val (client, server) = runDelta(dir, listOf(
                OutgoingFile(meta) { ByteArrayInputStream(payload) }),
                senderVersion = sv, receiverVersion = rv, store = spy)
            assertNotEquals("sender v$sv / receiver v$rv must not push", true, spy.pushChosen)
            assertTrue(client.last() is TransferEvent.Completed)
            assertTrue(server.last() is TransferEvent.Completed)
            assertArrayEquals(payload, File(dir, "mixed.bin").readBytes())
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
