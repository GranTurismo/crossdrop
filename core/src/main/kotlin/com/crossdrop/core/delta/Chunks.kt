package com.crossdrop.core.delta

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ProtocolException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** One content-defined chunk: length + BLAKE3 identity. */
data class ChunkEntry(val len: Int, val hash: ByteArray) {
    val key: String get() = hash.joinToString("") { "%02x".format(it) }
    override fun equals(other: Any?): Boolean =
        other is ChunkEntry && len == other.len && hash.contentEquals(other.hash)
    override fun hashCode(): Int = 31 * len + hash.contentHashCode()
}

/** A local file whose chunks are already indexed — reuse source for deltas.
 *  [root] = whole-file BLAKE3 hex when known (from a persisted index). */
data class Baseline(val file: File, val chunks: List<ChunkEntry>, val root: String? = null)

/**
 * App-provided storage plumbing for protocol v2 delta/resume receives.
 * `InboxStore` implements this on Android; tests use a plain temp dir impl.
 */
interface DeltaReceiver {
    /** Directory holding received chunks named `<blake3-hex>.bin` — the
     *  resumability pool; may contain chunks from interrupted sessions. */
    fun stagingPool(): File

    /** Existing file + chunk index matching [meta] (usually by name), or null. */
    fun baselineFor(meta: FileMeta): Baseline?

    /** Final destination file for the assembled output. May coincide with the
     *  baseline file — implementers assemble via temp+rename. */
    fun targetFor(meta: FileMeta): File

    /** Persist a chunk index sidecar for [file] so future sends can delta
     *  against it without re-chunking. [root] = whole-file BLAKE3 hex. */
    fun publishIndex(file: File, chunks: List<ChunkEntry>, root: String)

    /** True when nothing local could shorten this offer — no baseline file for
     *  any of [files] and an empty staging pool. The sender then streams
     *  chunks right away (push mode) instead of hashing the whole file for a
     *  manifest first. Conservative default: stay on the manifest path. */
    fun canPush(files: List<FileMeta>): Boolean = false

    /** Drop any cached index for [meta]'s file — called when content fails
     *  verification so the next attempt re-chunks the baseline instead of
     *  trusting a stale index forever. */
    fun invalidate(meta: FileMeta)
}

object ChunkCodec {

    const val ENTRIES_PER_PART = 16_384      // 36 B/entry → ~576 KB/frame

    // ---- CHUNK_MAP : [u32 fileIndex][u32 part][u32 parts][u32 count] --------
    //                    [u32 len][32B hash] × count

    fun encodeMapPart(fileIndex: Int, part: Int, parts: Int, entries: List<ChunkEntry>): ByteArray {
        val out = ByteArrayOutputStream(16 + entries.size * 36)
        val hdr = ByteBuffer.allocate(16)
        hdr.putInt(fileIndex); hdr.putInt(part); hdr.putInt(parts); hdr.putInt(entries.size)
        out.write(hdr.array())
        for (e in entries) { out.write(ByteBuffer.allocate(4).putInt(e.len).array()); out.write(e.hash) }
        return out.toByteArray()
    }

    data class MapPart(val fileIndex: Int, val part: Int, val parts: Int, val entries: List<ChunkEntry>)

    fun decodeMapPart(payload: ByteArray): MapPart {
        if (payload.size < 16) throw ProtocolException("short CHUNK_MAP")
        val buf = ByteBuffer.wrap(payload)
        val fi = buf.int; val part = buf.int; val parts = buf.int; val count = buf.int
        // Validate BEFORE allocating — a hostile count would OOM otherwise.
        if (count < 0 || payload.size != 16 + count * 36)
            throw ProtocolException("bad CHUNK_MAP entry count $count")
        if (part < 0 || parts < 1 || part >= parts || fi < 0)
            throw ProtocolException("bad CHUNK_MAP header")
        val entries = ArrayList<ChunkEntry>(count)
        repeat(count) {
            val len = buf.int
            if (len <= 0) throw ProtocolException("bad chunk len $len")
            val hash = ByteArray(32).also { buf.get(it) }
            entries += ChunkEntry(len, hash)
        }
        return MapPart(fi, part, parts, entries)
    }

    // ---- CHUNK_REQ : [u32 fileIndex][u32 count][u32 chunkIndex] × count -----

    fun encodeRequest(fileIndex: Int, indices: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + indices.size * 4)
        buf.putInt(fileIndex); buf.putInt(indices.size)
        indices.forEach(buf::putInt)
        return buf.array()
    }

    data class Request(val fileIndex: Int, val indices: IntArray)

    fun decodeRequest(payload: ByteArray): Request {
        if (payload.size < 8) throw ProtocolException("short CHUNK_REQ")
        val buf = ByteBuffer.wrap(payload)
        val fi = buf.int; val count = buf.int
        if (count < 0 || payload.size != 8 + count * 4 || fi < 0)
            throw ProtocolException("bad CHUNK_REQ count $count")
        return Request(fi, IntArray(count) { buf.int })
    }

    // ---- PUSH_CHUNK : [u32 fileIndex][32B blake3][payload] -------------------

    fun encodePush(fileIndex: Int, hash: ByteArray, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 32 + payload.size)
        buf.putInt(fileIndex); buf.put(hash); buf.put(payload)
        return buf.array()
    }

    class PushChunk(val fileIndex: Int, val hash: ByteArray, val payload: ByteArray)

    fun decodePush(payload: ByteArray): PushChunk {
        if (payload.size <= 36) throw ProtocolException("short PUSH_CHUNK")
        val buf = ByteBuffer.wrap(payload)
        val fi = buf.int
        if (fi < 0) throw ProtocolException("bad PUSH_CHUNK header")
        val hash = ByteArray(32).also { buf.get(it) }
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        if (data.size > FastCdc.MAX) throw ProtocolException("oversized PUSH_CHUNK")
        return PushChunk(fi, hash, data)
    }

    // ---- PUSH_END : [u32 fileIndex][u32 chunkCount][32B whole-file blake3] ---

    fun encodePushEnd(fileIndex: Int, count: Int, root: ByteArray): ByteArray =
        ByteBuffer.allocate(40).putInt(fileIndex).putInt(count).put(root).array()

    class PushEnd(val fileIndex: Int, val count: Int, val root: ByteArray)

    fun decodePushEnd(payload: ByteArray): PushEnd {
        if (payload.size != 40) throw ProtocolException("bad PUSH_END")
        val buf = ByteBuffer.wrap(payload)
        val fi = buf.int; val count = buf.int
        if (fi < 0 || count < 0) throw ProtocolException("bad PUSH_END header")
        return PushEnd(fi, count, ByteArray(32).also { buf.get(it) })
    }

    // ---- CHUNK : [u32 fileIndex][u32 chunkIndex][payload] --------------------

    fun encodeChunk(fileIndex: Int, chunkIndex: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size)
        buf.putInt(fileIndex); buf.putInt(chunkIndex); buf.put(payload)
        return buf.array()
    }

    data class Chunk(val fileIndex: Int, val chunkIndex: Int, val payload: ByteArray)

    fun decodeChunk(payload: ByteArray): Chunk {
        if (payload.size < 8) throw ProtocolException("short CHUNK")
        val buf = ByteBuffer.wrap(payload)
        val fi = buf.int; val ci = buf.int
        if (fi < 0 || ci < 0) throw ProtocolException("bad CHUNK header")
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        return Chunk(fi, ci, data)
    }
}
