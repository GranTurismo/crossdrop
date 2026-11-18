package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.discovery.MdnsHub
import com.crossdrop.discovery.VaultAdvertiser
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.net.VaultHostManager
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files

class DesktopWiringTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-test").toFile()

        /**
         * Where folders that must count as shareable live. On Windows the system temp dir sits under
         * %LOCALAPPDATA%, which isShareable() rightly refuses (AppData), so there they go under the
         * user's home instead; elsewhere temp is fine.
         */
        private val shareRoot: File =
            (if (com.crossdrop.platform.Platform.isWindows)
                Files.createTempDirectory(File(System.getProperty("user.home")).toPath(), "crossdrop-share")
            else Files.createTempDirectory("crossdrop-share")).toFile()

        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively(); shareRoot.deleteRecursively() }
    }

    private class Node(name: String, root: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val identity = SessionIdentity()
        val engine = TransferEngine(identity, { name }, name)
        val inbox = InboxStore(File(root, "inbox"))
        val history = HistoryStore(File(root, "history.json"))
        lateinit var server: TransferServer
        val coordinator: TransferCoordinator
        init {
            val self = this
            server = TransferServer(scope, engine) { }
            coordinator = TransferCoordinator(scope, engine, server, inbox, history)
            coordinator.start()
            server.start()
        }
        fun close() { server.stop(); scope.cancel() }
    }

    private suspend fun <T> awaitNotNull(what: String, diag: () -> String = { "" }, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out after 20s waiting for: $what ${diag()}".trim())

    @Test fun sendsAFileEndToEndAndRecordsHistory() = runBlocking {
        val a = Node("alice", File(home, "a")); val b = Node("bob", File(home, "b"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val payload = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
            val src = File(home, "payload.bin").apply { writeBytes(payload) }

            a.coordinator.sendTo(
                Peer("bob", "bob", Endpoint("127.0.0.1", b.server.port)), listOf(src))

            val offer = awaitNotNull("offer", { "(sender: ${a.coordinator.sessions.value})" }) { b.coordinator.incomingOffer.value }
            assertEquals("payload.bin", offer.offer.files.single().name)
            b.coordinator.respondToOffer(true)

            awaitNotNull("sender done") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status.name == "COMPLETED" } }
            awaitNotNull("receiver done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status.name == "COMPLETED" } }

            assertArrayEquals(payload, b.inbox.list().single().file.readBytes())
            awaitNotNull("histories") { if (a.history.list().size == 1 && b.history.list().size == 1) Unit else null }
            assertEquals("SENDING", a.history.list().single().direction)
            assertEquals("RECEIVING", b.history.list().single().direction)
        } finally { a.close(); b.close() }
    }

    @Test fun declinedOfferLeavesInboxEmpty() = runBlocking {
        val a = Node("alice2", File(home, "a2")); val b = Node("bob2", File(home, "b2"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val src = File(home, "nope.txt").apply { writeText("hello") }
            a.coordinator.sendTo(Peer("bob2", "bob2", Endpoint("127.0.0.1", b.server.port)), listOf(src))
            awaitNotNull("offer", { "(sender: ${a.coordinator.sessions.value})" }) { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(false)
            awaitNotNull("declined") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.DECLINED } }
            assertTrue(b.inbox.list().isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun sendsToTwoDevicesAtOnce() = runBlocking {
        val a = Node("alice3", File(home, "a3")); val b = Node("bob3", File(home, "b3")); val c = Node("carol3", File(home, "c3"))
        try {
            awaitNotNull("listeners") { if (b.server.port != 0 && c.server.port != 0) Unit else null }
            val payload = ByteArray(1024 * 1024 + 5) { (it * 7).toByte() }
            val src = File(home, "both.bin").apply { writeBytes(payload) }
            val gid = a.coordinator.sendTo(
                listOf(
                    Peer("bob3", "bob3", Endpoint("127.0.0.1", b.server.port)),
                    Peer("carol3", "carol3", Endpoint("127.0.0.1", c.server.port)),
                ),
                listOf(src),
            )
            awaitNotNull("offer b") { b.coordinator.incomingOffer.value }; b.coordinator.respondToOffer(true)
            awaitNotNull("offer c") { c.coordinator.incomingOffer.value }; c.coordinator.respondToOffer(true)
            val group = awaitNotNull("both delivered") {
                a.coordinator.groups.value.firstOrNull { it.id == gid }?.takeIf { it.isTerminal && it.completed == 2 }
            }
            assertEquals(2, group.sessions.size)
            assertArrayEquals(payload, b.inbox.list().single().file.readBytes())
            assertArrayEquals(payload, c.inbox.list().single().file.readBytes())
            // History is written right after each session's terminal state is published: wait for it.
            awaitNotNull("both recorded") { a.history.list().takeIf { it.size == 2 } }
            assertEquals(2, a.history.list().size)
        } finally { a.close(); b.close(); c.close() }
    }

    @Test fun textArrivesInlineWithoutTouchingTheInbox() = runBlocking {
        val a = Node("alice4", File(home, "a4")); val b = Node("bob4", File(home, "b4"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            a.coordinator.sendText(listOf(Peer("bob4", "bob4", Endpoint("127.0.0.1", b.server.port))), "https://example.com/x")
            val offer = awaitNotNull("text offer") { b.coordinator.incomingOffer.value }
            assertEquals("https://example.com/x", offer.text)
            b.coordinator.respondToOffer(true)
            awaitNotNull("sent") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            assertTrue(b.inbox.list().isEmpty())
            awaitNotNull("history") { b.history.list().singleOrNull() }
            assertEquals("Link", b.history.list().single().summary)
        } finally { a.close(); b.close() }
    }

    private fun janeCard(): File {
        val jane = com.crossdrop.core.contacts.VContact(
            given = "Jane", family = "Doe", phones = listOf(com.crossdrop.core.contacts.VContact.Labeled("+1 555 0100", "CELL")),
            photo = ByteArray(90_000) { (it * 13).toByte() },
        )
        return File(home, "Jane Doe.vcf").apply { writeText(com.crossdrop.core.contacts.VCard.write(jane)) }
    }

    @Test fun contactCardsArriveAsAContactOfferAndLandInTheInboxHere() = runBlocking {
        val a = Node("alice5", File(home, "a5")); val b = Node("bob5", File(home, "b5"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val card = janeCard()
            a.coordinator.sendTo(Peer("bob5", "bob5", Endpoint("127.0.0.1", b.server.port)), listOf(card))
            val offer = awaitNotNull("contact offer") { b.coordinator.incomingOffer.value }
            assertEquals(com.crossdrop.core.protocol.OfferKind.CONTACT, offer.kind)
            assertEquals(listOf("Jane Doe"), offer.contacts.map { it.displayName })
            assertFalse(offer.offer.text!!.contains("PHOTO"))                      // preview is photo-less
            b.coordinator.respondToOffer(true)
            awaitNotNull("received") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            // No address book on a desktop: the full card (photo included) is saved as a file.
            assertArrayEquals(card.readBytes(), b.inbox.list().single().file.readBytes())
            awaitNotNull("history") { b.history.list().singleOrNull() }
            assertEquals("Jane Doe", b.history.list().single().summary)
        } finally { a.close(); b.close() }
    }

    @Test fun aContactReceiverGetsTheWholeCardInsteadOfTheInbox() = runBlocking {
        val a = Node("alice6", File(home, "a6")); val b = Node("bob6", File(home, "b6"))
        val got = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
        b.coordinator.hub.contactReceiver = object : com.crossdrop.net.ContactReceiver {
            override fun stagingFile(meta: com.crossdrop.core.protocol.FileMeta) = File(home, "staged-${meta.name}")
            override suspend fun received(peerName: String, offer: com.crossdrop.core.protocol.Offer, files: List<File>) {
                got.complete(peerName to files.single().readText())
                files.forEach { it.delete() }
            }
        }
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val card = janeCard()
            a.coordinator.sendTo(Peer("bob6", "bob6", Endpoint("127.0.0.1", b.server.port)), listOf(card))
            awaitNotNull("contact offer") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            val (from, text) = withTimeoutOrNull(20_000) { got.await() } ?: error("receiver never called")
            assertEquals("alice6", from)
            assertEquals(card.readText(), text)
            assertTrue(b.inbox.list().isEmpty())
            // Quiet on the receiving side: no transfer view pops up for a contact card.
            assertTrue(b.coordinator.groups.value.isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun hostsAFolderAsAReadOnlyDriveWithoutTouchingIt() = runBlocking {
        val shared = File(shareRoot, "shared").apply { mkdirs() }
        File(shared, "hello.txt").writeText("hi from the mac")
        val hub = MdnsHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = VaultHostManager(scope, SessionIdentity(), { "test-mac" }, VaultAdvertiser(hub))
        try {
            assertTrue(host.start(writable = false, usePin = false, folder = shared))
            val port = host.state.value!!.port
            fun http(method: String, path: String, body: ByteArray? = null): Pair<Int, String> {
                val c = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
                c.requestMethod = method
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString().orEmpty()
                return code to text
            }
            assertEquals(200 to "hi from the mac", http("GET", "/hello.txt"))
            assertEquals(403, http("PUT", "/evil.txt", "x".toByteArray()).first)   // read-only default
            assertFalse(File(shared, "evil.txt").exists())
            assertEquals(listOf("hello.txt"), shared.list()!!.toList())              // no .staging litter
        } finally {
            host.stop(); hub.stop(); scope.cancel()
        }
        assertEquals(listOf("hello.txt"), shared.list()!!.toList())
    }

    @Test fun aGuestSeesFilesAppearInAHostedDriveWithoutRefreshing() = runBlocking<Unit> {
        val shared = File(shareRoot, "live").apply { mkdirs() }
        File(shared, "first.txt").writeText("one")
        val hub = MdnsHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = VaultHostManager(scope, SessionIdentity(), { "host-mac" }, VaultAdvertiser(hub))
        val guestRoot = File(home, "guest").apply { mkdirs() }
        val explorer = com.crossdrop.net.VaultExplorerManager(
            scope, SessionIdentity(), { "guest" }, "guest-id",
            InboxStore(File(guestRoot, "inbox")), HistoryStore(File(guestRoot, "history.json")))
        try {
            assertTrue(host.start(writable = false, usePin = false, folder = shared))
            val port = host.state.value!!.port
            explorer.mount(
                Peer("host-id", "host-mac", Endpoint("127.0.0.1", port),
                    vault = com.crossdrop.core.VaultDescriptor("live", writable = false, needsPin = false)))

            fun names() = explorer.state.value?.entries?.map { it.name }.orEmpty()
            awaitNotNull("initial listing") { names().takeIf { it.isNotEmpty() } }
            assertEquals(listOf("first.txt"), names())

            // Something lands in the shared folder behind the app's back (a copy in Finder, a download…).
            File(shared, "added-later.txt").writeText("two")
            File(shared, "docs").mkdirs()
            awaitNotNull("new file shown", { "(shows ${names()})" }) { names().takeIf { "added-later.txt" in it && "docs" in it } }

            // Removal and rename follow too, and a size change on an existing file shows up.
            File(shared, "first.txt").delete()
            File(shared, "added-later.txt").writeText("longer content now")
            awaitNotNull("removal shown", { "(shows ${names()})" }) { names().takeIf { "first.txt" !in it } }
            awaitNotNull("size change shown") {
                explorer.state.value?.entries?.firstOrNull { it.name == "added-later.txt" }?.takeIf { it.size == 18L }
            }

            // Browsing into a folder keeps watching that folder, not the root.
            explorer.browse("docs")
            awaitNotNull("browsed") { explorer.state.value?.takeIf { it.path == "docs" && !it.loading } }
            File(shared, "docs/inside.md").writeText("# hi")
            awaitNotNull("file inside folder shown", { "(shows ${names()})" }) { names().takeIf { it == listOf("inside.md") } }
        } finally {
            explorer.close(); host.stop(); hub.stop(); scope.cancel()
        }
    }

    @Test fun refusesToShareHomeLibraryAndRoot() {
        val host = VaultHostManager(CoroutineScope(Dispatchers.Default), SessionIdentity(), { "t" }, VaultAdvertiser(MdnsHub()))
        val userHome = File(System.getProperty("user.home"))
        assertFalse(host.isShareable(File("/")))
        assertFalse(host.isShareable(userHome))
        assertFalse(host.isShareable(File(userHome, "Library")))
        assertFalse(host.isShareable(File(userHome, "Library/Mobile Documents")))
        assertFalse(host.isShareable(File(home, "does-not-exist")))
        assertFalse(host.isShareable(File(home, "support")))                     // CrossDrop's own state
        assertTrue(host.isShareable(File(shareRoot, "a").apply { mkdirs() }))
    }
}
