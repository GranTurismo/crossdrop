package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.transfer.ResumePolicy
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** A send whose connection drops mid-way waits for the device and finishes by itself; the receiver is not asked twice. */
class AutoResumeTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-resume").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    private class Node(name: String, policy: ResumePolicy = ResumePolicy()) {
        val root = File(home, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TransferEngine(SessionIdentity(), { name }, "id-$name")
        val inbox = InboxStore(File(root, "inbox"), File(root, "state"))
        val server = TransferServer(scope, engine) { }
        val coordinator = TransferCoordinator(scope, engine, server, inbox, HistoryStore(File(root, "history.json")), policy)
        init { coordinator.hub.pairings = com.crossdrop.core.pairing.PairingStore(File(root, "pairings.json")); coordinator.start(); server.start() }
        fun close() { server.stop(); scope.cancel() }
    }

    /** A TCP relay in front of a node that can be told to cut the first connection part-way, and to refuse new ones. */
    private class FlakyProxy(private val targetPort: Int) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        @Volatile var cutFirstAfterBytes = -1L
        @Volatile var cutOnConnection = 1              // which connection `cutFirstAfterBytes` applies to (a paired send opens a check first)
        private var accepted = 0
        @Volatile var refuse = false
        @Volatile var throttleUpstreamMs = 0L          // pause per 16 KB sent towards the device: a slow link
        private val live = ConcurrentLinkedQueue<Socket>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (_: Exception) { break }
                    if (refuse) { c.close(); continue }
                    val up = Socket("127.0.0.1", targetPort)
                    live += c; live += up
                    accepted++
                    val cut = if (accepted == cutOnConnection) cutFirstAfterBytes else -1L
                    if (accepted == cutOnConnection) cutFirstAfterBytes = -1
                    thread(isDaemon = true) { pipe(c, up, cut, true) }
                    thread(isDaemon = true) { pipe(up, c, -1, false) }
                }
            }
        }
        private fun pipe(from: Socket, to: Socket, cutAfter: Long, throttle: Boolean) {
            val buf = ByteArray(16 * 1024); var total = 0L
            try {
                while (true) {
                    val n = from.getInputStream().read(buf); if (n < 0) break
                    to.getOutputStream().write(buf, 0, n); total += n
                    if (throttle && throttleUpstreamMs > 0) Thread.sleep(throttleUpstreamMs)
                    if (cutAfter in 0..total) { sever(); break }
                }
            } catch (_: Exception) { }
            runCatching { from.close() }; runCatching { to.close() }
        }
        fun sever() { live.forEach { runCatching { it.close() } }; live.clear() }
        fun close() { runCatching { server.close() }; sever() }
    }

    private suspend fun <T> await(what: String, ms: Long = 20_000, block: () -> T?): T =
        withTimeoutOrNull(ms) {
            var v = block()
            while (v == null) { delay(25); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    private val quick = ResumePolicy(windowMs = 30_000, pausesMs = listOf(150))

    @Test fun aDroppedSendWaitsForTheDeviceAndFinishesWithoutAskingTwice() = runBlocking {
        val a = Node("ra", quick); val b = Node("rb")
        val proxy = FlakyProxy(await("listener") { b.server.port.takeIf { it != 0 } })
        try {
            val peer = Peer("rb-id", "rb", Endpoint("127.0.0.1", proxy.port))
            a.coordinator.hub.radar = MutableStateFlow(mapOf(peer.instanceId to peer))
            val payload = ByteArray(8 * 1024 * 1024 + 11) { (it * 31 + it / 7).toByte() }
            val src = File(home, "big.bin").apply { writeBytes(payload) }
            proxy.cutFirstAfterBytes = 2L * 1024 * 1024

            val seen = ConcurrentLinkedQueue<SessionStatus>()
            val watcher = thread(isDaemon = true) {
                while (true) { a.coordinator.sessions.value.lastOrNull()?.let { seen += it.status }; Thread.sleep(5) }
            }
            a.coordinator.sendTo(peer, listOf(src))
            await("the first, real prompt") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)

            val done = await("sender finished by itself") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            await("receiver finished") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            assertNull("the repeat was taken without a second prompt", b.coordinator.incomingOffer.value)
            assertFalse("it never showed as failed", SessionStatus.FAILED in seen)
            assertTrue("what had arrived was not sent again (${done.bytesSkipped} skipped)", done.bytesSkipped > 0)
            assertArrayEquals(payload, b.inbox.list().single().file.readBytes())
            // the dead first receive was replaced, not left behind as a failed card
            assertTrue(b.coordinator.sessions.value.none { it.status == SessionStatus.FAILED })
            watcher.interrupt()
        } finally { proxy.close(); a.close(); b.close() }
    }

    @Test fun ifTheDeviceNeverComesBackItFailsAfterTheWindowWithAPlainMessage() = runBlocking {
        val a = Node("rc", ResumePolicy(windowMs = 1_500, pausesMs = listOf(100))); val b = Node("rd")
        val proxy = FlakyProxy(await("listener") { b.server.port.takeIf { it != 0 } })
        try {
            val peer = Peer("rd-id", "rd", Endpoint("127.0.0.1", proxy.port))
            a.coordinator.hub.radar = MutableStateFlow(mapOf(peer.instanceId to peer))
            val src = File(home, "big2.bin").apply { writeBytes(ByteArray(8 * 1024 * 1024) { it.toByte() }) }
            proxy.cutFirstAfterBytes = 1024 * 1024
            a.coordinator.sendTo(peer, listOf(src))
            await("prompt") { b.coordinator.incomingOffer.value }
            proxy.refuse = true                       // the device is gone for good
            b.coordinator.respondToOffer(true)

            val failed = await("gives up", ms = 15_000) { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.FAILED } }
            // the device's own name from the handshake, in a plain sentence
            assertTrue(failed.error!!, failed.error!!.startsWith("Lost the connection to rd"))
            assertFalse(failed.resuming)
        } finally { proxy.close(); a.close(); b.close() }
    }

    @Test fun cancellingWhileItWaitsStopsTheRetries() = runBlocking {
        val a = Node("re", ResumePolicy(windowMs = 30_000, pausesMs = listOf(100))); val b = Node("rf")
        val proxy = FlakyProxy(await("listener") { b.server.port.takeIf { it != 0 } })
        try {
            val peer = Peer("rf-id", "rf", Endpoint("127.0.0.1", proxy.port))
            // the device is not on the radar: the send waits for it
            a.coordinator.hub.radar = MutableStateFlow(mapOf(peer.instanceId to peer))
            val src = File(home, "big3.bin").apply { writeBytes(ByteArray(8 * 1024 * 1024) { it.toByte() }) }
            proxy.cutFirstAfterBytes = 1024 * 1024
            val gid = a.coordinator.sendTo(peer, listOf(src))
            await("prompt") { b.coordinator.incomingOffer.value }
            proxy.refuse = true
            b.coordinator.respondToOffer(true)
            await("it is waiting") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.resuming } }
            a.coordinator.cancelGroup(gid)
            assertEquals(SessionStatus.CANCELLED, a.coordinator.sessions.value.last().status)
            proxy.refuse = false
            delay(800)
            assertEquals("no retry after cancelling", SessionStatus.CANCELLED, a.coordinator.sessions.value.last().status)
            assertNull(b.coordinator.incomingOffer.value)
        } finally { proxy.close(); a.close(); b.close() }
    }

    @Test fun aReceiversCancelIsNotUndoneByTheSendersRetries() = runBlocking {
        // To the sender a receiver's Cancel during a fast (push) transfer looks like a dropped connection, so its
        // auto-resume tries again. The receiver must be asked, not silently given the transfer back, and answering
        // "no" must end the retrying.
        val a = Node("rg", quick); val b = Node("rh")
        val proxy = FlakyProxy(await("listener") { b.server.port.takeIf { it != 0 } })
        try {
            val peer = Peer("rh-id", "rh", Endpoint("127.0.0.1", proxy.port))
            a.coordinator.hub.radar = MutableStateFlow(mapOf(peer.instanceId to peer))
            val src = File(home, "big4.bin").apply { writeBytes(ByteArray(24 * 1024 * 1024) { (it * 5).toByte() }) }
            proxy.throttleUpstreamMs = 4                      // a slow link, so there is time to cancel mid-way
            a.coordinator.sendTo(peer, listOf(src))
            await("the first prompt") { b.coordinator.incomingOffer.value }
            b.coordinator.respondToOffer(true)
            await("receiving") { b.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.TRANSFERRING } }
            b.coordinator.cancelGroup(b.coordinator.sessions.value.last().groupId)         // the person at the receiving end says stop
            // Stay slow until the sender has noticed the connection went (a blocked read only sees the cancel on the next
            // chunk): unthrottled, the rest of the file would race to completion first.
            await("the sender sees the connection go") { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.resuming } }
            proxy.throttleUpstreamMs = 0

            val diag = { "sender=${a.coordinator.sessions.value.map { it.status to it.resuming }} receiver=${b.coordinator.sessions.value.map { it.status }}" }
            val asked = withTimeoutOrNull(15_000) { var v = b.coordinator.incomingOffer.value; while (v == null) { delay(25); v = b.coordinator.incomingOffer.value }; v }
                ?: error("not asked again: ${diag()}")
            assertEquals(1, asked.offer.files.size)
            b.coordinator.respondToOffer(false)
            val end = await("the sender gives up on a no", ms = 15_000) {
                a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.DECLINED }
            }
            assertFalse(end.resuming)
            delay(600)
            assertNull("and does not ask a third time", b.coordinator.incomingOffer.value)
        } finally { proxy.close(); a.close(); b.close() }
    }

    @Test fun aResumeToAPairedDeviceNeverFallsBackToSendingUnprovenToWhoeverTookItsAddress() = runBlocking {
        val a = Node("sa", ResumePolicy(windowMs = 60_000, pausesMs = listOf(1_200))); val b = Node("sb"); val x = Node("sx")
        val proxy = FlakyProxy(await("listener") { b.server.port.takeIf { it != 0 } })
        try {
            await("listeners") { if (x.server.port != 0 && b.server.port != 0) Unit else null }
            // pair a with b ("Add a device"): b's person agrees
            val answer = launch(Dispatchers.Default) {
                val prompt = await("pair prompt") { b.coordinator.hub.pairPrompt.value }
                b.coordinator.hub.respondToPair(prompt, true)
            }
            val bReal = Peer("sb-id", "sb", Endpoint("127.0.0.1", proxy.port))
            a.coordinator.hub.pairWith(Peer("sb-id", "sb", Endpoint("127.0.0.1", b.server.port))) { }!!
            answer.join()

            val radar = MutableStateFlow(mapOf(bReal.instanceId to bReal))
            a.coordinator.hub.radar = radar
            val payload = ByteArray(8 * 1024 * 1024 + 3) { (it * 17).toByte() }
            val src = File(home, "paired.bin").apply { writeBytes(payload) }
            proxy.cutOnConnection = 2                      // connection 1 is the "is this one of mine?" check, 2 the send
            proxy.cutFirstAfterBytes = 2L * 1024 * 1024
            a.coordinator.sendTo(bReal, listOf(src))
            await("it is waiting for the device", ms = 20_000) { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.resuming } }

            // the radar id (public: it is in the mDNS record) now answers at someone else's address
            radar.value = mapOf(bReal.instanceId to Peer("sb-id", "sb", Endpoint("127.0.0.1", x.server.port)))
            delay(3_500)                                   // longer than the retry pause: it has tried, and must have refused
            assertNull("the impostor was never even offered the files", x.coordinator.incomingOffer.value)
            assertTrue(x.coordinator.sessions.value.isEmpty())
            assertTrue("still waiting for the real device", a.coordinator.sessions.value.last().resuming)

            radar.value = mapOf(bReal.instanceId to bReal)  // the real one is back
            await("finished once the real device returned", ms = 30_000) { a.coordinator.sessions.value.lastOrNull()?.takeIf { it.status == SessionStatus.COMPLETED } }
            assertArrayEquals(payload, b.inbox.list().single().file.readBytes())
        } finally { proxy.close(); a.close(); b.close(); x.close() }
    }
}
