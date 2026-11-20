package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.protocol.VerifyItem
import com.crossdrop.core.transfer.ControlHandler
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.PairSecret
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.SecureRandom

/** Announce, pairing, recognising a paired device, backup checks and pairing proofs on offers. */
class ControlTest {

    private val idA = "0123456789abcdef"
    private val idB = "fedcba9876543210"
    private fun engine(name: String, id: String, os: String? = null) = TransferEngine(SessionIdentity(), { name }, id, os = os)

    /** One connection: [initiator] runs [block] against a listener served by [rxEngine] with [handler]. */
    private fun <T> exchange(
        rxEngine: TransferEngine,
        handler: ControlHandler,
        initiator: TransferEngine,
        decide: (com.crossdrop.core.transfer.InboundRequest) -> ReceiveDecision = { ReceiveDecision.Reject() },
        block: suspend (Endpoint) -> T,
    ): Pair<T, List<TransferEvent>> = runBlocking {
        val listener = ServerSocket(0)
        val served = async { rxEngine.receiveRequest(listener.accept(), handler) { decide(it) }.toList() }
        val result = block(Endpoint("127.0.0.1", listener.localPort))
        val events = served.await()
        listener.close()
        result to events
    }

    @Test
    fun `announce tells the listener where to reach the initiator`() {
        var seen: List<Any?>? = null
        val handler = object : ControlHandler {
            override fun announced(name: String, instanceId: String, host: String, port: Int, os: String?) { seen = listOf(name, instanceId, host, port, os) }
        }
        val a = engine("Guest phone", idA, os = Platforms.ANDROID)
        val (peer, events) = exchange(engine("Host phone", idB, os = Platforms.ANDROID), handler, a) { ep ->
            a.control(ep) { announce(43210); Triple(peerName, peerInstanceId, peerOs) }
        }
        // Both ends learn what the other runs on from the handshake (how a phone knows it may offer apps).
        assertEquals(listOf("Guest phone", idA, "127.0.0.1", 43210, "android"), seen)
        assertEquals(Triple("Host phone", idB, "android"), peer)
        assertTrue("a control exchange is not a transfer", events.none { it is TransferEvent.Failed || it is TransferEvent.Completed })
    }

    @Test
    fun `pairing gives both sides the same key, only when the human agrees`() {
        var stored: Triple<String, String, ByteArray>? = null
        var askedSas: String? = null
        val yes = object : ControlHandler {
            override suspend fun pairRequested(name: String, sas: String): Boolean { askedSas = sas; return true }
            override fun paired(pairId: String, name: String, key: ByteArray) { stored = Triple(pairId, name, key) }
        }
        val a = engine("Pixel", idA)
        val pairId = "00112233445566778899aabbccddeeff"
        val (result, _) = exchange(engine("MacBook", idB), yes, a) { ep -> a.control(ep) { sas to pair(pairId) } }
        val (sas, key) = result
        assertNotNull(key)
        assertEquals(sas, askedSas)
        assertEquals(pairId, stored!!.first)
        assertEquals("Pixel", stored!!.second)
        assertArrayEquals(key, stored!!.third)

        val no = object : ControlHandler { override suspend fun pairRequested(name: String, sas: String) = false }
        val (declined, _) = exchange(engine("MacBook", idB), no, a) { ep -> a.control(ep) { pair(pairId) } }
        assertNull(declined)
    }

    @Test
    fun `a paired device is recognised both ways, a stranger is not`() {
        val shared = PairSecret("00112233445566778899aabbccddeeff", random(32))
        val other = PairSecret("ffeeddccbbaa99887766554433221100", random(32))
        val computer = object : ControlHandler { override fun pairings() = listOf(other, shared) }
        val phone = engine("Pixel", idA)
        val (found, _) = exchange(engine("MacBook", idB), computer, phone) { ep ->
            phone.control(ep) { pairCheck(listOf(PairSecret("aa".repeat(16), random(32)), shared)) }
        }
        assertEquals(shared.id, found?.id)

        val stranger = object : ControlHandler { override fun pairings() = listOf(other) }
        val (none, _) = exchange(engine("Somebody's PC", idB), stranger, phone) { ep -> phone.control(ep) { pairCheck(listOf(shared)) } }
        assertNull(none)

        // A device that "knows" nothing can't fake a match either.
        val liar = object : ControlHandler { override fun pairings() = listOf(PairSecret(shared.id, random(32))) }
        val (fake, _) = exchange(engine("MacBook", idB), liar, phone) { ep -> phone.control(ep) { pairCheck(listOf(shared)) } }
        assertNull(fake)
    }

