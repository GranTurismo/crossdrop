package com.crossdrop.core.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TLS-record-style AEAD: each frame is sealed with ChaCha20-Poly1305 under a
 * nonce derived from a monotonically increasing per-direction counter.
 * Reordered, dropped, replayed or tampered records all fail authentication.
 */
class RecordCipher(key: ByteArray) {
    private val keyParam = KeyParameter(key)
    private var counter: Long = 0

    fun seal(plaintext: ByteArray): ByteArray = crypt(plaintext, forEncryption = true)

    fun open(ciphertext: ByteArray): ByteArray = crypt(ciphertext, forEncryption = false)

    private fun crypt(input: ByteArray, forEncryption: Boolean): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(keyParam, TAG_BITS, nextNonce()))
        val out = ByteArray(cipher.getOutputSize(input.size))
        var len = cipher.processBytes(input, 0, input.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun nextNonce(): ByteArray {
        // 12-byte nonce: 4 zero bytes || u64 big-endian counter.
        val n = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putLong(counter)
            .array()
        counter++
        return n
    }

    private companion object {
        const val TAG_BITS = 128
    }
}
