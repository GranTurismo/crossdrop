package com.crossdrop.core

import com.crossdrop.core.delta.Blake3
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Official BLAKE3 test vectors (test_vectors.json, hash mode, first 32 bytes
 * of each extended output). Input is the repeating sequence i % 251.
 */
class Blake3Test {

    private fun input(len: Int) = ByteArray(len) { (it % 251).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val vectors = mapOf(
        0 to "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
        1 to "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
        3 to "e1be4d7a8ab5560aa4199eea339849ba8e293d55ca0a81006726d184519e647f",
        1023 to "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11",
        1024 to "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7",
        1025 to "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444",
        2049 to "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030",
        3072 to "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2",
        5121 to "628bd2cb2004694adaab7bbd778a25df25c47b9d4155a55f8fbd79f2fe154cff",
        16384 to "f875d6646de28985646f34ee13be9a576fd515f76b5b0a26bb324735041ddde4",
        102400 to "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085",
    )

    @Test
    fun `official vectors one-shot`() {
        for ((len, want) in vectors) {
            assertEquals("len=$len", want, hex(Blake3.hash(input(len))))
        }
    }

    @Test
    fun `official vectors streamed`() {
        for ((len, want) in vectors) {
            val h = Blake3()
            h.update(ByteArrayInputStream(input(len)))
            assertEquals("len=$len", want, h.hexDigest())
        }
    }

    @Test
    fun `odd update sizes match one-shot`() {
        val data = input(3073)
        val want = hex(Blake3.hash(data))
        for (step in intArrayOf(1, 63, 64, 65, 999, 1024, 2048)) {
            val h = Blake3()
            var off = 0
            while (off < data.size) {
                val n = minOf(step, data.size - off)
                h.update(data, off, n)
                off += n
            }
            assertEquals("step=$step", want, h.hexDigest())
        }
    }
}
