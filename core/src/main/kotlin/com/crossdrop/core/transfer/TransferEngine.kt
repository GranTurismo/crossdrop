package com.crossdrop.core.transfer

import com.crossdrop.core.Endpoint
import com.crossdrop.core.crypto.SessionCrypto
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.protocol.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

sealed interface TransferEvent {
    data object Connecting : TransferEvent
    /** Secure channel up. [sas] = short auth string both humans can compare. */
    data class Secured(val peerName: String, val sas: String) : TransferEvent
    data class AwaitingAccept(val offer: Offer) : TransferEvent
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSec: Long,
        val currentFile: FileMeta,
    ) : TransferEvent
    data class Completed(val offer: Offer, val elapsedMs: Long) : TransferEvent
    data object DeclinedByPeer : TransferEvent
    data object Cancelled : TransferEvent
    data class Failed(val message: String, val cause: Throwable? = null) : TransferEvent
}

/** A file the app layer wants to ship. [open] must return a fresh readable stream. */
class OutgoingFile(val meta: FileMeta, val open: () -> InputStream)

/** Receiver's answer to an [Offer]. */
sealed interface ReceiveDecision {
    /** Accept: [sinkFor] returns the stream each incoming file is written to. */
    data class Accept(val sinkFor: (FileMeta) -> OutputStream) : ReceiveDecision
    data class Reject(val reason: String? = null) : ReceiveDecision
}

/**
 * Drives one transfer in either direction over a single TCP socket:
 *
 *   HELLO → HELLO_ACK → [encrypted] OFFER → ACCEPT/DECLINE → DATA* → DONE → DONE
 *
 * The final DONE is echoed by the receiver carrying its own SHA-256 of the
 * payload; the sender compares — tampering or truncation fails loudly.
 */
