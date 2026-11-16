package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.protocol.TrustGrant
import com.crossdrop.core.protocol.decode
import com.crossdrop.core.protocol.encode
import com.crossdrop.core.transfer.InboundRequest
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import com.crossdrop.core.transfer.TrustCredential
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Offer kinds, inline text, switch batches and trust grants (accept once, then skip the prompt). */
class OfferExtrasTest {

    private fun engine(name: String) = TransferEngine(SessionIdentity(), { name }, UUID.randomUUID().toString().take(8))

    private fun file(name: String, bytes: ByteArray, cat: String? = null) =
        OutgoingFile(FileMeta(name, bytes.size.toLong(), "application/octet-stream", cat = cat, mtime = 1_700_000_000_000)) {
            ByteArrayInputStream(bytes)
        }

    /** One send → receive over loopback; returns (sender events, receiver events, request seen). */
    private fun roundTrip(
        files: List<OutgoingFile>,
        options: SendOptions,
        decide: (InboundRequest) -> ReceiveDecision,
    ): Triple<List<TransferEvent>, List<TransferEvent>, InboundRequest?> = runBlocking {
        val listener = ServerSocket(0)
        var seen: InboundRequest? = null
        val rx = engine("receiver")
        val job = async { rx.receiveRequest(listener.accept()) { req -> seen = req; decide(req) }.toList() }
        val tx = engine("sender").send(Endpoint("127.0.0.1", listener.localPort), files, options).toList()
        val r = job.await()
        listener.close()
        Triple(tx, r, seen)
    }

    private fun accept(grant: TrustGrant? = null) =
        ReceiveDecision.Accept({ ByteArrayOutputStream() }, grant = grant)

    @Test
    fun `text offer carries the text inline and as a file`() {
        val text = "https://example.com/a?b=c"
        val (tx, _, req) = roundTrip(
            listOf(file("Link.txt", text.toByteArray())), SendOptions(kind = OfferKind.TEXT, text = text),
        ) { accept() }
        assertEquals(OfferKind.TEXT, req!!.offer.kind)
        assertEquals(text, req.offer.text)
        assertTrue(tx.last() is TransferEvent.Completed)
    }

    @Test
    fun `oversized text is not inlined`() {
        val text = "x".repeat(70 * 1024)
        val (_, _, req) = roundTrip(
            listOf(file("Text.txt", text.toByteArray())), SendOptions(kind = OfferKind.TEXT, text = text),
        ) { accept() }
        assertNull(req!!.offer.text)
    }

    @Test
    fun `item category, time and batch survive the wire`() {
        val batch = BatchInfo(index = 2, count = 5, photos = 10, videos = 3, contacts = 40, totalBytes = 1234)
        val (_, _, req) = roundTrip(
            listOf(file("IMG_1.JPG", ByteArray(10), cat = "photo")), SendOptions(kind = OfferKind.SWITCH, batch = batch),
        ) { accept() }
        assertEquals("photo", req!!.offer.files.single().cat)
        assertEquals(1_700_000_000_000, req.offer.files.single().mtime)
        assertEquals(batch, req.offer.batch)
    }

    @Test
    fun `grant reaches the sender and a later offer proves it`() {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val grant = TrustGrant("g1", Base64.getEncoder().encodeToString(token), OfferKind.CAMERA, 60_000)
        val (tx, _, _) = roundTrip(listOf(file("a.jpg", ByteArray(5))), SendOptions(kind = OfferKind.CAMERA)) { accept(grant) }
        val got = tx.filterIsInstance<TransferEvent.TrustGranted>().single().grant
        assertEquals(grant, got)

        val cred = TrustCredential.of(got)!!
        var proved = false
        val (tx2, _, req2) = roundTrip(
            listOf(file("b.jpg", ByteArray(5))), SendOptions(kind = OfferKind.CAMERA, trust = cred),
        ) { req -> proved = req.provesTrust(token); accept() }
        assertTrue(proved)
        assertEquals("g1", req2!!.offer.trustId)
        assertTrue(tx2.last() is TransferEvent.Completed)
    }

    @Test
    fun `wrong token does not prove trust`() {
        val cred = TrustCredential("g1", ByteArray(32) { 1 })
        var proved = true
        roundTrip(listOf(file("b.jpg", ByteArray(5))), SendOptions(trust = cred)) { req ->
            proved = req.provesTrust(ByteArray(32) { 2 }); accept()
        }
        assertFalse(proved)
    }

    @Test
    fun `offer without a proof never proves trust`() {
        var proved = true
        roundTrip(listOf(file("b.jpg", ByteArray(5))), SendOptions()) { req ->
            proved = req.provesTrust(ByteArray(32)); accept()
        }
        assertFalse(proved)
    }

    @Test
    fun `a proof copied from another connection fails`() {
        val token = ByteArray(32) { 7 }
        var firstOffer: Offer? = null
        roundTrip(listOf(file("a", ByteArray(1))), SendOptions(trust = TrustCredential("g", token))) { req ->
            firstOffer = req.offer; accept()
        }
        // Replay: same transferId and proof, but on a fresh connection (different binding).
        var proved = true
        roundTrip(listOf(file("a", ByteArray(1))), SendOptions()) { req ->
            val forged = InboundRequest(req.peerName, req.peerInstanceId, req.sas, firstOffer!!, ByteArray(32))
            proved = forged.provesTrust(token)
            accept()
        }
        assertFalse(proved)
    }

    @Test
    fun `a trusted transfer id is accepted once`() {
        val store = com.crossdrop.core.transfer.TrustStore()
        val grant = store.grant(OfferKind.CAMERA, "phone", 60_000)
        val cred = TrustCredential.of(grant)!!
        var first: Any? = null
        var replay: Any? = "unset"
        var captured: InboundRequest? = null
        roundTrip(listOf(file("a", ByteArray(1))), SendOptions(kind = OfferKind.CAMERA, trust = cred)) { req ->
            first = store.match(req); captured = req; accept()
        }
        // Same request presented again (what replaying a recorded session amounts to).
        replay = store.match(captured!!)
        assertNotNull(first)
        assertNull(replay)
    }

    /** Same inputs give the same proof in the Swift core (`OfferExtrasTests.testProofKnownAnswer`). */
    @Test
    fun `proof known answer`() {
        val p = com.crossdrop.core.crypto.SessionCrypto.trustProof(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "t")
        assertEquals("gPKpMCI2GQVihgj2WHUXG16IFWN9tU/jpjsRkiYSk2U=", Base64.getEncoder().encodeToString(p))
    }

    /** The JSON an older receiver sees still decodes into its own (narrower) Offer. */
    @Serializable
    private data class OldFileMeta(val name: String, val size: Long, val mime: String? = null)
    @Serializable
    private data class OldOffer(val transferId: String, val files: List<OldFileMeta>)

    @Test
    fun `older receivers ignore the new fields`() {
        val offer = Offer(
            "t", listOf(FileMeta("a.jpg", 3, "image/jpeg", cat = "photo", mtime = 5)),
            kind = OfferKind.SWITCH, text = "hi", trustId = "x", trustProof = "y",
            batch = BatchInfo(0, 1),
        )
        val old = offer.encode().decode<OldOffer>()
        assertEquals("a.jpg", old.files.single().name)
        assertEquals(3L, old.files.single().size)
    }
}
