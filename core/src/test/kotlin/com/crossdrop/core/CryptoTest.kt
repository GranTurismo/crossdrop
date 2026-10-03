package com.crossdrop.core

import com.crossdrop.core.crypto.RecordCipher
import com.crossdrop.core.crypto.SessionCrypto
import com.crossdrop.core.crypto.SessionIdentity
import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test

class CryptoTest {

    @Test
    fun `both sides derive symmetric keys and identical SAS`() {
        val a = SessionIdentity()
        val b = SessionIdentity()

        val ka = SessionCrypto.deriveSession(a, b.publicKey, isInitiator = true)
        val kb = SessionCrypto.deriveSession(b, a.publicKey, isInitiator = false)

        // A writes with what B reads, and vice versa.
        assertArrayEquals(ka.sendKey, kb.recvKey)
        assertArrayEquals(ka.recvKey, kb.sendKey)
        assertEquals(ka.sas, kb.sas)
        assertEquals(6, ka.sas.length)
    }

    @Test
    fun `record cipher roundtrip`() {
        val key = ByteArray(32) { it.toByte() }
        val seal = RecordCipher(key)
        val open = RecordCipher(key)

        val msgs = listOf("hello".toByteArray(), ByteArray(0), ByteArray(100_000) { 0x5A })
        val sealed = msgs.map { seal.seal(it) }
        sealed.forEachIndexed { i, ct -> assertArrayEquals(msgs[i], open.open(ct)) }
    }

    @Test(expected = InvalidCipherTextException::class)
    fun `tampered ciphertext fails`() {
        val key = ByteArray(32) { 7 }
        val ct = RecordCipher(key).seal("payload".toByteArray())
        ct[ct.size / 2] = (ct[ct.size / 2] + 1).toByte()
        RecordCipher(key).open(ct)
    }

    @Test(expected = InvalidCipherTextException::class)
    fun `reordered records fail (nonce mismatch)`() {
        val key = ByteArray(32) { 9 }
        val seal = RecordCipher(key)
        val first = seal.seal("one".toByteArray())
        val second = seal.seal("two".toByteArray())
        val open = RecordCipher(key)
        open.open(second) // skipping `first` desynchronizes the counter
        open.open(first)
    }
}
