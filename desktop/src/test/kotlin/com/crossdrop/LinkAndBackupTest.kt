package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/** Works anywhere (announce over a private link) and phone backups to a paired computer, through the real hub. */
class LinkAndBackupTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-link").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    class Node(
        val name: String,
        val root: File,
        val instanceId: String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
        os: String? = null,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TransferEngine(SessionIdentity(), { name }, instanceId, os = os)
        val inbox = InboxStore(File(root, "inbox"))
        val history = HistoryStore(File(root, "history.json"))
        val server = TransferServer(scope, engine) { }
        val coordinator = TransferCoordinator(scope, engine, server, inbox, history)
        val announced = AnnouncedPeers().also { coordinator.hub.announced = it }
        init { coordinator.start(); server.start() }
        val endpoint get() = Endpoint("127.0.0.1", server.port)
        fun close() { server.stop(); scope.cancel() }
    }

    private suspend fun <T> await(what: String, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    /** A computer that keeps phone backups, as the desktop app sets it up. */
    private fun backupComputer(node: Node): Pair<com.crossdrop.core.pairing.PairingStore, com.crossdrop.backup.DesktopBackupReceiver> {
        val pairs = com.crossdrop.core.pairing.PairingStore(File(node.root, "pairings.json"))
        node.coordinator.hub.pairings = pairs
        val folders = java.util.concurrent.ConcurrentHashMap<String, String>()
        val rx = com.crossdrop.backup.DesktopBackupReceiver(
            pairs, node.history, root = { File(node.root, "Backups") }, stateDir = File(node.root, "state"),
            folderNames = object : com.crossdrop.backup.DesktopBackupReceiver.FolderNames {
                override fun get(pairId: String) = folders[pairId]
                override fun put(pairId: String, name: String) { folders[pairId] = name }
            },
        )
        node.coordinator.hub.backupReceiver = rx
        return pairs to rx
    }

    private fun photo(key: String, name: String, bytes: ByteArray, taken: String, video: Boolean = false) =
        com.crossdrop.net.BackupItem(
            key,
            com.crossdrop.core.protocol.FileMeta(
                name, bytes.size.toLong(), if (video) "video/mp4" else "image/jpeg",
                cat = if (video) com.crossdrop.core.protocol.ItemCategory.VIDEO else com.crossdrop.core.protocol.ItemCategory.PHOTO,
                mtime = java.time.LocalDateTime.parse(taken).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
        ) { bytes.inputStream() }

    @Test fun aPairedPhoneBacksUpByMonthAndOnlyRecheckedFilesCanBeFreed() = runBlocking {
        val phone = Node("Pixel", File(home, "phone")); val mac = Node("MacBook", File(home, "mac"))
        val stranger = Node("Somebody's PC", File(home, "stranger"))
        try {
            await("listeners") { if (mac.server.port != 0 && stranger.server.port != 0) Unit else null }
            val (macPairs, rx) = backupComputer(mac)
            backupComputer(stranger)
            val phonePairs = com.crossdrop.core.pairing.PairingStore(File(phone.root, "pairings.json"))
            phone.coordinator.hub.pairings = phonePairs
            val runner = com.crossdrop.net.BackupRunner(phone.coordinator.hub, phone.engine)
            val macPeer = com.crossdrop.core.Peer(mac.instanceId, "MacBook", mac.endpoint)
            val strangerPeer = com.crossdrop.core.Peer(stranger.instanceId, "MacBook", stranger.endpoint)   // borrowed name

            // -- pair: both screens show the same code; the computer's human agrees.
            var codeOnMac: String? = null
            val agree = launch(Dispatchers.Default) {
                val prompt = await("pair prompt") { mac.coordinator.hub.pairPrompt.value }
                assertEquals("Pixel", prompt.peerName)
                codeOnMac = prompt.sas
                mac.coordinator.hub.respondToPair(prompt, true)
            }
            var codeOnPhone: String? = null
            val secret = runner.pair(macPeer, phonePairs) { codeOnPhone = it }!!
            agree.join()
            assertEquals(codeOnPhone, codeOnMac)
            assertEquals("MacBook", phonePairs.devices.value.single().name)
            assertEquals("Pixel", macPairs.devices.value.single().name)

            // -- find it among the devices on the radar (the stranger even took its name).
            assertEquals(mac.instanceId, runner.find(listOf(strangerPeer, macPeer), secret)?.instanceId)

            // -- back up: filed by month, dates kept, identical content stored once.
            val a = ByteArray(300_000) { (it * 7).toByte() }
            val b = ByteArray(200_000) { (it * 11).toByte() }
            val v = ByteArray(700_000) { (it * 3).toByte() }
            val items = listOf(
                photo("p:1:100", "IMG_0001.jpg", a, "2026-10-03T10:15:00"),
                photo("p:2:100", "IMG_0002.jpg", b, "2026-11-01T08:00:00"),
                photo("p:3:100", "IMG_0001 copy.jpg", a, "2026-11-02T09:00:00"),     // same bytes as p:1
                photo("v:4:100", "VID_0004.mp4", v, "2026-11-05T19:30:00", video = true),
            )
            val ledger = com.crossdrop.core.backup.BackupLedger(File(phone.root, "ledger.log"))
            val outcome = runner.run(macPeer, secret, items, ledger)
            assertEquals(com.crossdrop.net.BackupRunner.Outcome.Done(4, 1_500_000), outcome)
            val folder = rx.folderFor(macPairs.devices.value.single().id)
            assertEquals("Pixel", folder.name)
            // The computer files a batch right after confirming it (the phone's ledger only says "received").
            await("filed") { if (rx.index(macPairs.devices.value.single().id).size() == 4) Unit else null }
            assertArrayEquals(a, File(folder, "2026/10/IMG_0001.jpg").readBytes())
            assertArrayEquals(b, File(folder, "2026/11/IMG_0002.jpg").readBytes())
            assertArrayEquals(v, File(folder, "2026/11/VID_0004.mp4").readBytes())
            assertEquals("the copy wasn't stored twice", false, File(folder, "2026/11/IMG_0001 copy.jpg").exists())
            assertEquals(items[0].meta.mtime, File(folder, "2026/10/IMG_0001.jpg").lastModified())
            assertEquals(4, ledger.all().size)

            // -- nothing new: nothing sent.
            assertEquals(com.crossdrop.net.BackupRunner.Outcome.Done(0, 0), runner.run(macPeer, secret, items, ledger))

            // -- before freeing up space the computer re-reads every file; an edited one doesn't count.
            assertEquals(items.map { it.key }.toSet(), runner.verify(macPeer, secret, ledger.all()))
            File(folder, "2026/11/IMG_0002.jpg").writeBytes(ByteArray(200_000) { 1 })
            assertEquals(setOf("p:1:100", "p:3:100", "v:4:100"), runner.verify(macPeer, secret, ledger.all()))
            // Asking the stranger gets nothing, even with the right keys and roots.
            assertEquals(emptySet<String>(), runner.verify(strangerPeer, secret, ledger.all()))

            // -- a device that isn't paired can't back up here, and nothing shows on the computer.
            val notPaired = com.crossdrop.core.transfer.PairSecret("ab".repeat(16), ByteArray(32) { 5 })
            val refused = runner.run(macPeer, notPaired, listOf(photo("p:9:1", "x.jpg", b, "2026-11-01T08:00:00")),
                com.crossdrop.core.backup.BackupLedger(File(phone.root, "other.log")))
            assertEquals(true, refused is com.crossdrop.net.BackupRunner.Outcome.Stopped)
            assertEquals(null, mac.coordinator.incomingOffer.value)
            assertEquals(false, File(folder, "2026/11/x.jpg").exists())
        } finally { phone.close(); mac.close(); stranger.close() }
    }

    @Test fun twoPhonesShakenTogetherFindEachOther() = runBlocking {
        val a = Node("Pixel", File(home, "shake-a")); val b = Node("Galaxy", File(home, "shake-b"))
        val c = Node("Bystander", File(home, "shake-c"))
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0 && c.server.port != 0) Unit else null }
            val pa = com.crossdrop.core.Peer(a.instanceId, "Pixel", a.endpoint)
            val pb = com.crossdrop.core.Peer(b.instanceId, "Galaxy", b.endpoint)
            val pc = com.crossdrop.core.Peer(c.instanceId, "Bystander", c.endpoint)
            // A is shaken; a second later B is. Only B knows A (one-way discovery): the SHAKE fixes that.
            a.coordinator.hub.shake(listOf(pb, pc), a.server.port)
            await("A's shake reached B") { b.announced.peers.value[a.instanceId] }
            assertEquals(null, a.coordinator.hub.shakeMatch.value)
            delay(1_000)
            b.coordinator.hub.shake(listOf(pa), b.server.port)
            val onB = await("B matched") { b.coordinator.hub.shakeMatch.value }
            val onA = await("A matched") { a.coordinator.hub.shakeMatch.value }
            assertEquals(a.instanceId, onB.instanceId)
            assertEquals("Pixel", onB.name)
            assertEquals(b.instanceId, onA.instanceId)
            // C heard A's shake but never shook: nothing happens there.
            delay(300)
            assertEquals(null, c.coordinator.hub.shakeMatch.value)
            // Handled matches clear; nothing about shaking shows as a transfer.
            a.coordinator.hub.shakeHandled(); b.coordinator.hub.shakeHandled()
            assertEquals(null, a.coordinator.hub.shakeMatch.value)
            assertEquals(emptyList<Any>(), a.coordinator.sessions.value + b.coordinator.sessions.value + c.coordinator.sessions.value)
        } finally { a.close(); b.close(); c.close() }
    }

    @Test fun aPhoneThatScannedTheCodeShowsUpOnTheHostAndCanBeSentTo() = runBlocking {
        val host = Node("Host phone", File(home, "host")); val guest = Node("Guest phone", File(home, "guest"))
        try {
            await("listeners") { if (host.server.port != 0 && guest.server.port != 0) Unit else null }
            // What WifiJoin does once it is on the host's network: tell the host where to reach it.
            val (name, id) = guest.engine.control(host.endpoint) { announce(guest.server.port); peerName to peerInstanceId }
            assertEquals("Host phone", name)
            assertEquals(host.instanceId, id)

            val seen = await("guest on the host's radar") { host.announced.peers.value[guest.instanceId] }
            assertEquals("Guest phone", seen.name)
            assertEquals(guest.server.port, seen.endpoint!!.port)
            // Nothing about the announcement looks like a transfer on either side.
            assertEquals(emptyList<Any>(), host.coordinator.sessions.value)

            // The host can now send to the guest through that entry.
            val payload = ByteArray(256 * 1024) { (it * 13).toByte() }
            val src = File(home, "for-guest.bin").apply { writeBytes(payload) }
            host.coordinator.sendTo(seen, listOf(src))
            await("offer") { guest.coordinator.incomingOffer.value }
            guest.coordinator.respondToOffer(true)
            await("delivered") { host.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            assertArrayEquals(payload, guest.inbox.list().single().file.readBytes())
        } finally { host.close(); guest.close() }
    }

    /** What Android's AppInbox does with an app offer, minus the installing: stage each APK, hear about the set. */
    private class FakeAppReceiver(val dir: File) : com.crossdrop.net.AppReceiver {
        @Volatile var got: List<Pair<String?, ByteArray>>? = null
        @Volatile var from: String? = null
        override fun stagingFile(offer: com.crossdrop.core.protocol.Offer, index: Int, meta: com.crossdrop.core.protocol.FileMeta) =
            File(dir, "${offer.transferId}/$index.apk")
        override suspend fun received(peerName: String, offer: com.crossdrop.core.protocol.Offer, files: List<File>) {
            from = peerName
            got = offer.files.mapIndexed { i, m -> m.pkg to files[i].readBytes() }
        }
    }

    private fun apk(name: String, pkg: String, bytes: ByteArray) = com.crossdrop.core.transfer.OutgoingFile(
        com.crossdrop.core.protocol.FileMeta(name, bytes.size.toLong(), "application/vnd.android.package-archive", cat = com.crossdrop.core.protocol.ItemCategory.APP, pkg = pkg),
    ) { bytes.inputStream() }

    @Test fun appsGoToAnotherAndroidPhoneWithAllTheirPiecesAndLandAsFilesElsewhere() = runBlocking {
        val a = Node("Pixel", File(home, "apps-a"), os = com.crossdrop.core.Platforms.ANDROID)
        val b = Node("Galaxy", File(home, "apps-b"), os = com.crossdrop.core.Platforms.ANDROID)
        val c = Node("MacBook", File(home, "apps-c"), os = com.crossdrop.core.Platforms.MAC)
        try {
            await("listeners") { if (a.server.port != 0 && b.server.port != 0 && c.server.port != 0) Unit else null }
            // A phone that reaches another through a code learns from the handshake that it is an Android phone.
            b.engine.control(a.endpoint) { announce(b.server.port) }
            assertEquals("android", await("B on A's radar") { a.announced.peers.value[b.instanceId] }.platform)
            c.engine.control(a.endpoint) { announce(c.server.port) }
            assertEquals("mac", await("C on A's radar") { a.announced.peers.value[c.instanceId] }.platform)

            val rx = FakeAppReceiver(File(home, "apps-b/staging")).also { b.coordinator.hub.appReceiver = it }
            val base1 = ByteArray(300_000) { (it * 7).toByte() }
            val split1 = ByteArray(40_000) { (it * 3).toByte() }
            val base2 = ByteArray(120_000) { (it * 5).toByte() }
            val apps = { listOf(apk("Notes.apk", "com.example.notes", base1), apk("Notes - config.arm64_v8a.apk", "com.example.notes", split1), apk("Maps.apk", "org.example.maps", base2)) }
            val pb = com.crossdrop.core.Peer(b.instanceId, "Galaxy", b.endpoint)
            a.coordinator.hub.send(listOf(pb), apps, com.crossdrop.core.transfer.SendOptions(kind = com.crossdrop.core.protocol.OfferKind.APP))
            val offer = await("app offer on B") { b.coordinator.incomingOffer.value }
            assertEquals(com.crossdrop.core.protocol.OfferKind.APP, offer.kind)
            b.coordinator.respondToOffer(true)
            val got = await("B staged the apps") { rx.got }
            assertEquals("Pixel", rx.from)
            assertEquals(listOf("com.example.notes", "com.example.notes", "org.example.maps"), got.map { it.first })
            assertArrayEquals(base1, got[0].second); assertArrayEquals(split1, got[1].second); assertArrayEquals(base2, got[2].second)
            // Staged privately until installed: nothing in B's inbox.
            assertEquals(0, b.inbox.list().size)

            // A computer (no app receiver) just keeps the APKs as files.
            val pc = com.crossdrop.core.Peer(c.instanceId, "MacBook", c.endpoint)
            a.coordinator.hub.send(listOf(pc), apps, com.crossdrop.core.transfer.SendOptions(kind = com.crossdrop.core.protocol.OfferKind.APP))
            await("app offer on C") { c.coordinator.incomingOffer.value }
            c.coordinator.respondToOffer(true)
            // The last file can still be a `.part` while it lands: wait for the finished names, not a count.
            val filed = setOf("Notes.apk", "Notes - config.arm64_v8a.apk", "Maps.apk")
            await("C filed them") { c.inbox.list().map { it.file.name }.toSet().takeIf { it == filed } }
        } finally { a.close(); b.close(); c.close() }
    }
}