    @Test
    fun `backup verify answers only for the matched pairing, in parts`() {
        val shared = PairSecret("00112233445566778899aabbccddeeff", random(32))
        val asked = mutableListOf<Int>()
        val computer = object : ControlHandler {
            override fun pairings() = listOf(shared)
            override fun verify(pairId: String, items: List<VerifyItem>): List<String> {
                assertEquals(shared.id, pairId)
                asked += items.size
                return items.filter { it.key.endsWith("0") }.map { it.key } + "not-asked"
            }
        }
        val phone = engine("Pixel", idA)
        val items = (0 until 4_500).map { VerifyItem("k$it", "r$it", it.toLong()) }
        val (ok, _) = exchange(engine("MacBook", idB), computer, phone) { ep ->
            phone.control(ep) { pairCheck(listOf(shared)); verify(items) }
        }
        assertEquals(listOf(2_000, 2_000, 500), asked)
        assertEquals(450, ok.size)
        assertTrue("never more than was asked", "not-asked" !in ok)
    }

    @Test
    fun `a backup offer goes only to the device that proves the pairing`() {
        val key = random(32)
        val bytes = random(100_000)
        val file = OutgoingFile(FileMeta("IMG_1.jpg", bytes.size.toLong(), "image/jpeg", key = "p:1")) { ByteArrayInputStream(bytes) }
        val phone = engine("Pixel", idA)

        // The real computer: holds the key, answers with the proof, gets the bytes.
        val out = ByteArrayOutputStream()
        val (sent, _) = exchange(engine("MacBook", idB), object : ControlHandler {}, phone, decide = { req ->
            val pair = req.pairedWith(listOf(PairSecret("x", key)))
            assertNotNull(pair)
            assertEquals(OfferKind.BACKUP, req.offer.kind)
            assertEquals("p:1", req.offer.files.single().key)
            ReceiveDecision.Accept({ out }, pairKey = pair!!.key)
        }) { ep -> phone.send(ep, listOf(file), SendOptions(kind = OfferKind.BACKUP, pairKey = key)).toList() }
        assertTrue(sent.last() is TransferEvent.Completed)
        assertArrayEquals(bytes, out.toByteArray())

        // A device that took the computer's name accepts, but can't prove the pairing: nothing is sent.
        val stolen = ByteArrayOutputStream()
        val (refused, _) = exchange(engine("MacBook", idB), object : ControlHandler {}, phone, decide = {
            ReceiveDecision.Accept({ stolen })
        }) { ep -> phone.send(ep, listOf(file), SendOptions(kind = OfferKind.BACKUP, pairKey = key)).toList() }
        assertTrue(refused.last() is TransferEvent.Failed)
        assertEquals(0, stolen.size())
    }

    @Test
    fun `completed transfers report the agreed roots`() {
        val bytes = random(300_000)
        val phone = engine("Pixel", idA)
        val dir = java.nio.file.Files.createTempDirectory("roots").toFile()
        val store = com.crossdrop.core.delta.FileDeltaStore(dir)
        val (tx, rx) = exchange(engine("MacBook", idB), object : ControlHandler {}, phone, decide = {
            ReceiveDecision.Accept({ ByteArrayOutputStream() }, store)
        }) { ep -> phone.send(ep, listOf(OutgoingFile(FileMeta("a.bin", bytes.size.toLong())) { ByteArrayInputStream(bytes) })).toList() }
        val sentRoots = (tx.last() as TransferEvent.Completed).roots
        val gotRoots = (rx.last() as TransferEvent.Completed).roots
        assertEquals(listOf(com.crossdrop.core.delta.Blake3.hash(bytes).joinToString("") { "%02x".format(it) }), sentRoots)
        assertEquals(sentRoots, gotRoots)
        dir.deleteRecursively()
    }

    /**
     * Fixed vectors for the pairing key and proofs, also checked against an independent HKDF/HMAC (Python's
     * hashlib) when written. The Swift port must produce the same bytes (CrossDropCore `ControlTests`).
     */
    @Test
    fun `pairing key and proofs match the known answers`() {
        val shared = ByteArray(32) { it.toByte() }
        val binding = ByteArray(32) { (it * 3).toByte() }
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        val key = com.crossdrop.core.crypto.SessionCrypto.pairKey(shared, binding, "00112233445566778899aabbccddeeff")
        assertEquals("2502acdf9b6803cbc7d2eb7b18d21a906d4a025fc08adf09609ad607b1d758d4", hex(key))
        assertEquals("bf47ea8b92d32d4ce616304e07a0c406cde37909528b4a716eed6d8a28a3eb97",
            hex(com.crossdrop.core.crypto.SessionCrypto.pairProof(key, "check-i", binding, ByteArray(16) { (it + 7).toByte() })))
        assertEquals("cc23aac790b0dc5ec2c724562a90ea143b4607793eda9cce736de6ae44ecb2b5",
            hex(com.crossdrop.core.crypto.SessionCrypto.pairProof(key, "offer", binding, "tid-1".toByteArray())))
    }

    private fun random(n: Int) = ByteArray(n).also(SecureRandom()::nextBytes)
}
