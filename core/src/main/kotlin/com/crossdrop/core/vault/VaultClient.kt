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
import com.crossdrop.core.vault.VaultServer.Companion.putHeader
import com.crossdrop.core.vault.VaultServer.Companion.readHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Native vault client — one encrypted socket per instance, one operation at a
 * time (serialized by [opLock]). Blocking; call from Dispatchers.IO.
 *
 * Handshake: HELLO → HELLO_ACK (ack advertises `vaultAuth`) → when the vault
 * needs a PIN, VAULT_AUTH carries HKDF(ECDH, pin) — the PIN never appears on
 * the wire and the token is sealed by the session cipher anyway.
 */
class VaultClient(
    private val host: String,
    private val port: Int,
    private val identity: SessionIdentity,
    private val deviceName: String,
    private val instanceId: String,
) : Closeable {

    /** True after a successful [connect] when the host demands a PIN. */
    var requiresPin = false
        private set
    /** The peer's display name as announced in HELLO_ACK. */
    var peerName = ""
        private set

    private var socket: Socket? = null
    private var io: FrameIO? = null
    private val opLock = ReentrantLock()
    private val nextTid = AtomicLong(1)

    // ------------------------------------------------------------ connect --

    /**
     * Connect + handshake (+ PIN auth when [pin] given or required).
     * @throws VaultError 401 when the vault needs a PIN or rejects it.
     */
    fun connect(pin: String? = null) {
        opLock.lock()
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.soTimeout = IO_TIMEOUT_MS
            val io = FrameIO(
                BufferedInputStream(s.getInputStream(), 64 * 1024),
                BufferedOutputStream(s.getOutputStream(), 64 * 1024))

            io.write(FrameType.HELLO, Hello(
                version = PROTOCOL_VERSION,
                deviceName = deviceName,
                publicKeyB64 = VaultServer.b64(identity.publicKey),
                instanceId = instanceId,
            ).encode())
            val (t, p) = io.read()
            if (t != FrameType.HELLO_ACK) throw ProtocolException("expected HELLO_ACK, got $t")
            val ack = p.decode<HelloAck>()
            peerName = ack.deviceName
            requiresPin = ack.vaultAuth == "pin"

            val keys = SessionCrypto.deriveSession(
                identity, VaultServer.unb64(ack.publicKeyB64), isInitiator = true)
            io.readCipher = RecordCipher(keys.recvKey)
            io.writeCipher = RecordCipher(keys.sendKey)

            if (requiresPin) {
                if (pin == null) { s.close(); throw VaultError(401, "pin required") }
                val token = VaultServer.b64(SessionCrypto.vaultAuthToken(keys.shared, pin))
                io.write(FrameType.VAULT_AUTH, encodeMsg(VaultAuth(token)))
                val (rt, rp) = try { io.read() }
                    catch (e: EOFException) { throw VaultError(401, "wrong pin") }
                when {
                    rt == FrameType.VAULT_PUSH_ACK &&
                        decodeMsg<VaultAck>(rp).status == "accept" -> Unit
                    rt == FrameType.VAULT_ERR -> throw decodeErr(rp)
                    else -> throw VaultError(401, "auth rejected")
                }
            }
            socket = s
            this.io = io
        } finally {
            opLock.unlock()
        }
    }

    // ------------------------------------------------------------ commands --

    /** Directory listing of [path] (may span several frames). */
    fun ls(path: String = "/"): List<VaultDirEntry> = op { io ->
        io.write(FrameType.VAULT_LS, encodeMsg(VaultLs(path)))
        val all = mutableListOf<VaultDirEntry>()
        while (true) {
            val (t, p) = io.read()
            when (t) {
                FrameType.VAULT_DIR -> {
                    val d = decodeMsg<VaultDir>(p)
                    all += d.entries
                    if (d.eof) return@op all
                }
                FrameType.VAULT_ERR -> throw decodeErr(p)
                else -> throw ProtocolException("expected VAULT_DIR, got $t")
            }
        }
        @Suppress("UNREACHABLE_CODE") all
    }

    fun stat(path: String): VaultAttr = op { io ->
        io.write(FrameType.VAULT_STAT, encodeMsg(VaultStat(path)))
        val (t, p) = io.read()
        when (t) {
            FrameType.VAULT_ATTR -> decodeMsg(p)
            FrameType.VAULT_ERR -> throw decodeErr(p)
            else -> throw ProtocolException("expected VAULT_ATTR, got $t")
        }
    }

    /**
     * Stream `path[offset .. offset+length)` into [out] (`length` -1 ⇒ EOF).
     * Returns the host's VAULT_DONE — [VaultDone.hash] is BLAKE3 of exactly the
     * bytes sent, which we verify on receipt.
     */
    fun pull(
        path: String, offset: Long = 0, length: Long = -1, out: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): VaultDone = op { io ->
        val tid = nextTid.getAndIncrement()
        io.write(FrameType.VAULT_PULL, encodeMsg(VaultPull(path, offset, length, tid)))
        val hasher = Blake3()
        var expect = offset
        var got = 0L
        while (true) {
            val (t, p) = io.read()
            when (t) {
                FrameType.VAULT_DATA -> {
                    val (dTid, dOff) = readHeader(p)
                    if (dTid != tid) throw ProtocolException("pull tid mismatch")
                    if (dOff != expect) throw ProtocolException("pull offset gap")
                    hasher.update(p, 12, p.size - 12)
                    out.write(p, 12, p.size - 12)
                    expect += p.size - 12
                    got += p.size - 12
                    onProgress(got)
                }
                FrameType.VAULT_DONE -> {
                    val done = decodeMsg<VaultDone>(p)
                    if (done.hash.isNotEmpty() && done.hash != hasher.hexDigest()) {
                        throw VaultError(400, "pull checksum mismatch")
                    }
                    return@op done
                }
                FrameType.VAULT_ERR -> throw decodeErr(p)
                FrameType.CANCEL -> throw VaultError(499, "cancelled by host")
                else -> throw ProtocolException("expected VAULT_DATA, got $t")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /**
     * Upload [size] bytes from [open] to vault-relative [path]. The host stages
     * the bytes, verifies our BLAKE3, and commits atomically; the returned
     * [VaultDone.path] is the final (possibly de-duplicated) vault path.
     */
    fun push(
        path: String, size: Long, mime: String? = null,
        open: () -> InputStream, onProgress: (Long) -> Unit = {},
    ): VaultDone = op { io ->
        io.write(FrameType.VAULT_PUSH_INIT,
            encodeMsg(VaultPushInit(path, size, mime)))
        val (t, p) = io.read()
        val ack = when (t) {
            FrameType.VAULT_PUSH_ACK -> decodeMsg<VaultAck>(p)
            FrameType.VAULT_ERR -> throw decodeErr(p)
            else -> throw ProtocolException("expected VAULT_PUSH_ACK, got $t")
        }
        if (ack.status != "accept") throw VaultError(403, ack.reason ?: "rejected")

        val hasher = Blake3()
        val buf = ByteArray(VaultServer.DATA_CHUNK)
        val frame = ByteArray(12 + VaultServer.DATA_CHUNK)
        open().use { input ->
            var sent = 0L
            while (sent < size) {
                val n = input.read(buf, 0,
                    minOf(buf.size.toLong(), size - sent).toInt())
                if (n <= 0) break
                hasher.update(buf, 0, n)
                putHeader(frame, ack.transferId, sent)
                buf.copyInto(frame, 12, 0, n)
                io.write(FrameType.VAULT_DATA, frame.copyOf(12 + n))
                sent += n
                onProgress(sent)
            }
            if (sent < size) throw EOFException("source truncated at $sent/$size")
        }
        io.write(FrameType.VAULT_DONE,
            encodeMsg(VaultDone(ack.transferId, hasher.hexDigest())))
        val (ft, fp) = io.read()
        when (ft) {
            FrameType.VAULT_DONE -> decodeMsg(fp)
            FrameType.VAULT_ERR -> throw decodeErr(fp)
            else -> throw ProtocolException("expected VAULT_DONE, got $ft")
        }
    }

    /** Best-effort abort of an in-flight transfer. */
    fun cancel() = runCatching {
        io?.write(FrameType.CANCEL, encodeMsg(com.crossdrop.core.protocol.Cancel()))
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        io = null
    }

    // ---------------------------------------------------------------- impl --

    private fun <T> op(block: (FrameIO) -> T): T {
        opLock.lock()
        try {
            val io = this.io ?: throw VaultError(0, "not connected")
            return block(io)
        } finally {
            opLock.unlock()
        }
    }

    private fun decodeErr(p: ByteArray): VaultError {
        val e = runCatching { decodeMsg<VaultErr>(p) }.getOrNull()
        return VaultError(e?.code ?: 500, e?.message ?: "unknown error")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 60_000
    }
}
