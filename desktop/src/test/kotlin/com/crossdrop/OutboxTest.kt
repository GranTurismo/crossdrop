package com.crossdrop

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.net.Outbox
import com.crossdrop.net.OutboxEntry
import com.crossdrop.net.OutboxItem
import com.crossdrop.net.OutboxResult
import com.crossdrop.net.OutboxRunner
import com.crossdrop.net.PairedPresence
import com.crossdrop.net.toOutgoingFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** "Send when it's back": picked for one of My devices while it is away, delivered when it proves itself and shows up. */
class OutboxTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-outbox").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    private fun node(name: String, root: String = name) = LinkAndBackupTest.Node(name, File(home, root)).also {
        it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json"))
    }

    private fun LinkAndBackupTest.Node.asPeer() = Peer(instanceId, name, endpoint)

    private suspend fun <T> await(what: String, ms: Long = 20_000, block: () -> T?): T =
        withTimeoutOrNull(ms) {
            var v = block()
            while (v == null) { delay(25); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    private suspend fun pair(a: LinkAndBackupTest.Node, b: LinkAndBackupTest.Node) = kotlinx.coroutines.coroutineScope {
        val answer = launch(Dispatchers.Default) {
            val prompt = await("pair prompt") { b.coordinator.hub.pairPrompt.value }
            b.coordinator.hub.respondToPair(prompt, true)
        }
        val made = a.coordinator.hub.pairWith(b.asPeer()) { }!!
        answer.join()
        made
    }

    private fun file(name: String, text: String): File =
        File(Files.createTempDirectory(home.toPath(), "q").toFile(), name).apply { writeText(text) }

    private class Rig(val a: LinkAndBackupTest.Node, outboxFile: File) {
        val radar = MutableStateFlow<Map<String, Peer>>(emptyMap())
        val pairs = a.coordinator.hub.pairings!!
        val presence = PairedPresence(a.scope, a.engine, pairs, radar, retryMs = 300)
        val removed = CopyOnWriteArrayList<OutboxEntry>()
        val outbox = Outbox(outboxFile, onRemoved = { removed += it })
        val results = CopyOnWriteArrayList<OutboxResult>()
        val runner = OutboxRunner(
            a.scope, a.coordinator.hub, presence, outbox, pairs,
            resolve = { e -> e.items.map { File(it.ref) }.toOutgoingFiles() },
            onResult = { results += it },
        )
        fun show(p: Peer) { radar.value = radar.value + (p.instanceId to p) }
        fun hide(p: Peer) { radar.value = radar.value - p.instanceId }
    }

    @Test fun queuedWhileAwayGoesOutWhenTheDeviceProvesItselfAndAppears() = runBlocking {
        val a = node("Phone"); val b = node("Laptop")
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0) Unit else null }
            val made = pair(a, b)
            val rig = Rig(a, File(home, "o1.json"))
            val doc = file("report.pdf", "quarterly numbers")

            rig.outbox.add(made.id, "Laptop", listOf(OutboxItem("path", doc.path, doc.name)))
            delay(600)
            assertTrue("nothing leaves while it is away", b.inbox.list().isEmpty())
            assertEquals(1, rig.outbox.entries.value.size)

            rig.show(b.asPeer())
            await("delivered") { b.inbox.list().firstOrNull { it.name == "report.pdf" } }
            assertNull("paired and accepting: no prompt", b.coordinator.incomingOffer.value)
            assertEquals("quarterly numbers", File(b.inbox.dir, "report.pdf").readText())
            await("entry cleared") { if (rig.outbox.entries.value.isEmpty()) Unit else null }
            assertTrue(rig.results.single().delivered)
            assertEquals(1, rig.removed.size)
            assertTrue("it shows in the sender's history", a.history.list().any { it.ok && it.peerName == "Laptop" })
        } finally { a.close(); b.close() }
    }

    @Test fun aDeviceThatOnlyBorrowedTheNameNeverGetsIt() = runBlocking {
        val a = node("Phone2"); val b = node("Laptop2")
        val impostor = LinkAndBackupTest.Node("Laptop2", File(home, "impostor-laptop")).also {
            it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json"))
        }
        try {
            await("listeners") { if (a.server.port != 0 && impostor.server.port != 0 && b.server.port != 0) Unit else null }
            val made = pair(a, b)
            val rig = Rig(a, File(home, "o2.json"))
            val doc = file("secret.txt", "for the real laptop only")
            rig.outbox.add(made.id, "Laptop2", listOf(OutboxItem("path", doc.path, doc.name)))

            rig.show(impostor.asPeer())                       // same name, no pairing key
            delay(1_500)
            assertTrue(impostor.inbox.list().isEmpty())
            assertNull(impostor.coordinator.incomingOffer.value)
            assertEquals(1, rig.outbox.entries.value.size)
            assertTrue(rig.presence.present.value.isEmpty())
        } finally { a.close(); b.close(); impostor.close() }
    }

    @Test fun aNoWaitsForTheNextVisitInsteadOfAskingAgainAtOnce() = runBlocking {
        val a = node("Phone3"); val b = node("Laptop3")
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0) Unit else null }
            val made = pair(a, b)
            b.coordinator.hub.pairings!!.let { it.setAccept(it.devices.value.single().id, false) }     // it asks before accepting
            val rig = Rig(a, File(home, "o3.json"))
            val doc = file("notes.txt", "meeting notes")
            rig.outbox.add(made.id, "Laptop3", listOf(OutboxItem("path", doc.path, doc.name)))

            rig.show(b.asPeer())
            await("the prompt") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(false)
            await("counted as a try") { rig.outbox.entries.value.singleOrNull()?.takeIf { it.attempts == 1 } }
            delay(1_200)
            assertNull("not asked again while it stays here", b.coordinator.incomingOffer.value)

            rig.hide(b.asPeer()); delay(400)                  // it leaves…
            rig.show(b.asPeer())                              // …and is back
            await("asked once more") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            await("delivered this time") { b.inbox.list().firstOrNull { it.name == "notes.txt" } }
            await("entry cleared") { if (rig.outbox.entries.value.isEmpty()) Unit else null }
        } finally { a.close(); b.close() }
    }

    @Test fun theQueueSurvivesARestartAndForgetsUnpairedDevices() {
        val f = File(home, "o4.json")
        val first = Outbox(f)
        first.add("p1", "Laptop", listOf(OutboxItem("path", "/x/a.txt", "a.txt")))
        first.add("p2", "Tablet", listOf(OutboxItem("path", "/x/b.txt", "b.txt")))
        val again = Outbox(f)
        assertEquals(listOf("Laptop", "Tablet"), again.entries.value.map { it.deviceName })
        again.prune(setOf("p1"))                              // p2 was unpaired
        assertEquals(listOf("Laptop"), again.entries.value.map { it.deviceName })
        assertEquals(listOf("Laptop"), Outbox(f).entries.value.map { it.deviceName })
    }

    @Test fun limitsAgeAndRealFailuresAreEnforced() {
        var now = 1_000_000_000L
        val gone = CopyOnWriteArrayList<OutboxEntry>()
        val box = Outbox(File(home, "o5.json"), clock = { now }, onRemoved = { gone += it })
        val item = listOf(OutboxItem("path", "/x/a", "a"))
        val first = box.add("p", "Laptop", item)!!
        repeat(Outbox.MAX_ENTRIES) { box.add("p", "Laptop", item) }
        assertEquals(Outbox.MAX_ENTRIES, box.entries.value.size)
        assertFalse("the oldest made room", box.entries.value.any { it.id == first.id })
        assertEquals(first.id, gone.single().id)

        val e = box.entries.value.first()
        assertFalse(box.failed(e.id)); assertFalse(box.failed(e.id))
        assertTrue("the third real failure drops it", box.failed(e.id))
        assertFalse(box.entries.value.any { it.id == e.id })

        now += Outbox.MAX_AGE_MS + 1
        box.prune(setOf("p"))
        assertTrue(box.entries.value.isEmpty())
        assertNull(box.add("p", "Laptop", emptyList()))
        // more than one entry can hold: the rest becomes another entry, nothing picked is dropped
        val many = (1..Outbox.MAX_ITEMS + 30).map { OutboxItem("path", "/x/f$it", "f$it") }
        val first2 = box.add("p", "Laptop", many)!!
        assertEquals(Outbox.MAX_ITEMS, first2.items.size)
        assertEquals(Outbox.MAX_ITEMS + 30, box.entries.value.sumOf { it.items.size })
    }

    @Test fun aDeviceIsRecognisedAgainAfterBeingUnpairedAndPairedAnew() = runBlocking {
        val a = node("Phone6"); val b = node("Laptop6")
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0) Unit else null }
            val first = pair(a, b)
            val rig = Rig(a, File(home, "o6.json"))
            rig.show(b.asPeer())
            assertEquals(b.instanceId, await("recognised") { rig.presence.present.value[first.id] }.instanceId)

            // unpaired on both sides, then paired afresh (a new pairing id) while it stays on the radar
            rig.pairs.remove(first.id); b.coordinator.hub.pairings!!.let { st -> st.devices.value.forEach { st.remove(it.id) } }
            await("no longer a paired device") { if (rig.presence.present.value.isEmpty()) Unit else null }
            val second = pair(a, b)
            assertTrue(first.id != second.id)
            assertEquals(b.instanceId, await("recognised under the new pairing") { rig.presence.present.value[second.id] }.instanceId)
        } finally { a.close(); b.close() }
    }

    @Test fun aRadarIdAppearingAtAnotherAddressMustProveItselfAgain() = runBlocking {
        val a = node("Phone7"); val b = node("Laptop7")
        val impostor = LinkAndBackupTest.Node("Laptop7", File(home, "impostor7")).also {
            it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json"))
        }
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0 && impostor.server.port != 0) Unit else null }
            val made = pair(a, b)
            val rig = Rig(a, File(home, "o7.json"))
            rig.show(b.asPeer())
            await("recognised") { rig.presence.present.value[made.id] }

            // the radar id is public (it is in the mDNS record): the same id now answers somewhere else
            rig.radar.value = mapOf(b.instanceId to Peer(b.instanceId, b.name, impostor.endpoint))
            delay(1_500)
            assertTrue("the proof was given from another address; it does not carry over", rig.presence.present.value.isEmpty())
        } finally { a.close(); b.close(); impostor.close() }
    }
}
