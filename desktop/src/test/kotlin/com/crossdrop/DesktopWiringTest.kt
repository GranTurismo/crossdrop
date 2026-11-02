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

    private suspend fun <T> awaitNotNull(what: String, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out after 20s waiting for: $what")

    @Test fun sendsAFileEndToEndAndRecordsHistory() = runBlocking {
        val a = Node("alice", File(home, "a")); val b = Node("bob", File(home, "b"))
        try {
            awaitNotNull("listener") { b.server.port.takeIf { it != 0 } }
            val payload = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
            val src = File(home, "payload.bin").apply { writeBytes(payload) }

            a.coordinator.sendTo(
                Peer("bob", "bob", Endpoint("127.0.0.1", b.server.port)), listOf(src))

            val offer = awaitNotNull("offer") { b.coordinator.incomingOffer.value }
            assertEquals("payload.bin", offer.offer.files.single().name)
            b.coordinator.respondToOffer(true)

            awaitNotNull("sender done") { a.coordinator.session.value?.takeIf { it.status.name == "COMPLETED" } }
            awaitNotNull("receiver done") { b.coordinator.session.value?.takeIf { it.status.name == "COMPLETED" } }

            assertArrayEquals(payload, b.inbox.list().single().file.readBytes())
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
            awaitNotNull("offer") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(false)
            awaitNotNull("declined") { a.coordinator.session.value?.takeIf { it.status == SessionStatus.DECLINED } }
            assertTrue(b.inbox.list().isEmpty())
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
