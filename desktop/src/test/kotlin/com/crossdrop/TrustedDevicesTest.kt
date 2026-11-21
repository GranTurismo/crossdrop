package com.crossdrop

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.PairPurpose
import com.crossdrop.net.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** "My devices": two devices that paired send each other files without asking; nobody else gets that. */
class TrustedDevicesTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-trusted").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    private fun node(name: String) = LinkAndBackupTest.Node(name, File(home, name)).also {
        it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json"))
    }

    private fun LinkAndBackupTest.Node.asPeer() = Peer(instanceId, name, endpoint)

    private suspend fun <T> await(what: String, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    /** [a] asks [b] to pair as "My devices"; [b]'s human agrees (or not). */
    private suspend fun pair(a: LinkAndBackupTest.Node, b: LinkAndBackupTest.Node, agree: Boolean = true) = kotlinx.coroutines.coroutineScope {
        val answer = launch(Dispatchers.Default) {
            val prompt = await("pair prompt on ${b.name}") { b.coordinator.hub.pairPrompt.value }
            assertEquals(PairPurpose.DEVICES, prompt.purpose)
            b.coordinator.hub.respondToPair(prompt, agree)
        }
        val made = a.coordinator.hub.pairWith(b.asPeer()) { }
        answer.join()
        made
    }

    private fun send(from: LinkAndBackupTest.Node, to: LinkAndBackupTest.Node, name: String): File {
        val f = File(Files.createTempDirectory(home.toPath(), "src").toFile(), name).apply { writeText("content of $name") }
        from.coordinator.sendTo(to.asPeer(), listOf(f))
        return f
    }

    private suspend fun delivered(to: LinkAndBackupTest.Node, name: String, from: LinkAndBackupTest.Node? = null) {
        val diag = { "(receiver sessions ${to.coordinator.sessions.value.map { it.status to it.error }}, prompt=${to.coordinator.incomingOffer.value != null}, sender ${from?.coordinator?.sessions?.value?.map { it.status to it.error }})" }
        withTimeoutOrNull(20_000) { while (to.inbox.list().none { it.name == name }) delay(50); true } ?: error("$name not delivered ${diag()}")
        await("$name delivered") { to.inbox.list().firstOrNull { it.name == name } }
        await("receiver done") { to.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
    }

    @Test fun pairedDevicesSendEachOtherFilesWithoutAskingAndEitherSideCanTurnThatOff() = runBlocking {
        val a = node("Pixel"); val b = node("MacBook"); val stranger = node("Somebody's PC")
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0 && stranger.server.port != 0) Unit else null }
            val made = pair(a, b)!!
            val aPairs = a.coordinator.hub.pairings!!; val bPairs = b.coordinator.hub.pairings!!
            // both sides hold it, accept files without asking, and it isn't a backup pairing
            for ((store, who) in listOf(aPairs to "MacBook", bPairs to "Pixel")) {
                val d = store.devices.value.single()
                assertEquals(who, d.name); assertTrue(d.accept); assertFalse(d.backup)
            }
            assertTrue(made.accept)

            // -- 1: no prompt at all, the file just arrives
            send(a, b, "one.txt")
            delivered(b, "one.txt", a)
            assertNull("nothing was asked", b.coordinator.incomingOffer.value)
            assertEquals("content of one.txt", File(b.inbox.dir, "one.txt").readText())
            // …and the other way round
            send(b, a, "back.txt"); delivered(a, "back.txt"); assertNull(a.coordinator.incomingOffer.value)

            // -- 2: the receiver turns it off: it asks again, and the paired sender is still recognised (no "not your device")
            bPairs.setAccept(bPairs.devices.value.single().id, false)
            send(a, b, "two.txt")
            val prompt = await("prompt once auto-accept is off") { b.coordinator.incomingOffer.value }
            assertEquals("two.txt", prompt.offer.files.single().name)
            b.coordinator.respondToOffer(true)
            delivered(b, "two.txt")

            // -- 3: a stranger always gets asked, even when it borrows a paired device's name
            // same display name, own storage: it holds no pairing key
            val impostor = LinkAndBackupTest.Node("Pixel", File(home, "impostor")).also {
                it.coordinator.hub.pairings = PairingStore(File(it.root, "pairings.json"))
            }
            await("impostor listener") { impostor.server.port.takeIf { it != 0 } }
            bPairs.setAccept(bPairs.devices.value.single().id, true)
            send(impostor, b, "three.txt")
            await("a prompt for the impostor") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(false)
            assertNull(b.inbox.list().firstOrNull { it.name == "three.txt" })
            impostor.close()

            // -- 4: after Unpair on the receiver alone, the sender is no longer recognised: it just asks (and is not refused)
            bPairs.remove(bPairs.devices.value.single().id)
            send(a, b, "four.txt")
            await("prompt after unpair") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            delivered(b, "four.txt")
        } finally { a.close(); b.close(); stranger.close() }
    }

    @Test fun declinedPairingAddsNothingOnEitherSide() = runBlocking {
        val a = node("Tab"); val b = node("Laptop")
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0) Unit else null }
            assertNull(pair(a, b, agree = false))
            assertTrue(a.coordinator.hub.pairings!!.devices.value.isEmpty())
            assertTrue(b.coordinator.hub.pairings!!.devices.value.isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun aPairingMadeForDevicesCannotBeUsedToBackUp() = runBlocking {
        val phone = node("Pixel 2"); val mac = node("Mac 2")
        try {
            await("listeners") { if (phone.server.port != 0 && mac.server.port != 0) Unit else null }
            pair(phone, mac)!!
            val secret = phone.coordinator.hub.pairings!!.secrets().single()
            val runner = com.crossdrop.net.BackupRunner(phone.coordinator.hub, phone.engine)
            val item = com.crossdrop.net.BackupItem("p:1:1", com.crossdrop.core.protocol.FileMeta("a.jpg", 3, "image/jpeg",
                cat = com.crossdrop.core.protocol.ItemCategory.PHOTO)) { "abc".byteInputStream() }
            val ledger = com.crossdrop.core.backup.BackupLedger(File(phone.root, "ledger.log"))
            val outcome = runner.run(mac.asPeer(), secret, listOf(item), ledger)
            assertTrue("refused: $outcome", outcome is com.crossdrop.net.BackupRunner.Outcome.Stopped)
        } finally { phone.close(); mac.close() }
    }

    @Test fun oldPairingsKeepMeaningBackupsAndNoSilentAccept() {
        // a pairings.json written before the two flags existed
        val file = File(home, "legacy-pairings.json")
        file.writeText("""[{"id":"${"a".repeat(32)}","name":"Old Mac","key":"${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}","pairedAt":1,"lastSeen":2}]""")
        val d = PairingStore(file).devices.value.single()
        assertTrue("an old pairing is a backup pairing", d.backup)
        assertFalse("and never starts accepting files unasked", d.accept)
    }

    @Test fun aDeviceThatKeepsNoBackupsNeverAcceptsABackupPairing() = runBlocking {
        // A phone holds pairings now ("Add a device"), but it never stores backups for anyone. A request in the old,
        // backup-only form must be refused outright: if it were shown and agreed to, the phone's own backup logic would
        // adopt that device as "my computer" and start sending it every photo.
        val computer = node("Mac Z"); val phone = node("Pixel Z")     // phone: pairings, but no backup receiver
        try {
            await("listeners") { if (computer.server.port != 0 && phone.server.port != 0) Unit else null }
            var prompted = false
            val watcher = launch(Dispatchers.Default) {
                repeat(80) {
                    phone.coordinator.hub.pairPrompt.value?.let { prompted = true; phone.coordinator.hub.respondToPair(it, true) }
                    delay(50)
                }
            }
            val runner = com.crossdrop.net.BackupRunner(computer.coordinator.hub, computer.engine)
            val secret = runner.pair(phone.asPeer(), computer.coordinator.hub.pairings!!) { }
            watcher.cancel()
            assertNull("refused", secret)
            assertFalse("the person was never asked", prompted)
            assertTrue(phone.coordinator.hub.pairings!!.devices.value.isEmpty())
            assertTrue(computer.coordinator.hub.pairings!!.devices.value.isEmpty())
        } finally { computer.close(); phone.close() }
    }
}
