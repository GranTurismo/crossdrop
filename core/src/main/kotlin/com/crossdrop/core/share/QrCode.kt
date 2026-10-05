package com.crossdrop.core.share

/**
 * Minimal self-contained QR encoder — byte mode, versions 1–10, all four
 * error-correction levels. Enough for ~270-char payload at level L (share URLs
 * are ~60–90 chars). Zero dependencies, so the rendered matrix can be drawn
 * straight into a Compose Canvas without pulling in ZXing.
 *
 * Layout/masking/interleaving follow ISO/IEC 18004.
 */
class QrCode private constructor(
    val version: Int,
    private val modules: BooleanArray,
) {
    val size: Int get() = 17 + version * 4

    /** True = dark module. Coordinates are (x,y) with (0,0) top-left. */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]

    enum class EccLevel(val formatBits: Int) { L(1), M(0), Q(3), H(2) }

    companion object {
        fun encode(text: String, ecl: EccLevel = EccLevel.M): QrCode =
            encodeBytes(text.toByteArray(Charsets.UTF_8), ecl)

        fun encodeBytes(data: ByteArray, ecl: EccLevel): QrCode {
            val version = (1..10).firstOrNull { v ->
                val headerBits = 4 + if (v <= 9) 8 else 16
                headerBits + data.size * 8L <= dataCodewords(v, ecl) * 8L
            } ?: throw IllegalArgumentException("payload too large for QR v10: ${data.size} B")

            val dataCw = interleaveWithEcc(makeDataCodewords(data, version, ecl), version, ecl)
            val m = Matrix(version)
            m.drawFunctionPatterns()
            m.placeData(dataCw)
            return m.finishWithBestMask(ecl)
        }

        // ------------------------------------------------------------- tables --

        /** Total codewords per version 1..10. */
        private val TOTAL_CW = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)

        /** EC codewords per block, indexed [ecl][version-1] (order L,M,Q,H). */
        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18),
            intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26),
            intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24),
            intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28),
        )

        /** Number of EC blocks, [ecl][version-1] (order L,M,Q,H). */
        private val NUM_BLOCKS = arrayOf(
            intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4),
            intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5),
            intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8),
            intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8),
        )

        /** Alignment-pattern center coordinates, [version-1]. */
        private val ALIGN_POS = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26),
            intArrayOf(6, 30), intArrayOf(6, 34), intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )

        private fun dataCodewords(version: Int, ecl: EccLevel): Int =
            TOTAL_CW[version - 1] -
                ECC_PER_BLOCK[ecl.ordinal][version - 1] * NUM_BLOCKS[ecl.ordinal][version - 1]

        // --------------------------------------------------------- data bits --

        private fun makeDataCodewords(data: ByteArray, version: Int, ecl: EccLevel): ByteArray {
            val capacity = dataCodewords(version, ecl)
            val countBits = if (version <= 9) 8 else 16
            val bits = BitBuffer()
            bits.put(0x4, 4)                 // byte mode
            bits.put(data.size, countBits)
            data.forEach { bits.put(it.toInt() and 0xFF, 8) }
            val capacityBits = capacity * 8
            bits.put(0, minOf(4, capacityBits - bits.size))          // terminator
            while (bits.size % 8 != 0) bits.put(0, 1)
            val pads = byteArrayOf(0xEC.toByte(), 0x11.toByte())
            var i = 0
            while (bits.size < capacityBits) { bits.put(pads[i % 2].toInt() and 0xFF, 8); i++ }
            return bits.toByteArray()
        }

        private class BitBuffer {
            private val bytes = ArrayList<Int>()
            var size = 0; private set
            fun put(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    if (size % 8 == 0) bytes.add(0)
                    if ((value ushr i) and 1 != 0) bytes[bytes.size - 1] =
                        bytes[bytes.size - 1] or (0x80 ushr (size % 8))
                    size++
                }
            }
            fun toByteArray() = bytes.map { it.toByte() }.toByteArray()
        }

        // ---------------------------------------------------------- RS + ECC --

        private val gfExp = IntArray(512).also { t ->
            var x = 1
            for (i in 0 until 255) { t[i] = x; x = x shl 1; if (x >= 256) x = x xor 0x11D }
            for (i in 255 until 512) t[i] = t[i - 255]
        }
        private val gfLog = IntArray(256).also { t -> for (i in 0 until 255) t[gfExp[i]] = i }

        private fun gfMul(a: Int, b: Int): Int =
            if (a == 0 || b == 0) 0 else gfExp[gfLog[a] + gfLog[b]]

        /** Generator polynomial ∏(x − α^i), i = 0..degree-1, highest degree first. */
        private fun rsDivisor(degree: Int): IntArray {
            var result = intArrayOf(1)
            for (i in 0 until degree) {
                val next = IntArray(result.size + 1)
                for (j in result.indices) {
                    next[j] = next[j] xor gfMul(result[j], 1)          // × x
                    next[j + 1] = next[j + 1] xor gfMul(result[j], gfExp[i]) // × α^i
                }
                result = next
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            // divisor is the monic generator [1, c1, …, cDegree]; its leading 1
            // is implicit in the shift register, so the remainder has degree
            // coefficients — one per EC codeword.
            val rem = IntArray(divisor.size - 1)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor rem[0]
                rem.copyInto(rem, 0, 1)
                rem[rem.size - 1] = 0
                for (j in rem.indices) rem[j] = rem[j] xor gfMul(divisor[j + 1], factor)
            }
            return rem.map { it.toByte() }.toByteArray()
        }

        /** Split data into blocks, append ECC per block, interleave to final codewords. */
        private fun interleaveWithEcc(data: ByteArray, version: Int, ecl: EccLevel): ByteArray {
            val numBlocks = NUM_BLOCKS[ecl.ordinal][version - 1]
            val eccLen = ECC_PER_BLOCK[ecl.ordinal][version - 1]
            val rawCw = TOTAL_CW[version - 1]
            val numShort = numBlocks - rawCw % numBlocks
            val shortLen = rawCw / numBlocks
            val divisor = rsDivisor(eccLen)

            val blocks = Array(numBlocks) { ByteArray(0) }
            var off = 0
            for (i in 0 until numBlocks) {
                val datLen = shortLen - eccLen + if (i < numShort) 0 else 1
                val dat = data.copyOfRange(off, off + datLen)
                off += datLen
                val ecc = rsRemainder(dat, divisor)
                blocks[i] = if (i < numShort) dat + byteArrayOf(0) + ecc else dat + ecc
            }

            val out = ByteArray(rawCw)
            var k = 0
            for (i in 0 until blocks[0].size) {
                for (j in 0 until numBlocks) {
                    if (i != shortLen - eccLen || j >= numShort) out[k++] = blocks[j][i]
                }
            }
            return out
        }

        // ------------------------------------------------------------- matrix --

        private class Matrix(val version: Int) {
            val size = 17 + version * 4
            val modules = BooleanArray(size * size)
            val isFunc = BooleanArray(size * size)

            private fun set(x: Int, y: Int, dark: Boolean, func: Boolean = false) {
                if (x !in 0 until size || y !in 0 until size) return
                modules[y * size + x] = dark
                if (func) isFunc[y * size + x] = true
            }

            private fun fn(x: Int, y: Int, dark: Boolean) = set(x, y, dark, func = true)

            fun drawFunctionPatterns() {
                for (i in 0 until size) { fn(6, i, i % 2 == 0); fn(i, 6, i % 2 == 0) }
                finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
                val pos = ALIGN_POS[version - 1]
                for (x in pos) for (y in pos) {
                    val corner = (x <= 8 && y <= 8) || (x <= 8 && y >= size - 9) ||
                        (x >= size - 9 && y <= 8)
                    if (!corner) alignPat(x, y)
                }
                drawFormatBits(0, EccLevel.M)   // placeholder; redrawn per mask
                drawVersion()
            }

            private fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until size && y in 0 until size) fn(x, y, d != 2 && d != 4)
                }
            }

            private fun alignPat(cx: Int, cy: Int) {
                for (dy in -2..2) for (dx in -2..2)
                    fn(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }

            fun drawFormatBits(mask: Int, ecl: EccLevel) {
                val data = ecl.formatBits shl 3 or mask
                var rem = data
                for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
                val bits = (data shl 10 or rem) xor 0x5412
                for (i in 0..5) fn(8, i, bit(bits, i))
                fn(8, 7, bit(bits, 6)); fn(8, 8, bit(bits, 7)); fn(7, 8, bit(bits, 8))
                for (i in 9 until 15) fn(14 - i, 8, bit(bits, i))
                for (i in 0..7) fn(size - 1 - i, 8, bit(bits, i))
                for (i in 8 until 15) fn(8, size - 15 + i, bit(bits, i))
                fn(8, size - 8, true)            // dark module
            }

            private fun drawVersion() {
                if (version < 7) return
                var rem = version
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
                val bits = version shl 12 or rem
                for (i in 0 until 18) {
                    val b = bit(bits, i)
                    val a = size - 11 + i % 3
                    val c = i / 3
                    fn(a, c, b); fn(c, a, b)
                }
            }

            fun placeData(data: ByteArray) {
                var i = 0
                var right = size - 1
                while (right >= 1) {
                    if (right == 6) right = 5
                    for (vert in 0 until size) {
                        for (j in 0..1) {
                            val x = right - j
                            val upward = (right + 1) and 2 == 0
                            val y = if (upward) size - 1 - vert else vert
                            if (!isFunc[y * size + x] && i < data.size * 8) {
                                modules[y * size + x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                                i++
                            }
                        }
                    }
                    right -= 2
                }
            }

            private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                else -> error("mask")
            }

            private fun applyMask(mask: Int) {
                for (y in 0 until size) for (x in 0 until size)
                    if (!isFunc[y * size + x] && maskBit(mask, x, y))
                        modules[y * size + x] = !modules[y * size + x]
            }

            fun finishWithBestMask(ecl: EccLevel): QrCode {
                var bestPenalty = Int.MAX_VALUE
                var bestMask = 0
                val saved = modules.copyOf()
                for (mask in 0..7) {
                    applyMask(mask)
                    drawFormatBits(mask, ecl)
                    val p = penaltyScore()
                    if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
                    System.arraycopy(saved, 0, modules, 0, saved.size) // restore
                }
                applyMask(bestMask)
                drawFormatBits(bestMask, ecl)
                return QrCode(version, modules.copyOf())
            }

            private fun penaltyScore(): Int {
                var p = 0
                // runs of ≥5 same color
                for (y in 0 until size) {
                    var run = 1
                    for (x in 1..size) {
                        if (x < size && modules[y * size + x] == modules[y * size + x - 1]) run++
                        else { if (run >= 5) p += 3 + (run - 5); run = 1 }
                    }
                }
                for (x in 0 until size) {
                    var run = 1
                    for (y in 1..size) {
                        if (y < size && modules[y * size + x] == modules[(y - 1) * size + x]) run++
                        else { if (run >= 5) p += 3 + (run - 5); run = 1 }
                    }
                }
                // 2×2 blocks
                for (y in 0 until size - 1) for (x in 0 until size - 1) {
                    val c = modules[y * size + x]
                    if (modules[y * size + x + 1] == c &&
                        modules[(y + 1) * size + x] == c &&
                        modules[(y + 1) * size + x + 1] == c) p += 3
                }
                // finder-like 1:1:3:1:1 pattern + 4-light quiet zone either side
                for (y in 0 until size) for (x in 0..size - 7) {
                    if (isFinderPattern(y * size + x)) {
                        if (x >= 4 && (0 until 4).all { !modules[y * size + x - 4 + it] }) p += 40
                        if (x + 11 <= size && (0 until 4).all { !modules[y * size + x + 7 + it] }) p += 40
                    }
                }
                for (x in 0 until size) for (y in 0..size - 7) {
                    var match = true
                    val pat = booleanArrayOf(true, false, true, true, true, false, true)
                    for (k in 0 until 7) if (modules[(y + k) * size + x] != pat[k]) match = false
                    if (match) {
                        if (y >= 4 && (0 until 4).all { !modules[(y - 4 + it) * size + x] }) p += 40
                        if (y + 11 <= size && (0 until 4).all { !modules[(y + 7 + it) * size + x] }) p += 40
                    }
                }
                // dark-module balance
                val dark = modules.count { it }
                val total = size * size
                val k = ((kotlin.math.abs(dark * 20L - total * 10L) + total - 1) / total) - 1
                p += (k * 10).toInt()
                return p
            }

            private fun isFinderPattern(off: Int): Boolean {
                val pat = booleanArrayOf(true, false, true, true, true, false, true)
                for (k in 0 until 7) if (modules[off + k] != pat[k]) return false
                return true
            }
        }

        private fun bit(value: Int, index: Int) = (value ushr index) and 1 != 0
    }
}
