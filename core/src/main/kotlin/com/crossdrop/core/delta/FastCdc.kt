package com.crossdrop.core.delta

import java.io.InputStream

/**
 * FastCDC-style content-defined chunking: a 32-bit gear rolling hash decides
 * cut points from byte content, so inserting/deleting bytes only perturbs
 * boundaries near the edit — ~95%+ of chunks stay identical across revisions.
 *
 * Exposed as a lazy [Sequence] — pull-based, so callers can do suspending
 * work (socket writes, progress) between chunks. Deterministic for a given
 * input, so sender and receiver produce identical chunk lists.
 *
 * Defaults target ~64 KB chunks: min 16 KB, avg 64 KB, max 256 KB.
 */
object FastCdc {

    const val MIN = 16 * 1024
    const val AVG = 64 * 1024
    const val MAX = 256 * 1024

    /** Deterministic gear table — both ends must share it, so a fixed
     *  SplitMix64 seed is used instead of an external constant table. */
    private val GEAR = run {
        var z = 0x243F6A8885A308D3L
        IntArray(256) {
            z += 0x9E3779B97F4A7C15uL.toLong()
            var r = z
            r = (r xor (r ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            r = (r xor (r ushr 27)) * 0x94D049BB133111EBuL.toLong()
            (r xor (r ushr 31)).toInt()
        }
    }

    private const val MASK_S = (1 shl 17) - 1   // cut-favouring while below avg
    private const val MASK_L = (1 shl 15) - 1   // cut-resistant once above avg

    /** Lazily split [input]; yields `(chunkBytes, fileOffset)` pairs. */
    fun chunks(
        input: InputStream,
        minSize: Int = MIN,
        avgSize: Int = AVG,
        maxSize: Int = MAX,
    ): Sequence<Pair<ByteArray, Long>> = sequence {
        // Direct array writes — ByteArrayOutputStream.write(int) costs ~4x more.
        val chunk = ByteArray(maxSize)
        val buf = ByteArray(64 * 1024)
        var fp = 0L
        var offset = 0L
        var chunkLen = 0

        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val b = buf[i]
                chunk[chunkLen++] = b
                if (chunkLen >= minSize) {
                    fp = (fp shl 1) + (GEAR[b.toInt() and 0xFF].toLong() and 0xFFFF_FFFFL)
                    val mask = if (chunkLen < avgSize) MASK_S else MASK_L
                    if ((fp and mask.toLong()) == 0L || chunkLen >= maxSize) {
                        yield(chunk.copyOf(chunkLen) to offset)
                        offset += chunkLen
                        chunkLen = 0; fp = 0
                    }
                }
            }
        }
        if (chunkLen > 0) yield(chunk.copyOf(chunkLen) to offset)
    }

    /** Convenience: materialize a byte array's chunks. */
    fun split(data: ByteArray, minSize: Int = MIN, avgSize: Int = AVG, maxSize: Int = MAX):
        List<Pair<ByteArray, Long>> =
        data.inputStream().use { chunks(it, minSize, avgSize, maxSize).toList() }
}
