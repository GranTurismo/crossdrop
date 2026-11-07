package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.PeerLiveness
import com.crossdrop.core.discovery.ServiceNames
import com.crossdrop.core.discovery.mergePeers
import com.crossdrop.core.discovery.tcpProbe
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class PeerDiscoveryTest {

    private fun nsd(id: String, name: String, probeable: Boolean = true) = Peer(
        instanceId = id, name = name, endpoint = Endpoint("10.0.0.2", 4000),
        sources = setOf(DiscoverySource.NSD), probeable = probeable,
    )

    private fun ble(id: String, name: String = UNNAMED_PEER) = Peer(
        instanceId = id, name = name, sources = setOf(DiscoverySource.BLE), rssi = -60,
    )

    // ------------------------------------------------------------------ merge --

    @Test
    fun `a BLE sighting never renames or unreaches an NSD peer`() {
        val both = listOf(mapOf("a" to nsd("a", "Pixel")), mapOf("a" to ble("a")))
        for (order in listOf(both, both.reversed())) {
            val p = mergePeers(order).getValue("a")
            assertEquals("Pixel", p.name)
            assertEquals(Endpoint("10.0.0.2", 4000), p.endpoint)
            assertEquals(setOf(DiscoverySource.NSD, DiscoverySource.BLE), p.sources)
            assertEquals(-60, p.rssi)
            assertTrue(p.probeable)
        }
    }

    // --------------------------------------------------------- service names --

    @Test
    fun `a peer advertised under two names survives losing one`() {
        val names = ServiceNames()
        names.add("crossdrop-ab", "ab")
        names.add("crossdrop-ab (2)", "ab")
        assertNull(names.remove("crossdrop-ab"))
        assertEquals("ab", names.remove("crossdrop-ab (2)"))
        assertNull(names.remove("never-seen"))
    }

    // --------------------------------------------------------------- liveness --

    private suspend fun awaitUntil(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(10) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out: $what") }
    }

    @Test
    fun `a stale record stays off the radar and a dead peer is dropped after repeated misses`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val alive = AtomicBoolean(false)
        val source = MutableStateFlow(mapOf("a" to nsd("a", "Mac")))
        val live = PeerLiveness(scope, source, intervalMs = 40, dropAfterMisses = 2, probe = { alive.get() })

        delay(300)
        assertTrue("unverified peer must not show", live.peers.value.isEmpty())

        alive.set(true)
        awaitUntil("peer appears once it answers") { "a" in live.peers.value }

        alive.set(false)
        awaitUntil("peer drops once it stops answering") { live.peers.value.isEmpty() }
        alive.set(true)
        awaitUntil("peer returns when it answers again") { "a" in live.peers.value }
        scope.cancel()
    }

    @Test
    fun `a single missed probe does not hide a peer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val results = ArrayDeque(listOf(true, false))      // then true forever
        val source = MutableStateFlow(mapOf("a" to nsd("a", "Mac")))
        val live = PeerLiveness(scope, source, intervalMs = 40, dropAfterMisses = 2,
            probe = { results.removeFirstOrNull() ?: true })
        awaitUntil("appears") { "a" in live.peers.value }
        repeat(20) { assertTrue("blinked", "a" in live.peers.value); delay(15) }
        scope.cancel()
    }

    @Test
    fun `peers that cannot be probed are trusted, BLE keeps an unreachable peer as proximity only`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = MutableStateFlow(mapOf(
            "legacy" to nsd("legacy", "Old build", probeable = false),
            "ble" to ble("ble"),
            "both" to mergePeers(listOf(mapOf("both" to nsd("both", "Phone")), mapOf("both" to ble("both", "x"))))
                .getValue("both"),
            "ghost" to nsd("ghost", "Ghost"),
        ))
        val live = PeerLiveness(scope, source, intervalMs = 40, dropAfterMisses = 1, probe = { false })
        delay(300)
        val p = live.peers.value
        assertEquals(setOf("legacy", "ble", "both"), p.keys)
        assertNull(p.getValue("both").endpoint)
        assertEquals(setOf(DiscoverySource.BLE), p.getValue("both").sources)
        assertEquals("Phone", p.getValue("both").name)
        scope.cancel()
    }

    @Test
    fun `tcpProbe tells a listening port from a closed one`() = runBlocking {
        val open = ServerSocket(0)
        val port = open.localPort
        assertTrue(tcpProbe(Endpoint("127.0.0.1", port)))
        open.close()
        assertFalse(tcpProbe(Endpoint("127.0.0.1", port)))
        assertFalse(tcpProbe(Endpoint("127.0.0.1", 0)))
    }

    // ------------------------------------------------- receiver stays quiet --

    @Test
    fun `a bare connect-and-close is not a failed transfer`() = runBlocking {
        val listener = ServerSocket(0)
        val engine = TransferEngine(SessionIdentity(), { "receiver" }, "ab12cd34")
        Socket("127.0.0.1", listener.localPort).close()
        val events = engine.receive(listener.accept()) { _, _ -> ReceiveDecision.Reject("n/a") }.toList()
        listener.close()
        assertTrue("probe surfaced as $events", events.isEmpty())
    }
}
