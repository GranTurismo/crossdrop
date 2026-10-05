package com.crossdrop.core.vault

import com.crossdrop.core.crypto.RecordCipher
import com.crossdrop.core.crypto.SessionCrypto
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.delta.Blake3
import com.crossdrop.core.protocol.FrameIO
import com.crossdrop.core.protocol.FrameType
import com.crossdrop.core.protocol.Hello
import com.crossdrop.core.protocol.HelloAck
import com.crossdrop.core.protocol.PROTOCOL_VERSION
import com.crossdrop.core.protocol.ProtocolException
import com.crossdrop.core.protocol.decode
import com.crossdrop.core.protocol.encode
import com.crossdrop.core.vault.VaultFs.VaultError
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The LAN Vault: one ephemeral TCP listener that demultiplexes
 *  - the AEAD binary protocol (X25519 → ChaCha20-Poly1305 frames) for native
 *    CrossDrop clients, and
 *  - a plain HTTP/1.1 file explorer for zero-install browsers.
 *
 * A frame length is always < 2^24, so a binary connection's first byte is
 * 0x00..0x3F — never an uppercase ASCII letter. Peeking a single byte splits
 * the streams without consuming anything.
 *
 * Threading: one daemon accept thread; each socket gets a blocking handler on
 * a bounded pool. [close] kills the listener, every client socket, and purges
 * the upload staging dir.
 */