class TransferEngine(
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    private val instanceId: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // ------------------------------------------------------------- sender --

    fun send(endpoint: Endpoint, files: List<OutgoingFile>): Flow<TransferEvent> = flow {
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            emit(TransferEvent.Connecting)
            socket = Socket(endpoint.host, endpoint.port).apply {
                tcpNoDelay = true
                soTimeout = CONTROL_TIMEOUT_MS
            }
            val io = FrameIO(socket.getInputStream(), socket.getOutputStream())
            val offer = Offer(UUID.randomUUID().toString(), files.map { it.meta })

            // -- handshake
            io.write(FrameType.HELLO, Hello(deviceName = deviceName(), publicKeyB64 = b64(identity.publicKey), instanceId = instanceId).encode())
            val (ackType, ackPayload) = io.read()
            if (ackType != FrameType.HELLO_ACK) throw ProtocolException("expected HELLO_ACK, got $ackType")
            val ack = ackPayload.decode<HelloAck>()
            val keys = SessionCrypto.deriveSession(identity, unb64(ack.publicKeyB64), isInitiator = true)
            io.writeCipher = com.crossdrop.core.crypto.RecordCipher(keys.sendKey)
            io.readCipher = com.crossdrop.core.crypto.RecordCipher(keys.recvKey)
            socket.soTimeout = 0
            emit(TransferEvent.Secured(ack.deviceName, keys.sas))

            // -- offer / accept
            io.write(FrameType.OFFER, offer.encode())
            emit(TransferEvent.AwaitingAccept(offer))
            when (io.read().first) {
                FrameType.ACCEPT -> Unit
                FrameType.DECLINE -> { emit(TransferEvent.DeclinedByPeer); return@flow }
                FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return@flow }
                else -> throw ProtocolException("expected ACCEPT/DECLINE")
            }

            // -- payload
            val total = files.sumOf { it.meta.size }.coerceAtLeast(1)
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(CHUNK_SIZE)
            var sent = 0L
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var lastEmit = 0L

            for ((index, file) in files.withIndex()) {
                // File-start marker — also guarantees zero-byte files materialize.
                io.write(FrameType.DATA, fileIndexHeader(index))
                file.open().use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        val payload = ByteArray(4 + n)
                        ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(index)
                        chunk.copyInto(payload, 4, 0, n)
                        io.write(FrameType.DATA, payload)
                        digest.update(chunk, 0, n)
                        sent += n; windowBytes += n

                        val now = System.currentTimeMillis()
                        val windowNs = System.nanoTime() - windowStart
                        if (now - lastEmit >= 100 || sent == total) {
                            val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                            emit(TransferEvent.Progress(sent, total, bps, file.meta))
                            lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                        }
                    }
                }
            }
            val sha = digest.digest().toHex()
            io.write(FrameType.DONE, Done(offer.transferId, sha).encode())

            // -- receiver echoes its own digest for verification
            val (doneType, donePayload) = io.read()
            if (doneType == FrameType.CANCEL) { emit(TransferEvent.Cancelled); return@flow }
            if (doneType != FrameType.DONE) throw ProtocolException("expected DONE echo, got $doneType")
            val echo = donePayload.decode<Done>()
            if (!echo.sha256.equals(sha, ignoreCase = true)) {
                emit(TransferEvent.Failed("integrity check failed"))
                return@flow
            }
            emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { socket?.let { s -> FrameIO(s.getInputStream(), s.getOutputStream()).write(FrameType.CANCEL, Cancel("sender cancelled").encode()) } }
            throw e
        } catch (e: Exception) {
            emit(TransferEvent.Failed(e.message ?: "transfer failed", e))
        } finally {
            runCatching { socket?.close() }
        }
    }.flowOn(dispatcher)

    // ----------------------------------------------------------- receiver --

    /**
     * Serve one inbound connection already accepted from the listener socket.
     * [decide] is invoked once the (decrypted) OFFER arrives — return
     * [ReceiveDecision.Accept] with a sink factory, or [ReceiveDecision.Reject].
     */
    fun receive(socket: Socket, decide: suspend (peerName: String, offer: Offer) -> ReceiveDecision): Flow<TransferEvent> = flow {
        val started = System.currentTimeMillis()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = CONTROL_TIMEOUT_MS
            val io = FrameIO(socket.getInputStream(), socket.getOutputStream())

            // -- handshake
            val (helloType, helloPayload) = io.read()
            if (helloType != FrameType.HELLO) throw ProtocolException("expected HELLO, got $helloType")
            val hello = helloPayload.decode<Hello>()
            if (hello.version != PROTOCOL_VERSION) throw ProtocolException("protocol v${hello.version} unsupported")
            io.write(FrameType.HELLO_ACK, HelloAck(deviceName = deviceName(), publicKeyB64 = b64(identity.publicKey), instanceId = instanceId).encode())
            val keys = SessionCrypto.deriveSession(identity, unb64(hello.publicKeyB64), isInitiator = false)
            io.writeCipher = com.crossdrop.core.crypto.RecordCipher(keys.sendKey)
            io.readCipher = com.crossdrop.core.crypto.RecordCipher(keys.recvKey)
            socket.soTimeout = DATA_TIMEOUT_MS
            emit(TransferEvent.Secured(hello.deviceName, keys.sas))

            // -- offer
            val (offerType, offerPayload) = io.read()
            if (offerType != FrameType.OFFER) throw ProtocolException("expected OFFER, got $offerType")
            val offer = offerPayload.decode<Offer>()
            val decision = decide(hello.deviceName, offer)
            when (decision) {
                is ReceiveDecision.Reject -> {
                    io.write(FrameType.DECLINE, Decline(offer.transferId, decision.reason).encode())
                    emit(TransferEvent.DeclinedByPeer)
                    return@flow
                }
                is ReceiveDecision.Accept -> io.write(FrameType.ACCEPT, Accept(offer.transferId).encode())
            }
            val sinkFor = decision.sinkFor

            // -- payload
            val total = offer.files.sumOf { it.size }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var currentFile = -1
            var sink: OutputStream? = null
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var lastEmit = 0L

            try {
                loop@ while (true) {
                    val (type, payload) = io.read()
                    when (type) {
                        FrameType.DATA -> {
                            if (payload.size < 4) throw ProtocolException("short DATA frame")
                            val fileIndex = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                            if (fileIndex != currentFile) {
                                if (fileIndex <= currentFile || fileIndex >= offer.files.size)
                                    throw ProtocolException("bad file index $fileIndex")
                                sink?.close()
                                currentFile = fileIndex
                                sink = sinkFor(offer.files[fileIndex])
                            }
                            val body = payload.copyOfRange(4, payload.size)
                            sink!!.write(body)
                            digest.update(body)
                            received += body.size; windowBytes += body.size

                            val now = System.currentTimeMillis()
                            val windowNs = System.nanoTime() - windowStart
                            if (now - lastEmit >= 100 || (total > 0 && received == total)) {
                                val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                                emit(TransferEvent.Progress(received, total, bps, offer.files[currentFile]))
                                lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                            }
                        }
                        FrameType.DONE -> {
                            val done = payload.decode<Done>()
                            val localSha = digest.digest().toHex()
                            io.write(FrameType.DONE, Done(offer.transferId, localSha).encode())
                            if (!localSha.equals(done.sha256, ignoreCase = true)) {
                                emit(TransferEvent.Failed("integrity check failed"))
                                return@flow
                            }
                            emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
                            break@loop
                        }
                        FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return@flow }
                        else -> throw ProtocolException("unexpected $type during payload")
                    }
                }
            } finally {
                sink?.close()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(TransferEvent.Failed(e.message ?: "receive failed", e))
        } finally {
            runCatching { socket.close() }
        }
    }.flowOn(dispatcher)

    private fun fileIndexHeader(index: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(index).array()

    companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val CONTROL_TIMEOUT_MS = 15_000
        const val DATA_TIMEOUT_MS = 60_000
    }
}

private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
