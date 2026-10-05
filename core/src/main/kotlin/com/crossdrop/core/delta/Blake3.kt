package com.crossdrop.core.delta

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pure-Kotlin BLAKE3 (hash mode only) — streaming hasher with the standard
 * chunk stack, so multi-GB inputs cost O(1) memory. Single-threaded; still
 * ~500 MB/s+ on a phone, far above Wi-Fi transfer rates.
 *
 * Reference: BLAKE3 spec §2 (compression in place, tree structure, output).
 */
class Blake3 {

    private var cv = IV.copyOf()
    private val block = ByteArray(64)
    private var blockLen = 0
    private var blocksCompressed = 0
    private var chunkCounter = 0L
    private var chunksSoFar = 0L
    private val stack = ArrayDeque<IntArray>()   // subtree CVs, oldest → newest

    // ------------------------------------------------------------- public --

    fun update(input: ByteArray, off: Int = 0, len: Int = input.size - off) {
        var o = off; var n = len
        while (n > 0) {
            if (blockLen == 64) {
                // Reached only while more input remains — so a finished
                // 16-block chunk can be emitted here without trailing a
                // spurious empty chunk at end-of-input.
                if (blocksCompressed == BLOCKS_PER_CHUNK - 1) endChunk()
                else compressBlock()
            }
            val take = minOf(64 - blockLen, n)
            input.copyInto(block, blockLen, o, o + take)
            blockLen += take; o += take; n -= take
        }
    }

    fun update(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf).also { if (it < 0) return }
            update(buf, 0, n)
        }
    }

    /** 32-byte digest. Consumes the hasher. */
    fun digest(): ByteArray = rootBytes(32)

    fun hexDigest(): String = digest().joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ chunking --

    /** Compress the current full 64-byte block as a NON-final chunk block. */
    private fun compressBlock() {
        cv = compress(cv, words(block, blockLen), chunkCounter, blockLen,
            flags()).copyOfRange(0, 8)
        blocksCompressed++
        blockLen = 0
    }

    private fun flags(): Int = if (blocksCompressed == 0) CHUNK_START else 0

    /** Emit the finished 1024-byte chunk's CV and merge complete subtrees. */
    private fun endChunk() {
        val out = Out(cv, words(block, blockLen), chunkCounter, blockLen,
            flags() or CHUNK_END)
        chunksSoFar++
        var tc = chunksSoFar
        var cvOut = out.chainingValue()
        while (tc % 2 == 0L) {
            cvOut = parentCv(stack.removeLast(), cvOut)
            tc = tc shr 1
        }
        stack.addLast(cvOut)
        cv = IV.copyOf()
        blocksCompressed = 0
        blockLen = 0
        chunkCounter++
    }

    private fun chunkOutput() = Out(cv, words(block, blockLen), chunkCounter,
        blockLen, flags() or CHUNK_END)

    private fun rootBytes(len: Int): ByteArray {
        var out = chunkOutput()
        for (i in stack.size - 1 downTo 0) {
            out = parentOut(stack.elementAt(i), out.chainingValue())
        }
        return out.rootBytes(len)
    }

    // -------------------------------------------------------------- output --

    private class Out(
        val cv: IntArray, val block: IntArray,
        val counter: Long, val blockLen: Int, val flags: Int,
    ) {
        fun chainingValue(): IntArray =
            compress(cv, block, counter, blockLen, flags).copyOfRange(0, 8)

        fun rootBytes(len: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0L
            while (out.size() < len) {
                val w = compress(cv, block, i, blockLen, flags or ROOT)
                for (word in w) repeat(4) { k -> out.write((word ushr (8 * k)) and 0xFF) }
                i++
            }
            return out.toByteArray().copyOf(len)
        }
    }

    // ----------------------------------------------------------- internals --

    private fun words(b: ByteArray, len: Int): IntArray {
        val w = IntArray(16)
        for (i in 0 until len) w[i / 4] = w[i / 4] or ((b[i].toInt() and 0xFF) shl (8 * (i % 4)))
        return w
    }

    companion object {
        private val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85u.toInt(), 0x3C6EF372, 0xA54FF53Au.toInt(),
            0x510E527F, 0x9B05688Cu.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )
        private const val CHUNK_START = 1
        private const val CHUNK_END = 2
        private const val PARENT = 4
        private const val ROOT = 8
        private const val BLOCKS_PER_CHUNK = 16          // 1024 bytes
        private val PERM = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

        private fun parentCv(left: IntArray, right: IntArray): IntArray =
            parentOut(left, right).chainingValue()

        private fun parentOut(left: IntArray, right: IntArray): Out {
            val block = IntArray(16)
            left.copyInto(block, 0); right.copyInto(block, 8)
            return Out(IV.copyOf(), block, 0, 64, PARENT)
        }

        private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
            s[a] = s[a] + s[b] + mx
            s[d] = (s[d] xor s[a]).rotateRight(16)
            s[c] = s[c] + s[d]
            s[b] = (s[b] xor s[c]).rotateRight(12)
            s[a] = s[a] + s[b] + my
            s[d] = (s[d] xor s[a]).rotateRight(8)
            s[c] = s[c] + s[d]
            s[b] = (s[b] xor s[c]).rotateRight(7)
        }

        private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
            val s = IntArray(16)
            cv.copyInto(s, 0)
            s[8] = IV[0]; s[9] = IV[1]; s[10] = IV[2]; s[11] = IV[3]
            s[12] = counter.toInt(); s[13] = (counter ushr 32).toInt()
            s[14] = blockLen; s[15] = flags
            var m = block
            repeat(7) {
                g(s, 0, 4, 8, 12, m[0], m[1]); g(s, 1, 5, 9, 13, m[2], m[3])
                g(s, 2, 6, 10, 14, m[4], m[5]); g(s, 3, 7, 11, 15, m[6], m[7])
                g(s, 0, 5, 10, 15, m[8], m[9]); g(s, 1, 6, 11, 12, m[10], m[11])
                g(s, 2, 7, 8, 13, m[12], m[13]); g(s, 3, 4, 9, 14, m[14], m[15])
                m = IntArray(16) { m[PERM[it]] }
            }
            val out = IntArray(16)
            for (i in 0 until 8) { out[i] = s[i] xor s[i + 8]; out[i + 8] = s[i + 8] xor cv[i] }
            return out
        }

        // ------------------------------------------------------------ util --

        fun hash(bytes: ByteArray): ByteArray = Blake3().also { it.update(bytes) }.digest()

        fun hash(input: InputStream): ByteArray {
            val h = Blake3(); h.update(input); return h.digest()
        }

        fun hex(bytes: ByteArray): String = hash(bytes).joinToString("") { "%02x".format(it) }
    }
}