class VaultServer(
    val fs: VaultFs,
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    val writable: Boolean = false,
    /** 4-digit PIN → `auth=pin`; null → `auth=open`. */
    private val pin: String? = null,
    private val instanceId: String,
    private val onEvent: (Event) -> Unit = {},
) : Closeable {

    sealed interface Event {
        data class ClientConnected(val peerName: String, val via: String) : Event
        data class ClientDisconnected(val peerName: String) : Event
        data class PullFinished(val path: String, val bytes: Long) : Event
        data class PushFinished(val path: String, val bytes: Long) : Event
    }

    /** Live host-dashboard counters. */
    val activeClients = AtomicInteger(0)
    val bytesOut = AtomicLong(0)
    val bytesIn = AtomicLong(0)
    val connectedPeers = CopyOnWriteArrayList<String>()

    @Volatile var port: Int = 0
        private set
    @Volatile private var closed = false
    val isRunning: Boolean get() = listener != null && !closed

    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(MAX_CLIENTS) { r ->
        Thread(r, "vault-client").apply { isDaemon = true }
    }
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val nextTransferId = AtomicLong(1)

    fun start(): Int {
        check(listener == null) { "already running" }
        val ss = ServerSocket(0)
        listener = ss
        port = ss.localPort
        acceptThread = thread(name = "vault-accept", isDaemon = true) {
            while (!closed) {
                try {
                    val socket = ss.accept()
                    if (!clientSockets.add(socket)) continue
                    try { pool.execute { serve(socket) } }
                    catch (e: Exception) { runCatching { socket.close() } }
                } catch (e: SocketException) {
                    if (!closed) throw e
                    break
                }
            }
        }
        return port
    }

    // ------------------------------------------------------------- demux --

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = PushbackInputStream(
                BufferedInputStream(socket.getInputStream(), 64 * 1024), 8)
            val first = input.read()
            if (first < 0) return
            input.unread(first)
            if (first in 'A'.code..'Z'.code) {
                // HTTP method — every verb starts with an ASCII capital.
                http.handle(socket, input)
            } else {
                nativeSession(socket, input)
            }
        } catch (_: SocketTimeoutException) {
            // idle client — drop it
        } catch (_: EOFException) {
        } catch (_: SocketException) {
        } catch (_: Exception) {
            // malformed client input must never kill the listener
        } finally {
            clientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private val http by lazy {
        VaultHttp(fs, writable, pin, deviceName) { bytesOut.addAndGet(it) }
    }

    // --------------------------------------------------- native session --

    /** Runs HELLO→HELLO_ACK→(VAULT_AUTH)→command loop. Returns peer name. */
    private fun nativeSession(socket: Socket, input: InputStream): String {
        val io = FrameIO(input, BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

        val (t0, p0) = io.read()
        if (t0 != FrameType.HELLO) throw ProtocolException("expected HELLO, got $t0")
        val hello = p0.decode<Hello>()
        io.write(FrameType.HELLO_ACK, HelloAck(
            version = PROTOCOL_VERSION,
            deviceName = deviceName(),
            publicKeyB64 = b64(identity.publicKey),
            instanceId = instanceId,
            vaultAuth = if (pin != null) "pin" else "open",
        ).encode())

        val keys = SessionCrypto.deriveSession(
            identity, unb64(hello.publicKeyB64), isInitiator = false)
        io.readCipher = RecordCipher(keys.recvKey)
        io.writeCipher = RecordCipher(keys.sendKey)

        if (pin != null) {
            socket.soTimeout = AUTH_TIMEOUT_MS
            val (at, ap) = io.read()
            val ok = at == FrameType.VAULT_AUTH &&
                runCatching { decodeMsg<VaultAuth>(ap) }.getOrNull()?.let {
                    MessageDigest.isEqual(
                        unb64(it.token),
                        SessionCrypto.vaultAuthToken(keys.shared, pin))
                } == true
            socket.soTimeout = IDLE_TIMEOUT_MS
            if (!ok) {
                // Slow down PIN guessing, then drop without a word.
                runCatching { Thread.sleep(300) }
                return hello.deviceName
            }
            io.write(FrameType.VAULT_PUSH_ACK, encodeMsg(VaultAck("accept")))
        }

        activeClients.incrementAndGet()
        connectedPeers += hello.deviceName
        onEvent(Event.ClientConnected(hello.deviceName, "native"))
        try {
            commandLoop(io)
        } finally {
            activeClients.decrementAndGet()
            connectedPeers -= hello.deviceName
            onEvent(Event.ClientDisconnected(hello.deviceName))
        }
        return hello.deviceName
    }

    private fun commandLoop(io: FrameIO) {
        while (!closed) {
            val (type, payload) = io.read()
            try {
                when (type) {
                    FrameType.VAULT_LS -> sendDir(io, decodeMsg<VaultLs>(payload).path)
                    FrameType.VAULT_STAT -> sendAttr(io, decodeMsg<VaultStat>(payload).path)
                    FrameType.VAULT_PULL -> doPull(io, decodeMsg<VaultPull>(payload))
                    FrameType.VAULT_PUSH_INIT -> doPush(io, decodeMsg<VaultPushInit>(payload))
                    FrameType.CANCEL -> {}   // nothing in-flight between commands
                    else -> sendErr(io, 400, "unsupported frame $type")
                }
            } catch (e: VaultError) {
                sendErr(io, e.code, e.message ?: "error")
            }
        }
    }

    private fun sendDir(io: FrameIO, path: String) {
        val entries = fs.list(path).map {
            VaultDirEntry(it.name, it.size, it.isDir, it.mtime, it.mime)
        }
        var sent = 0
        for (batch in entries.chunked(DIR_BATCH)) {
            sent += batch.size
            io.write(FrameType.VAULT_DIR, encodeMsg(VaultDir(
                path = path, entries = batch, eof = sent >= entries.size)))
        }
        if (entries.isEmpty()) {
            io.write(FrameType.VAULT_DIR, encodeMsg(VaultDir(path = path, emptyList(), eof = true)))
        }
    }

    private fun sendAttr(io: FrameIO, path: String) {
        val e = fs.stat(path)
        val hash = if (e.isDir || e.size > HASH_MAX) null
            else fs.resolve(path).inputStream().use { Blake3.hash(it) }.toHex()
        io.write(FrameType.VAULT_ATTR, encodeMsg(VaultAttr(
            e.name, e.size, e.isDir, e.mtime, e.mime, hash)))
    }

    // ------------------------------------------------------------- pull --

    private fun doPull(io: FrameIO, req: VaultPull) {
        val f = fs.resolve(req.path)
        if (!f.exists()) throw VaultError(404, "not found")
        if (!f.isFile) throw VaultError(400, "not a file")
        val size = f.length()
        val offset = req.offset.coerceIn(0, size)
        val length = (if (req.length < 0) size - offset else req.length)
            .coerceIn(0, size - offset)
        val tid = req.transferId
        val hasher = Blake3()
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(DATA_CHUNK)
            val frame = ByteArray(12 + DATA_CHUNK)
            var sent = 0L
            while (sent < length) {
                val n = raf.read(buf, 0, minOf(buf.size, (length - sent).toInt()))
                if (n <= 0) break
                hasher.update(buf, 0, n)
                putHeader(frame, tid, offset + sent)
                buf.copyInto(frame, 12, 0, n)
                io.write(FrameType.VAULT_DATA, frame.copyOf(12 + n))
                bytesOut.addAndGet(n.toLong())
                sent += n
            }
        }
        io.write(FrameType.VAULT_DONE, encodeMsg(VaultDone(tid, hasher.hexDigest())))
        onEvent(Event.PullFinished(req.path, length))
    }

    // ------------------------------------------------------------- push --

    private fun doPush(io: FrameIO, req: VaultPushInit) {
        if (!writable) throw VaultError(403, "vault is read-only")
        val clean = fs.sanitize(req.path)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        if (req.size < 0) throw VaultError(400, "bad size")
        if (!fs.hasRoom(req.size)) throw VaultError(413, "insufficient storage")

        val tid = nextTransferId.getAndIncrement()
        val stage = fs.stageFile(tid)
        try {
            io.write(FrameType.VAULT_PUSH_ACK, encodeMsg(VaultAck("accept", tid)))
            RandomAccessFile(stage, "rw").use { raf ->
                var expected = 0L
                while (expected < req.size) {
                    val (t, p) = io.read()
                    when (t) {
                        FrameType.VAULT_DATA -> {
                            if (p.size < 12 || p.size - 12 > DATA_CHUNK) {
                                throw VaultError(400, "bad data frame")
                            }
                            val (dTid, dOff) = readHeader(p)
                            if (dTid != tid) throw VaultError(409, "transfer id mismatch")
                            if (dOff != expected) throw VaultError(409, "out-of-order chunk")
                            raf.write(p, 12, p.size - 12)
                            expected += p.size - 12
                            bytesIn.addAndGet((p.size - 12).toLong())
                        }
                        FrameType.CANCEL -> throw VaultError(499, "cancelled")
                        else -> throw VaultError(400, "expected VAULT_DATA, got $t")
                    }
                }
            }
            val (t, p) = io.read()
            if (t != FrameType.VAULT_DONE) throw VaultError(400, "expected VAULT_DONE")
            val done = decodeMsg<VaultDone>(p)
            val actual = stage.inputStream().use { Blake3.hash(it) }.toHex()
            if (!MessageDigest.isEqual(
                    actual.toByteArray(), done.hash.toByteArray())) {
                throw VaultError(400, "checksum mismatch")
            }
            val finalPath = fs.commitStaged(stage, clean)
            io.write(FrameType.VAULT_DONE, encodeMsg(VaultDone(tid, actual, finalPath)))
            onEvent(Event.PushFinished(finalPath, req.size))
        } finally {
            stage.delete()
        }
    }

    private fun sendErr(io: FrameIO, code: Int, msg: String) {
        runCatching { io.write(FrameType.VAULT_ERR, encodeMsg(VaultErr(code, msg))) }
    }

    // ----------------------------------------------------------- lifecycle --

    /** Kill the listener, drop every client, purge upload staging. */
    override fun close() {
        closed = true
        runCatching { listener?.close() }
        listener = null
        clientSockets.forEach { runCatching { it.close() } }
        clientSockets.clear()
        pool.shutdownNow()
        runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
        fs.purgeStaging()
    }

    companion object {
        const val DATA_CHUNK = 256 * 1024          // max VAULT_DATA payload
        private const val DIR_BATCH = 512
        private const val MAX_CLIENTS = 16
        private const val IDLE_TIMEOUT_MS = 45_000
        private const val AUTH_TIMEOUT_MS = 15_000
        /** Stat hash only for files ≤ 256 MB — keeps browse snappy. */
        private const val HASH_MAX = 256L * 1024 * 1024

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** `[u32 tid][u64 offset]` prefix for VAULT_DATA payloads. */
        fun putHeader(dst: ByteArray, tid: Long, offset: Long) {
            dst[0] = (tid ushr 24).toByte(); dst[1] = (tid ushr 16).toByte()
            dst[2] = (tid ushr 8).toByte();  dst[3] = tid.toByte()
            for (i in 0 until 8) dst[4 + i] = (offset ushr (56 - i * 8)).toByte()
        }

        fun readHeader(p: ByteArray): Pair<Long, Long> {
            val tid = ((p[0].toLong() and 0xFF) shl 24) or
                ((p[1].toLong() and 0xFF) shl 16) or
                ((p[2].toLong() and 0xFF) shl 8) or (p[3].toLong() and 0xFF)
            var off = 0L
            for (i in 4 until 12) off = (off shl 8) or (p[i].toLong() and 0xFF)
            return tid to off
        }
    }
}
