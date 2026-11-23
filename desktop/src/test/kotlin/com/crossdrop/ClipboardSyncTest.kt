package com.crossdrop

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairedDevice
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.MAX_CLIPBOARD_BYTES
import com.crossdrop.net.ClipShare
import com.crossdrop.net.ClipboardSync
import com.crossdrop.net.CopyOutcome
import com.crossdrop.net.PairedPresence
import com.crossdrop.platform.ClipRead
import com.crossdrop.platform.ClipboardSource
import com.crossdrop.platform.ClipboardWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Clipboard sharing with "My devices": only paired devices, only with both switches on, no echoes, no strangers. */
class ClipboardSyncTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-clip").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    /** A device with its own radar, presence and clipboard (a list of what was put on it). */
    private class Device(name: String, root: File) {
        val node = LinkAndBackupTest.Node(name, root).also { it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json")) }
        val pairs = node.coordinator.hub.pairings!!
        val radar = MutableStateFlow<Map<String, Peer>>(emptyMap())
        val presence = PairedPresence(node.scope, node.engine, pairs, radar, retryMs = 300)
        val pasted = CopyOnWriteArrayList<String>()
        val sync = ClipboardSync(node.scope, node.engine, pairs, presence.present, apply = { pasted += it })
            .also { node.coordinator.hub.clipboardReceiver = it }
        fun asPeer() = Peer(node.instanceId, node.name, node.endpoint)
        fun sees(vararg others: Device) { radar.value = others.associate { it.node.instanceId to it.asPeer() } }
        fun only(): PairedDevice = pairs.devices.value.single()
        fun close() = node.close()
    }

    private fun device(name: String) = Device(name, File(home, name))

    private suspend fun <T> await(what: String, ms: Long = 20_000, block: () -> T?): T =
        withTimeoutOrNull(ms) {
            var v = block()
            while (v == null) { delay(25); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    private suspend fun pair(a: Device, b: Device): PairedDevice = kotlinx.coroutines.coroutineScope {
        await("listeners") { if (a.node.server.port != 0 && b.node.server.port != 0) Unit else null }
        val answer = launch(Dispatchers.Default) {
            val prompt = await("pair prompt") { b.node.coordinator.hub.pairPrompt.value }
            b.node.coordinator.hub.respondToPair(prompt, true)
        }
        val made = a.node.coordinator.hub.pairWith(b.asPeer()) { }!!
        answer.join()
        made
    }

    /** Paired, both switches on, each on the other's radar and recognised. */
    private suspend fun linked(a: Device, b: Device): String {
        val made = pair(a, b)
        a.pairs.setClipboard(made.id, true); b.pairs.setClipboard(made.id, true)
        a.sees(b); b.sees(a)
        await("recognised both ways") { if (made.id in a.presence.present.value && made.id in b.presence.present.value) Unit else null }
        return made.id
    }

    @Test fun aCopyLandsOnTheOtherDeviceAndIsNotSentBack() = runBlocking {
        val a = device("Mac1"); val b = device("Phone1")
        try {
            val id = linked(a, b)
            assertEquals(CopyOutcome.SHARING, a.sync.copied("https://example.com/a?b=c"))
            await("pasted there") { b.pasted.firstOrNull() }
            assertEquals(listOf("https://example.com/a?b=c"), b.pasted)
            await("reported") { a.sync.status.value[id]?.takeIf { it == ClipShare.SENT } }
            assertEquals(ClipShare.RECEIVED, b.sync.status.value[id])

            // The phone's clipboard now holds it; noticing that is not a new copy, so nothing goes back.
            assertEquals(CopyOutcome.UNCHANGED, b.sync.copied("https://example.com/a?b=c"))
            delay(500)
            assertTrue(a.pasted.isEmpty())

            // The other way round works the same, in any script.
            assertEquals(CopyOutcome.SHARING, b.sync.copied("გამარჯობა 👋"))
            await("pasted on the Mac") { a.pasted.firstOrNull() }
            assertEquals(listOf("გამარჯობა 👋"), a.pasted)
            assertEquals("the copy that arrived isn't passed on again", CopyOutcome.UNCHANGED, a.sync.copied("გამარჯობა 👋"))
        } finally { a.close(); b.close() }
    }

    @Test fun nothingIsTakenWhileTheSwitchIsOffOnTheOtherSide() = runBlocking {
        val a = device("Mac2"); val b = device("Phone2")
        try {
            val id = linked(a, b)
            b.pairs.setClipboard(id, false)
            a.sync.copied("not for you yet")
            await("refused") { a.sync.status.value[id]?.takeIf { it == ClipShare.OFF_THERE } }
            assertTrue(b.pasted.isEmpty())
            assertEquals("the phone can say the Mac is sharing", ClipShare.WANTED, b.sync.status.value[id])

            // Switched on over there: the next copy goes.
            b.pairs.setClipboard(id, true)
            a.sync.copied("now it goes")
            await("pasted") { b.pasted.firstOrNull() }
            assertEquals(listOf("now it goes"), b.pasted)

            // Off here: nothing leaves at all.
            a.pairs.setClipboard(id, false)
            assertEquals(CopyOutcome.NOT_SHARING, a.sync.copied("stays here"))
            delay(500)
            assertEquals(listOf("now it goes"), b.pasted)
        } finally { a.close(); b.close() }
    }

    @Test fun aDeviceThatCannotProveThePairingGetsNothing() = runBlocking {
        val a = device("Mac3"); val b = device("Phone3")
        val impostor = device("Phone3")                      // same name, no key
        try {
            val id = linked(a, b)
            // Even if the radar were fooled into placing the paired device at the impostor's address, the copy is only
            // sent after the device on the other end proves the pairing on that very connection.
            val forged = MutableStateFlow(mapOf(id to impostor.asPeer()))
            val sync = ClipboardSync(a.node.scope, a.node.engine, a.pairs, forged, apply = { })
            impostor.pairs.add(id, "Mac3", ByteArray(32), accept = true, backup = false)   // knows the id, not the key
            impostor.pairs.setClipboard(id, true)
            sync.copied("my bank PIN is 1234")
            await("not proven") { sync.status.value[id]?.takeIf { it == ClipShare.FAILED } }
            assertTrue(impostor.pasted.isEmpty())
            assertTrue(b.pasted.isEmpty())
        } finally { a.close(); b.close(); impostor.close() }
    }

    @Test fun aCopyWaitsForADeviceThatShowsUpSoonButNotForever() = runBlocking {
        val a = device("Mac4"); val b = device("Phone4")
        try {
            val id = linked(a, b)
            a.sees()                                          // the phone left
            await("gone") { if (id !in a.presence.present.value) Unit else null }
            a.sync.copied("meeting at 3")
            delay(400)
            assertTrue(b.pasted.isEmpty())
            a.sees(b)                                         // …and is back within the minute
            await("delivered on arrival") { b.pasted.firstOrNull() }
            assertEquals(listOf("meeting at 3"), b.pasted)

            // An old copy doesn't turn up later: a sync whose copies only wait 300 ms.
            var now = 0L
            val quick = ClipboardSync(a.node.scope, a.node.engine, a.pairs, a.presence.present, apply = { }, freshMs = 300, clock = { now })
            a.sees(); await("gone again") { if (id !in a.presence.present.value) Unit else null }
            quick.copied("stale by the time it's back")
            now += 1_000
            a.sees(b)
            await("back") { a.presence.present.value[id] }
            delay(800)
            assertEquals(listOf("meeting at 3"), b.pasted)
        } finally { a.close(); b.close() }
    }

    @Test fun quickCopiesArriveInOrderAndTooLongIsRefused() = runBlocking {
        val a = device("Mac5"); val b = device("Phone5")
        try {
            linked(a, b)
            for (i in 1..6) a.sync.copied("copy $i")
            await("the last copy is what's left") { b.pasted.lastOrNull()?.takeIf { it == "copy 6" } }
            delay(500)
            assertEquals("copy 6", b.pasted.last())
            assertEquals(CopyOutcome.TOO_LONG, a.sync.copied("x".repeat(MAX_CLIPBOARD_BYTES + 1)))
            assertEquals(CopyOutcome.EMPTY, a.sync.copied("   "))
        } finally { a.close(); b.close() }
    }

    @Test fun shareNowSaysWhoGotIt() = runBlocking {
        val a = device("Phone6"); val b = device("Mac6")
        try {
            val id = linked(a, b)
            b.sync.copied("from the Mac")
            await("pasted on the phone") { a.pasted.firstOrNull() }
            // The tile shares even what the clipboard already held.
            val r = a.sync.shareNow("from the Mac")
            assertEquals(CopyOutcome.SHARING, r.outcome)
            assertEquals(mapOf(id to ClipShare.SENT), r.results)
            assertEquals(listOf("from the Mac"), b.pasted)
        } finally { a.close(); b.close() }
    }

    // ------------------------------------------------------------------ the desktop watcher --

    private class FakeClipboard(var counter: Boolean = true) : ClipboardSource {
        @Volatile var text: String? = null
        @Volatile var secret = false
        @Volatile var busy = false
        @Volatile var stamp = 0L
        val reads = java.util.concurrent.atomic.AtomicInteger()
        fun copy(t: String, secret: Boolean = false) { this.secret = secret; text = t; stamp++ }
        override fun stamp(): Long? = if (counter) stamp else null
        override fun read(): ClipRead? {
            if (busy) throw IllegalStateException("cannot open system clipboard")
            reads.incrementAndGet()
            return text?.let { ClipRead(it, secret) }
        }
        override fun write(text: String) { copy(text) }
    }

    @Test fun theWatcherReportsNewCopiesOnly() = runBlocking {
        for (counter in listOf(true, false)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clip = FakeClipboard(counter).apply { copy("there before") }
            val active = MutableStateFlow(false)
            val seen = CopyOnWriteArrayList<String>()
            val watcher = ClipboardWatcher(clip, active, onCopy = { seen += it }, intervalMs = 20)
            try {
                watcher.start(scope)
                clip.copy("while nobody shares"); delay(100)
                active.value = true; delay(100)
                assertTrue("what was there when sharing began isn't a copy ($counter)", seen.isEmpty())
                if (counter) assertEquals("with a counter, nothing is read until something is copied", 0, clip.reads.get())

                clip.copy("hello"); await("reported") { seen.firstOrNull() }
                clip.copy("hunter2", secret = true); delay(150)
                assertEquals("a password manager's copy stays here", listOf("hello"), seen)

                watcher.write("from the phone"); delay(150)
                assertEquals("what arrived isn't reported back", listOf("hello"), seen)

                clip.busy = true; clip.copy("while busy"); delay(100)
                clip.busy = false
                await("read once the clipboard is free") { seen.getOrNull(1) }
                assertEquals(listOf("hello", "while busy"), seen)

                active.value = false; delay(60)
                clip.copy("after turning it off"); delay(100)
                active.value = true; delay(100)
                assertEquals(listOf("hello", "while busy"), seen)
            } finally { scope.cancel() }
        }
    }
}
