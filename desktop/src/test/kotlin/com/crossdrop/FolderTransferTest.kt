package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.TooManyFilesException
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.net.toOutgoingFolder
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** A whole folder goes as one offer and arrives as the same tree; what a hostile sender names can't escape the inbox. */
class FolderTransferTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-folders").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    private class Node(name: String, val root: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TransferEngine(SessionIdentity(), { name }, name)
        val inbox = InboxStore(File(root, "inbox"), File(root, "state"))
        val server: TransferServer = TransferServer(scope, engine) { }
        val coordinator = TransferCoordinator(scope, engine, server, inbox, HistoryStore(File(root, "history.json")))
        init { coordinator.start(); server.start() }
        val peer get() = Peer(root.name, root.name, Endpoint("127.0.0.1", server.port))
        fun close() { server.stop(); scope.cancel() }
    }

    private suspend fun <T> awaitNotNull(what: String, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out after 20s waiting for: $what")

    private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    private fun makeFolder(name: String): File {
        val f = File(home, "src-$name/Holiday").apply { mkdirs() }
        File(f, "a.txt").writeText("alpha")
        File(f, "Day 1").mkdirs(); File(f, "Day 1/b.bin").writeBytes(ByteArray(2 * 1024 * 1024 + 3) { (it * 13).toByte() })
        File(f, "Day 1/Beach").mkdirs(); File(f, "Day 1/Beach/c.txt").writeText("sand")
        File(f, "Empty").mkdirs()                                   // empty folders are not sent
        File(f, ".DS_Store").writeText("junk"); File(f, "Day 1/Thumbs.db").writeText("junk")
        return f
    }

    @Test fun aFolderArrivesAsTheSameTreeAndASecondSendDoesNotDuplicateIt() = runBlocking {
        val a = Node("fa", File(home, "fa")); val b = Node("fb", File(home, "fb"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val folder = makeFolder("one")
            val want = listOf("Holiday/Day 1/Beach/c.txt", "Holiday/Day 1/b.bin", "Holiday/a.txt")

            repeat(2) { round ->
                a.coordinator.sendTo(b.peer, listOf(folder))
                val offer = awaitNotNull("offer #$round") { b.coordinator.incomingOffer.value }
                assertEquals("Holiday", Folders.topFolder(offer.offer.files))
                assertEquals(want.sorted(), offer.offer.files.map { "${it.dir}/${it.name}" }.sorted())
                b.coordinator.respondToOffer(true)
                awaitNotNull("receiver done #$round") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
                awaitNotNull("sender done #$round") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
                b.coordinator.dismissGroup(b.coordinator.sessions.value.last().groupId)
                a.coordinator.dismissGroup(a.coordinator.sessions.value.last().groupId)

                assertEquals("round $round: same tree, no (1) copies", want, tree(b.inbox.dir))
            }
            assertArrayEquals(File(folder, "Day 1/b.bin").readBytes(), File(b.inbox.dir, "Holiday/Day 1/b.bin").readBytes())
            assertEquals("sand", File(b.inbox.dir, "Holiday/Day 1/Beach/c.txt").readText())
            assertEquals("the list shows each file under its folder", want, b.inbox.list().map { it.name }.sorted())
        } finally { a.close(); b.close() }
    }

    @Test fun whatASenderNamesCannotEscapeTheInbox() = runBlocking {
        val a = Node("ha", File(home, "ha")); val b = Node("hb", File(home, "hb"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val evil = listOf(
                FileMeta("../../evil.txt", 4, dir = "../../../etc/x\\..\\y"),
                FileMeta("C:\\Windows\\system.ini", 4, dir = "/abs/olute"),
                FileMeta("CON", 4, dir = "NUL/COM1"),
            )
            a.coordinator.hub.send(listOf(b.peer), { evil.map { m -> OutgoingFile(m) { ByteArrayInputStream("evil".toByteArray()) } } })
            awaitNotNull("offer") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            awaitNotNull("receiver done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }

            val inbox = b.inbox.dir.canonicalFile
            val landed = inbox.walkTopDown().filter { it.isFile }.toList()
            assertEquals(3, landed.size)
            assertTrue("everything stays inside the inbox", landed.all { it.canonicalFile.startsWith(inbox) })
            assertFalse(File(b.root, "evil.txt").exists())
            assertFalse(File(home, "evil.txt").exists())
            // no segment is ever "..", and Windows device names are defused
            assertTrue(landed.none { f -> f.relativeTo(inbox).invariantSeparatorsPath.split('/').any { it == ".." || it.equals("CON", true) || it.equals("NUL", true) } })
        } finally { a.close(); b.close() }
    }

    @Test fun wipingTouchesOnlyWhatCrossDropReceived() {
        val root = File(home, "wipe/inbox").apply { mkdirs() }
        val inbox = InboxStore(root, File(home, "wipe/state"))
        // a folder of the person's own that happens to sit in the inbox
        File(root, "Mine").mkdirs(); File(root, "Mine/keep.txt").writeText("mine")
        // a received folder, as a receive creates it
        val meta = FileMeta("pic.jpg", 3, dir = "Trip/Day 1")
        inbox.uniqueFile(meta).writeText("pic")
        inbox.uniqueFile(FileMeta("n.txt", 1, dir = "Trip")).writeText("n")

        assertEquals(listOf("Trip/Day 1/pic.jpg", "Trip/n.txt"), inbox.list().map { it.name }.sorted())
        inbox.shredAll()
        assertTrue(inbox.list().isEmpty())
        assertFalse("emptied received folders are removed", File(root, "Trip").exists())
        assertEquals("mine", File(root, "Mine/keep.txt").readText())
    }

    @Test fun movingTheInboxCarriesReceivedFoldersAndLeavesTheOthers() {
        val first = File(home, "mv/first").apply { mkdirs() }
        val second = File(home, "mv/second").apply { mkdirs() }
        val inbox = InboxStore(first, File(home, "mv/state"))
        inbox.uniqueFile(FileMeta("pic.jpg", 3, dir = "Trip/Day 1")).writeText("pic")
        File(first, "Mine").mkdirs(); File(first, "Mine/keep.txt").writeText("mine")

        val old = inbox.switchTo(second)
        assertEquals(1, inbox.moveFrom(old))
        assertEquals("pic", File(second, "Trip/Day 1/pic.jpg").readText())
        assertEquals(listOf("Trip/Day 1/pic.jpg"), inbox.list().map { it.name })
        assertTrue("someone else's folder stays", File(first, "Mine/keep.txt").exists())
        assertFalse(File(first, "Trip").exists())
    }

    @Test fun aFolderWithTooManyFilesIsRefusedNotHalfSent() {
        val big = File(home, "big/Many").apply { mkdirs() }
        repeat(Folders.MAX_FILES + 1) { File(big, "f$it").createNewFile() }
        var refused: TooManyFilesException? = null
        try { big.toOutgoingFolder() } catch (e: TooManyFilesException) { refused = e }
        assertEquals(Folders.MAX_FILES, refused?.limit)
    }

    @Test fun linksInAFolderAreNotFollowed() {
        val outside = File(home, "links/outside").apply { mkdirs() }; File(outside, "secret.txt").writeText("secret")
        val folder = File(home, "links/Pack").apply { mkdirs() }; File(folder, "ok.txt").writeText("ok")
        val linked = runCatching { Files.createSymbolicLink(File(folder, "out").toPath(), outside.toPath()) }.isSuccess
        if (!linked) return   // no symlink rights here (some Windows setups): nothing to check
        assertEquals(listOf("ok.txt"), folder.toOutgoingFolder().map { it.meta.name })
    }

    @Test fun aSenderCannotMergeIntoOrOverwriteWhatThePersonAlreadyKeptInTheInbox() = runBlocking {
        // The inbox can be any folder the person picked, and may already hold their own files and folders. A device that
        // is allowed to send must not be able to put its files into one of the person's folders (which "Wipe inbox"
        // would then treat as received) or replace one of their files that happens to have the same name.
        val a = Node("oa", File(home, "oa")); val b = Node("ob", File(home, "ob"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            File(b.inbox.dir, "Taxes").mkdirs(); File(b.inbox.dir, "Taxes/2025.pdf").writeText("the person's own")
            File(b.inbox.dir, "plans.txt").writeText("the person's own plans")

            val evil = listOf(
                FileMeta("2025.pdf", 5, dir = "Taxes"),                 // would land in their folder
                FileMeta("plans.txt", 5),                               // would replace their file
            )
            a.coordinator.hub.send(listOf(b.peerOf()), { evil.map { m -> OutgoingFile(m) { ByteArrayInputStream("hello".toByteArray()) } } })
            awaitNotNull("offer") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            awaitNotNull("done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }

            assertEquals("the person's folder is untouched", "the person's own", File(b.inbox.dir, "Taxes/2025.pdf").readText())
            assertEquals("the person's file is untouched", "the person's own plans", File(b.inbox.dir, "plans.txt").readText())
            assertEquals("the folder arrived beside it", "hello", File(b.inbox.dir, "Taxes (1)/2025.pdf").readText())
            assertEquals("the file arrived beside it", "hello", File(b.inbox.dir, "plans (1).txt").readText())

            // Wipe reaches the folders CrossDrop received, never the person's own (loose files can't be told apart, which
            // the wipe warning says when the inbox is a folder they picked).
            b.inbox.shredAll()
            assertEquals("the person's own", File(b.inbox.dir, "Taxes/2025.pdf").readText())
            assertFalse(File(b.inbox.dir, "Taxes (1)").exists())
        } finally { a.close(); b.close() }
    }

    private fun Node.peerOf() = Peer(root.name, root.name, Endpoint("127.0.0.1", server.port))

    @Test fun differentNamesInAnyScriptAllArriveEvenWhenTheyCleanToTheSamePlace() = runBlocking {
        val a = Node("ua", File(home, "ua")); val b = Node("ub", File(home, "ub"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            // names in other scripts keep their letters; and two names that still clean to one place do not replace each other
            val metas = listOf(
                FileMeta("ბაღი.jpg", 3), FileMeta("ზღვა.jpg", 3),          // used to be one "____.jpg"
                FileMeta("a:b.txt", 3), FileMeta("a_b.txt", 3),           // still collide once cleaned
            )
            a.coordinator.hub.send(listOf(b.peerOf()), { metas.map { m -> OutgoingFile(m) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) } } })
            awaitNotNull("offer") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            awaitNotNull("done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            val names = b.inbox.list().map { it.name }.sorted()
            assertEquals(4, names.size)
            assertTrue(names.toString(), "ბაღი.jpg" in names && "ზღვა.jpg" in names)
            assertEquals(2, names.count { it.startsWith("a_b") })
        } finally { a.close(); b.close() }
    }

    @Test fun inAFolderThePersonPickedOnlyWhatWasReceivedIsListedAndWiped() {
        val root = File(home, "picked/inbox").apply { mkdirs() }
        File(root, "taxes.pdf").writeText("the person's own")
        val inbox = InboxStore(root, File(home, "picked/state"), custom = true)
        inbox.uniqueFile(FileMeta("from-a-friend.txt", 1)).writeText("received")
        assertEquals(listOf("from-a-friend.txt"), inbox.list().map { it.name })
        inbox.shredAll()
        assertEquals("the person's own", File(root, "taxes.pdf").readText())
        assertFalse(File(root, "from-a-friend.txt").exists())
        // CrossDrop's own folder lists every loose file, as before
        val own = InboxStore(File(home, "own/inbox").apply { mkdirs() }, File(home, "own/state"))
        File(own.dir, "x.bin").writeText("x")
        assertEquals(listOf("x.bin"), own.list().map { it.name })
    }

    @Test fun aLinkInTheInboxIsNeverFollowedOrOverwritten() {
        val outside = File(home, "link/outside.txt").apply { parentFile.mkdirs(); writeText("precious") }
        val root = File(home, "link/inbox").apply { mkdirs() }
        val linked = runCatching { Files.createSymbolicLink(File(root, "shortcut.txt").toPath(), outside.toPath()) }.isSuccess
        if (!linked) return                                              // no symlink rights here (some Windows setups)
        val inbox = InboxStore(root, File(home, "link/state"))
        assertTrue("not listed", inbox.list().none { it.name == "shortcut.txt" })
        inbox.shred(File(root, "shortcut.txt"))                           // even if asked directly
        assertEquals("what it pointed at is untouched", "precious", outside.readText())
        assertFalse("the link itself went", File(root, "shortcut.txt").exists())
    }

    @Test fun anIndexLeftByADeletedFileDoesNotLetASenderReplaceTheNextFileOfThatName() = runBlocking {
        val a = Node("ia", File(home, "ia")); val b = Node("ib", File(home, "ib"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val first = File(home, "idx-src/report.pdf").apply { parentFile.mkdirs(); writeText("version one from a friend") }
            a.coordinator.sendTo(b.peerOf(), listOf(first))
            awaitNotNull("offer") { b.coordinator.incomingOffer.value }; b.coordinator.respondToOffer(true)
            awaitNotNull("done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            val firstSession = b.coordinator.sessions.value.last().id
            b.coordinator.dismissGroup(b.coordinator.sessions.value.last().groupId)
            // the person deletes it (their file manager does not clean our bookkeeping) and saves their own report.pdf there
            File(b.inbox.dir, "report.pdf").delete()
            File(b.inbox.dir, "report.pdf").writeText("the person's own report, different")

            first.writeText("version two from the friend")
            a.coordinator.sendTo(b.peerOf(), listOf(first))
            awaitNotNull("second offer") { b.coordinator.incomingOffer.value }; b.coordinator.respondToOffer(true)
            awaitNotNull("second done") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED && it.id != firstSession } }
            assertEquals("the person's own report", "the person's own report, different", File(b.inbox.dir, "report.pdf").readText())
            assertEquals("version two from the friend", File(b.inbox.dir, "report (1).pdf").readText())
        } finally { a.close(); b.close() }
    }

    @Test fun aFolderIsNeverSentDeeperThanAReceiverRebuildsIt() {
        var dir = File(home, "deep/Root").apply { mkdirs() }
        repeat(25) { dir = File(dir, "L$it").apply { mkdirs() }; File(dir, "f.txt").writeText("x") }
        val metas = File(home, "deep/Root").toOutgoingFolder().map { it.meta }
        assertTrue(metas.isNotEmpty())
        // every file's folder path (the picked folder first) fits the receiver's limit: none would land a level up
        assertTrue(metas.all { it.dir!!.split('/').size <= Folders.MAX_DEPTH })
        assertEquals(Folders.MAX_DEPTH - 1, metas.size)
    }
}
