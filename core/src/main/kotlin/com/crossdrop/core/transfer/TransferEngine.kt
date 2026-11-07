package com.crossdrop.core.transfer

import com.crossdrop.core.Endpoint
import com.crossdrop.core.crypto.SessionCrypto
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.delta.Blake3
import com.crossdrop.core.delta.ChunkCodec
import com.crossdrop.core.delta.ChunkEntry
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.delta.FastCdc
import java.io.EOFException
import java.io.File
import com.crossdrop.core.protocol.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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
        /** v2: bytes satisfied locally via delta/resume instead of the wire. */
        val bytesSkipped: Long = 0,
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
    /** [sinkFor] serves the v1 monolithic path; [delta] (when non-null and the
     *  peer speaks v2) engages chunked delta/resume storage instead. */
    data class Accept(
        val sinkFor: (FileMeta) -> OutputStream,
        val delta: DeltaReceiver? = null,
    ) : ReceiveDecision
    data class Reject(val reason: String? = null) : ReceiveDecision
}

/**
 * Drives one transfer in either direction over a single TCP socket:
 *
 *   HELLO → HELLO_ACK → [encrypted] OFFER → ACCEPT/DECLINE → payload → DONE → DONE
 *
 * Payload paths, negotiated by version:
 *
 *   v1 — DATA* frames, monolithic streams; DONE echoes a whole-transfer SHA-256.
 *
 *   v2 — per file: CHUNK_MAP (FastCDC/BLAKE3 manifest) → CHUNK_REQ (missing
 *        indices) → CHUNK* (only changed/missing bytes). Chunks land in a
 *        hash-named staging pool so interrupted sessions resume for free, and
 *        existing inbox files act as delta baselines. DONE echoes per-file
 *        BLAKE3 roots — every chunk is additionally hash-verified on receipt.
 */
class TransferEngine(
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    private val instanceId: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Advertised protocol version — tests pin this to simulate older peers. */
    private val protocolVersion: Int = PROTOCOL_VERSION,
) {
    // ------------------------------------------------------------- sender --

    fun send(endpoint: Endpoint, files: List<OutgoingFile>): Flow<TransferEvent> = flow {
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        var io: FrameIO? = null
        try {
            emit(TransferEvent.Connecting)
            socket = Socket(endpoint.host, endpoint.port).apply {
                tcpNoDelay = true
                soTimeout = CONTROL_TIMEOUT_MS
            }
            io = FrameIO(socket.getInputStream(), socket.getOutputStream())
            val io = io!!
            val offer = Offer(UUID.randomUUID().toString(), files.map { it.meta })

            // -- handshake
            io.write(FrameType.HELLO, Hello(version = protocolVersion, deviceName = deviceName(), publicKeyB64 = b64(identity.publicKey), instanceId = instanceId).encode())
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
            val (ansType, ansPayload) = io.read()
            when (ansType) {
                FrameType.ACCEPT -> Unit
                FrameType.DECLINE -> { emit(TransferEvent.DeclinedByPeer); return@flow }
                FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return@flow }
                else -> throw ProtocolException("expected ACCEPT/DECLINE")
            }

            // Receiver opts in: v2 delta / v3 push only when both sides agreed.
            val accept = ansPayload.decode<Accept>()
            if (ack.version >= 3 && accept.delta && accept.push)
                sendPush(io, offer, files, started) { emit(it) }
            else if (ack.version >= 2 && accept.delta)
                sendDelta(io, offer, files, started) { emit(it) }
            else sendMonolithic(io, offer, files, started) { emit(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { io?.write(FrameType.CANCEL, Cancel("sender cancelled").encode()) }
            throw e
        } catch (e: Exception) {
            emit(TransferEvent.Failed(e.message ?: "transfer failed", e))
        } finally {
            runCatching { socket?.close() }
        }
    }.flowOn(dispatcher)

    /** v1: stream raw bytes in DATA frames; SHA-256 echo at the end. */
    private suspend fun sendMonolithic(
        io: FrameIO, offer: Offer, files: List<OutgoingFile>, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
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
        if (doneType == FrameType.CANCEL) { emit(TransferEvent.Cancelled); return }
        if (doneType != FrameType.DONE) throw ProtocolException("expected DONE echo, got $doneType")
        val echo = donePayload.decode<Done>()
        if (!echo.sha256.equals(sha, ignoreCase = true)) {
            emit(TransferEvent.Failed("integrity check failed"))
            return
        }
        emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
    }

    /** v2: manifest → missing-index request → only those chunks on the wire. */
    private suspend fun sendDelta(
        io: FrameIO, offer: Offer, files: List<OutgoingFile>, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        val total = files.sumOf { it.meta.size }.coerceAtLeast(1)
        val roots = ArrayList<String>(files.size)
        var sent = 0L          // wire bytes — drives bps
        var cached = 0L        // delta-resumed bytes
        var lastEmit = 0L
        var windowStart = System.nanoTime()
        var windowBytes = 0L

        for ((index, file) in files.withIndex()) {
            // -- pass 1: FastCDC split + BLAKE3 per chunk & per file.
            //    Keep each chunk's offset so pass 2 can skip straight to the
            //    bytes it needs instead of re-chunking the whole file.
            val chunks = ArrayList<ChunkEntry>()
            val offs = ArrayList<Long>()
            val fileHasher = Blake3()
            file.open().use { input ->
                FastCdc.chunks(input).forEach { (bytes, off) ->
                    chunks += ChunkEntry(bytes.size, Blake3.hash(bytes))
                    offs += off
                    fileHasher.update(bytes)
                }
            }
            roots += fileHasher.hexDigest()

            // -- manifest, in parts bounded by frame size
            val parts = maxOf(1, (chunks.size + ChunkCodec.ENTRIES_PER_PART - 1) / ChunkCodec.ENTRIES_PER_PART)
            for (p in 0 until parts) {
                val slice = chunks.subList(p * ChunkCodec.ENTRIES_PER_PART,
                    minOf(chunks.size, (p + 1) * ChunkCodec.ENTRIES_PER_PART))
                io.write(FrameType.CHUNK_MAP, ChunkCodec.encodeMapPart(index, p, parts, slice))
            }

            // -- receiver answers with the indices it lacks
            val (reqType, reqPayload) = io.read()
            when (reqType) {
                FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return }
                FrameType.CHUNK_REQ -> Unit
                else -> throw ProtocolException("expected CHUNK_REQ, got $reqType")
            }
            val need = ChunkCodec.decodeRequest(reqPayload).indices.toHashSet()
            if (need.any { it !in chunks.indices })
                throw ProtocolException("CHUNK_REQ index out of range")
            val needBytes = need.sumOf { chunks[it].len.toLong() }
            cached += file.meta.size - needBytes

            // -- pass 2: skip to each needed chunk's recorded offset and
            //    stream only those bytes (deduped — equal content sent once)
            if (need.isNotEmpty()) {
                file.open().use { input ->
                    var pos = 0L
                    for (ci in need.sorted()) {
                        skipFully(input, offs[ci] - pos)
                        val bytes = ByteArray(chunks[ci].len)
                        readFully(input, bytes)
                        pos = offs[ci] + bytes.size
                        if (!Blake3.hash(bytes).contentEquals(chunks[ci].hash))
                            throw ProtocolException("${file.meta.name} changed mid-transfer")
                        io.write(FrameType.CHUNK, ChunkCodec.encodeChunk(index, ci, bytes))
                        sent += bytes.size; windowBytes += bytes.size
                        val now = System.currentTimeMillis()
                        val windowNs = System.nanoTime() - windowStart
                        if (now - lastEmit >= 100) {
                            val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                            emit(TransferEvent.Progress(sent + cached, total, bps, file.meta, cached))
                            lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                        }
                    }
                }
            }
        }

        // -- completion: receiver verifies assembled roots, echoes its own
        emit(TransferEvent.Progress(sent + cached, total, 0,
            files.lastOrNull()?.meta ?: FileMeta("", 0), cached))
        finishSend(io, offer, roots, started, emit)
    }

    /**
     * v3: no manifest, no hashing before the first byte. Each file is chunked
     * and hashed in a single streaming pass and every chunk goes out the moment
     * it is cut, so hashing overlaps the network instead of preceding it. The
     * receiver proved it holds nothing reusable (Accept.push), so the up-front
     * dedupe pass would only have cost time.
     */
    private suspend fun sendPush(
        io: FrameIO, offer: Offer, files: List<OutgoingFile>, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        val total = files.sumOf { it.meta.size }.coerceAtLeast(1)
        val roots = ArrayList<String>(files.size)
        var sent = 0L
        var lastEmit = 0L
        var windowStart = System.nanoTime()
        var windowBytes = 0L

        for ((index, file) in files.withIndex()) {
            val fileHasher = Blake3()
            var count = 0
            var fileBytes = 0L
            file.open().use { input ->
                for ((bytes, _) in FastCdc.chunks(input)) {
                    val hash = Blake3.hash(bytes)
                    fileHasher.update(bytes)
                    io.write(FrameType.PUSH_CHUNK, ChunkCodec.encodePush(index, hash, bytes))
                    count++; fileBytes += bytes.size
                    sent += bytes.size; windowBytes += bytes.size
                    val now = System.currentTimeMillis()
                    val windowNs = System.nanoTime() - windowStart
                    if (now - lastEmit >= 100) {
                        val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                        emit(TransferEvent.Progress(sent, total, bps, file.meta))
                        lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                    }
                }
            }
            if (fileBytes != file.meta.size)
                throw ProtocolException("${file.meta.name} changed mid-transfer")
            val root = fileHasher.digest()
            io.write(FrameType.PUSH_END, ChunkCodec.encodePushEnd(index, count, root))
            roots += root.toHex()
        }

        emit(TransferEvent.Progress(sent, total, 0, files.lastOrNull()?.meta ?: FileMeta("", 0)))
        finishSend(io, offer, roots, started, emit)
    }

    /** Shared v2/v3 tail: send our BLAKE3 roots, compare the receiver's echo. */
    private suspend fun finishSend(
        io: FrameIO, offer: Offer, roots: List<String>, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        io.write(FrameType.DONE, Done(offer.transferId, "", roots).encode())
        val (doneType, donePayload) = io.read()
        if (doneType == FrameType.CANCEL) { emit(TransferEvent.Cancelled); return }
        if (doneType != FrameType.DONE) throw ProtocolException("expected DONE echo, got $doneType")
        val echo = donePayload.decode<Done>()
        if (echo.roots != null && echo.roots != roots) {
            emit(TransferEvent.Failed("integrity check failed"))
            return
        }
        emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
    }

    /** Advance [n] bytes forward in [input]; falls back to read-discard when
     *  the stream can't seek (skip returns 0). */
    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) throw ProtocolException("source truncated mid-transfer")
            left--
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw ProtocolException("source truncated mid-transfer")
            off += n
        }
    }

    // ----------------------------------------------------------- receiver --

    /**
     * Serve one inbound connection already accepted from the listener socket.
     * [decide] is invoked once the (decrypted) OFFER arrives — return
     * [ReceiveDecision.Accept] or [ReceiveDecision.Reject].
     */
    fun receive(socket: Socket, decide: suspend (peerName: String, offer: Offer) -> ReceiveDecision): Flow<TransferEvent> = flow {
        val started = System.currentTimeMillis()
        var io: FrameIO? = null
        var pushMode = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = CONTROL_TIMEOUT_MS
            io = FrameIO(socket.getInputStream(), socket.getOutputStream())
            val io = io!!

            // -- handshake
            val (helloType, helloPayload) = try {
                io.read()
            } catch (_: EOFException) {
                // Connected, said nothing, left: a liveness probe (see PeerLiveness) or a port scan.
                // Not a session — surfacing it would flash a failed transfer on the UI.
                return@flow
            }
            if (helloType != FrameType.HELLO) throw ProtocolException("expected HELLO, got $helloType")
            val hello = helloPayload.decode<Hello>()
            if (hello.version < 1)
                throw ProtocolException("protocol v${hello.version} unsupported")
            // A newer peer clamps down to what we speak rather than failing.
            val negotiated = minOf(hello.version, protocolVersion)
            io.write(FrameType.HELLO_ACK, HelloAck(version = protocolVersion, deviceName = deviceName(), publicKeyB64 = b64(identity.publicKey), instanceId = instanceId).encode())
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
                is ReceiveDecision.Accept -> {
                    val delta = negotiated >= 2 && decision.delta != null
                    val push = negotiated >= 3 && decision.delta?.canPush(offer.files) == true
                    io.write(FrameType.ACCEPT,
                        Accept(offer.transferId, delta = delta, push = push).encode())
                    pushMode = push
                }
            }

            if (negotiated >= 2 && decision.delta != null) {
                if (pushMode) receivePush(io, offer, decision.delta, started) { emit(it) }
                else receiveDelta(socket, io, offer, decision.delta, started) { emit(it) }
            } else {
                receiveMonolithic(io, offer, decision.sinkFor, started) { emit(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { io?.write(FrameType.CANCEL, Cancel("receiver cancelled").encode()) }
            throw e
        } catch (e: Exception) {
            emit(TransferEvent.Failed(e.message ?: "receive failed", e))
        } finally {
            runCatching { socket.close() }
        }
    }.flowOn(dispatcher)

    /** v1: consume DATA frames into per-file sinks; SHA-256 echo at the end. */
    private suspend fun receiveMonolithic(
        io: FrameIO, offer: Offer,
        sinkFor: (FileMeta) -> OutputStream, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
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
                            return
                        }
                        emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
                        break@loop
                    }
                    FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return }
                    else -> throw ProtocolException("unexpected $type during payload")
                }
            }
        } finally {
            sink?.close()
        }
    }

    /** v2: manifests → local reuse (staging pool + baseline) → assemble. */
    private suspend fun receiveDelta(
        socket: Socket, io: FrameIO, offer: Offer, delta: DeltaReceiver, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        val total = offer.files.sumOf { it.size }.coerceAtLeast(1)
        // Chunking a multi-GB file before the first CHUNK_MAP can take far
        // longer than DATA_TIMEOUT — bounded but generous; aborts arrive via
        // CANCEL or socket close.
        socket.soTimeout = DELTA_TIMEOUT_MS
        val pool = delta.stagingPool().apply { mkdirs() }
        val roots = ArrayList<String>(offer.files.size)
        var received = 0L      // wire bytes
        var cached = 0L        // locally-satisfied bytes
        var lastEmit = 0L
        var windowStart = System.nanoTime()
        var windowBytes = 0L

        for ((index, meta) in offer.files.withIndex()) {
            // -- 1. read the sender's chunk manifest (possibly several parts)
            val manifest = ArrayList<ChunkEntry>()
            var parts = -1; var nextPart = 0
            while (parts < 0 || nextPart < parts) {
                val (type, payload) = io.read()
                when (type) {
                    FrameType.CHUNK_MAP -> {
                        val mp = ChunkCodec.decodeMapPart(payload)
                        if (mp.fileIndex != index || mp.part != nextPart)
                            throw ProtocolException("unexpected chunk-map part ${mp.fileIndex}/${mp.part}")
                        if (parts >= 0 && mp.parts != parts)
                            throw ProtocolException("inconsistent chunk-map parts")
                        parts = mp.parts; nextPart++
                        manifest.addAll(mp.entries)
                        // Every chunk but the last is ≥ MIN — cap the alloc.
                        if (manifest.size > meta.size / FastCdc.MIN + 1)
                            throw ProtocolException("manifest too large for ${meta.name}")
                    }
                    FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return }
                    else -> throw ProtocolException("expected CHUNK_MAP, got $type")
                }
            }
            if (manifest.sumOf { it.len.toLong() } != meta.size)
                throw ProtocolException("manifest size mismatch for ${meta.name}")

            // -- 2. index local reuse sources: staging pool + baseline file.
            //    A pooled chunk only counts if its bytes hash to its name —
            //    a corrupt .bin would otherwise poison every retry.
            pool.listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() }
            val poolFiles = (pool.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".bin") && it.name.length == 68 }
                ?.associateBy { it.name.removeSuffix(".bin") } ?: emptyMap()).toMutableMap()
            val poolOk = HashMap<String, java.io.File?>()
            fun pooled(e: ChunkEntry): java.io.File? = poolOk.getOrPut(e.key) {
                val f = poolFiles[e.key] ?: return@getOrPut null
                val good = f.length() == e.len.toLong() && runCatching {
                    f.inputStream().buffered().use { Blake3.hash(it) }.contentEquals(e.hash)
                }.getOrDefault(false)
                if (!good) { f.delete(); poolFiles.remove(e.key); null } else f
            }
            val baseline = delta.baselineFor(meta)
            val baseIndex = HashMap<String, Pair<Long, Int>>()   // key → (offset,len)
            if (baseline != null) {
                var off = 0L
                for (e in baseline.chunks) { baseIndex.putIfAbsent(e.key, off to e.len); off += e.len }
            }
            // One request per missing hash — equal chunks cross the wire once.
            val requested = LinkedHashMap<String, Int>()         // key → first missing index
            for (i in manifest.indices) {
                val e = manifest[i]
                if (e.key !in baseIndex && pooled(e) == null) requested.putIfAbsent(e.key, i)
            }
            cached += meta.size - requested.keys.sumOf { manifest[requested.getValue(it)].len.toLong() }

            // -- 3. ask for only what's missing
            io.write(FrameType.CHUNK_REQ, ChunkCodec.encodeRequest(index, requested.values.toIntArray()))
            emit(TransferEvent.Progress(received + cached, total, 0, meta, cached))

            // -- 4. receive missing chunks → hash-verify → staging pool
            val wantIndex = requested.values.toHashSet()
            val pending = requested.keys.toMutableSet()
            while (pending.isNotEmpty()) {
                val (type, payload) = io.read()
                when (type) {
                    FrameType.CHUNK -> {
                        val c = ChunkCodec.decodeChunk(payload)
                        if (c.fileIndex != index || c.chunkIndex !in wantIndex)
                            throw ProtocolException("unexpected chunk ${c.fileIndex}/${c.chunkIndex}")
                        val want = manifest[c.chunkIndex]
                        if (c.payload.size != want.len || !Blake3.hash(c.payload).contentEquals(want.hash))
                            throw ProtocolException("chunk ${c.chunkIndex} failed hash check")
                        if (!pending.remove(want.key))
                            throw ProtocolException("duplicate chunk ${c.chunkIndex}")
                        val tmp = java.io.File(pool, "${want.key}.${System.nanoTime()}.tmp")
                        tmp.writeBytes(c.payload)
                        val bin = java.io.File(pool, "${want.key}.bin")
                        // renameTo can't overwrite on Windows — Files.move can.
                        java.nio.file.Files.move(tmp.toPath(), bin.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        poolFiles[want.key] = bin
                        received += c.payload.size; windowBytes += c.payload.size
                        val now = System.currentTimeMillis()
                        val windowNs = System.nanoTime() - windowStart
                        if (now - lastEmit >= 100 || pending.isEmpty()) {
                            val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                            emit(TransferEvent.Progress(received + cached, total, bps, meta, cached))
                            lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                        }
                    }
                    FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return }
                    else -> throw ProtocolException("expected CHUNK, got $type")
                }
            }

            // -- 5. assemble. Fast path: manifest identical to the baseline's
            // index AND the file's bytes still hash to its stored root —
            // nothing to write. A mutated baseline falls through to the
            // verified assembly below.
            val fastOk = baseline != null && baseline.root != null &&
                baseline.chunks == manifest && runCatching {
                    baseline.file.inputStream().buffered().use { Blake3.hash(it).toHex() }
                }.getOrNull() == baseline.root
            if (fastOk) {
                roots += baseline.root
            } else {
                val target = delta.targetFor(meta)
                val tmp = java.io.File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
                val fileHasher = Blake3()
                val raf = baseline?.let { RandomAccessFile(it.file, "r") }
                try {
                    tmp.outputStream().buffered().use { out ->
                        for (e in manifest) {
                            val pooled = poolFiles[e.key]
                            if (pooled != null) {
                                pooled.inputStream().buffered().use { it.copyTo(tee(out, fileHasher)) }
                            } else {
                                val (off, len) = baseIndex[e.key]
                                    ?: throw ProtocolException("chunk ${e.key.take(8)} missing locally")
                                // Baseline bytes are trusted only after their
                                // own chunk hash matches — a corrupt baseline
                                // gets its index dropped and heals next retry.
                                raf!!.seek(off)
                                val h = copySlice(raf, out, fileHasher, len)
                                if (h == null || !h.contentEquals(e.hash)) {
                                    delta.invalidate(meta)
                                    throw ProtocolException("baseline chunk ${e.key.take(8)} corrupt")
                                }
                            }
                        }
                    }
                } finally {
                    // Baseline handle must close before the move — the target
                    // may BE the baseline file (Windows locks open files).
                    raf?.close()
                }
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                roots += fileHasher.hexDigest()
                delta.publishIndex(target, manifest, fileHasher.hexDigest())
            }

            // -- pool hygiene: chunks now live inside the assembled file
            for (e in manifest) java.io.File(pool, "${e.key}.bin").delete()
        }

        // -- 6. final DONE with our BLAKE3 roots; sender compares
        finishReceive(io, offer, delta, roots, started, emit)
    }

    /**
     * v3 push: chunks arrive unannounced, straight into a `.part` file. Each is
     * hash-verified as it lands; PUSH_END carries the sender's whole-file root.
     * Verified files are moved into place and indexed like v2 output, so later
     * re-shares delta against them. If the session dies mid-file, the chunks
     * received so far are salvaged into the staging pool — the next attempt
     * sees a non-empty pool, stays on the manifest path, and resumes.
     */
    private suspend fun receivePush(
        io: FrameIO, offer: Offer, delta: DeltaReceiver, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        val total = offer.files.sumOf { it.size }.coerceAtLeast(1)
        val roots = ArrayList<String>(offer.files.size)
        var received = 0L
        var lastEmit = 0L
        var windowStart = System.nanoTime()
        var windowBytes = 0L

        for ((index, meta) in offer.files.withIndex()) {
            val target = delta.targetFor(meta)
            val part = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
            val manifest = ArrayList<ChunkEntry>()
            val fileHasher = Blake3()
            var fileBytes = 0L
            var done = false
            val out = part.outputStream().buffered(CHUNK_SIZE)
            try {
                loop@ while (true) {
                    val (type, payload) = io.read()
                    when (type) {
                        FrameType.PUSH_CHUNK -> {
                            val c = ChunkCodec.decodePush(payload)
                            if (c.fileIndex != index)
                                throw ProtocolException("unexpected push chunk for file ${c.fileIndex}")
                            if (fileBytes + c.payload.size > meta.size)
                                throw ProtocolException("${meta.name} overruns its offered size")
                            val hash = Blake3.hash(c.payload)
                            if (!hash.contentEquals(c.hash))
                                throw ProtocolException("push chunk failed hash check")
                            out.write(c.payload)
                            fileHasher.update(c.payload)
                            manifest += ChunkEntry(c.payload.size, hash)
                            fileBytes += c.payload.size
                            received += c.payload.size; windowBytes += c.payload.size
                            val now = System.currentTimeMillis()
                            val windowNs = System.nanoTime() - windowStart
                            if (now - lastEmit >= 100) {
                                val bps = if (windowNs > 0) (windowBytes * 1_000_000_000L / windowNs) else 0
                                emit(TransferEvent.Progress(received, total, bps, meta))
                                lastEmit = now; windowStart = System.nanoTime(); windowBytes = 0
                            }
                        }
                        FrameType.PUSH_END -> {
                            val end = ChunkCodec.decodePushEnd(payload)
                            if (end.fileIndex != index || end.count != manifest.size || fileBytes != meta.size)
                                throw ProtocolException("${meta.name} ended inconsistently")
                            val root = fileHasher.digest()
                            if (!root.contentEquals(end.root))
                                throw ProtocolException("${meta.name} failed integrity check")
                            out.close()
                            java.nio.file.Files.move(part.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            val hex = root.toHex()
                            delta.publishIndex(target, manifest, hex)
                            roots += hex
                            done = true
                            break@loop
                        }
                        FrameType.CANCEL -> { emit(TransferEvent.Cancelled); return }
                        else -> throw ProtocolException("expected PUSH_CHUNK, got $type")
                    }
                }
            } finally {
                runCatching { out.close() }
                if (!done) salvage(part, manifest, delta.stagingPool())
                part.delete()
            }
        }

        emit(TransferEvent.Progress(received, total, 0, offer.files.lastOrNull() ?: FileMeta("", 0)))
        finishReceive(io, offer, delta, roots, started, emit)
    }

    /** Move the verified chunks of an interrupted push into the staging pool,
     *  hash-named, so a retry resumes instead of starting over. Best effort. */
    private fun salvage(part: File, chunks: List<ChunkEntry>, pool: File) {
        if (chunks.isEmpty()) return
        runCatching {
            pool.mkdirs()
            part.inputStream().buffered(CHUNK_SIZE).use { input ->
                for (e in chunks) {
                    val bytes = ByteArray(e.len)
                    readFully(input, bytes)
                    val bin = File(pool, "${e.key}.bin")
                    if (bin.exists()) continue
                    val tmp = File(pool, "${e.key}.${System.nanoTime()}.tmp")
                    tmp.writeBytes(bytes)
                    java.nio.file.Files.move(tmp.toPath(), bin.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Shared v2/v3 tail: await the sender's roots, echo ours, compare. */
    private suspend fun finishReceive(
        io: FrameIO, offer: Offer, delta: DeltaReceiver, roots: List<String>, started: Long,
        emit: suspend (TransferEvent) -> Unit,
    ) {
        val (type, payload) = io.read()
        if (type == FrameType.CANCEL) { emit(TransferEvent.Cancelled); return }
        if (type != FrameType.DONE) throw ProtocolException("expected DONE, got $type")
        val done = payload.decode<Done>()
        io.write(FrameType.DONE, Done(offer.transferId, "", roots).encode())
        if (done.roots != null && done.roots != roots) {
            // Content assembled but the root disagrees — drop the indexes we
            // trusted so a retry re-chunks baselines rather than re-failing.
            offer.files.forEach { runCatching { delta.invalidate(it) } }
            emit(TransferEvent.Failed("integrity check failed"))
            return
        }
        emit(TransferEvent.Completed(offer, System.currentTimeMillis() - started))
    }

    /** Copy [len] bytes from a positioned [raf] into [out], hashing into
     *  [hasher] (whole-file) on the way. Returns the copied chunk's own
     *  BLAKE3, or null if the source ran out early — callers compare the
     *  digest against the manifest hash. */
    private fun copySlice(
        raf: RandomAccessFile, out: OutputStream, hasher: Blake3, len: Int,
    ): ByteArray? {
        val chunkHasher = Blake3()
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n); hasher.update(buf, 0, n); chunkHasher.update(buf, 0, n); left -= n
        }
        return if (left == 0) chunkHasher.digest() else null
    }

    /** Wrap [out] so every written byte also feeds [hasher]. */
    private fun tee(out: OutputStream, hasher: Blake3) = object : OutputStream() {
        override fun write(b: Int) { out.write(b); hasher.update(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); hasher.update(b, off, len)
        }
    }

    private fun fileIndexHeader(index: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(index).array()

    companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val CONTROL_TIMEOUT_MS = 15_000
        const val DATA_TIMEOUT_MS = 60_000
        /** v2 manifest/chunk phases pause for full-file hashing — generous. */
        const val DELTA_TIMEOUT_MS = 600_000
    }
}

private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
