package com.crossdrop.core.discovery

import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * mDNS only retires a peer when the peer says goodbye. A laptop that sleeps, a phone that
 * drops off Wi-Fi or a crashed app leaves its record cached for an hour or more, so the
 * radar keeps showing a device nobody can send to. This asks each [Peer.probeable] peer's
 * listener directly and only passes on the ones that answer.
 *
 * A peer is shown once a probe succeeds (so a stale record never flashes onto the radar)
 * and hidden after [dropAfterMisses] consecutive failures (so one lost packet doesn't make
 * a device blink). Peers that can't be probed — BLE-only sightings, builds that don't
 * advertise [Peer.probeable] — are trusted as discovery reported them.
 */
class PeerLiveness(
    scope: CoroutineScope,
    source: StateFlow<Map<String, Peer>>,
    private val intervalMs: Long = 4_000,
    private val dropAfterMisses: Int = 2,
    private val probe: suspend (Endpoint) -> Boolean = ::tcpProbe,
) {
    /** Ids whose last probes say the listener is up. */
    private val reachable = MutableStateFlow<Set<String>>(emptySet())
    private val misses = HashMap<String, Int>()      // touched only by the sweep loop

    val peers: StateFlow<Map<String, Peer>> = combine(source, reachable) { all, up ->
        buildMap {
            for ((id, p) in all) {
                when {
                    !p.probeable || p.endpoint == null || id in up -> put(id, p)
                    // Unreachable over the LAN: keep it only if BLE still hears it nearby.
                    else -> p.withoutEndpoint()?.let { put(id, it) }
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            // Sweep on a timer, and immediately when a peer appears so it shows up without waiting a tick.
            val arrivals = source.map { it.keys }.distinctUntilChanged()
            val tick = flow { while (true) { emit(Unit); delay(intervalMs) } }
            combine(arrivals, tick) { _, _ -> }.collect { sweep(source.value) }
        }
    }

    private suspend fun sweep(all: Map<String, Peer>) {
        val targets = all.values.filter { it.probeable && it.endpoint != null }
        misses.keys.retainAll(targets.map { it.instanceId }.toSet())
        val results = coroutineScope {
            targets.map { p -> async { p.instanceId to probe(p.endpoint!!) } }.awaitAll()
        }
        val up = reachable.value.toMutableSet()
        for ((id, ok) in results) {
            if (ok) { misses.remove(id); up += id; continue }
            val n = (misses[id] ?: 0) + 1
            misses[id] = n
            if (id !in up || n >= dropAfterMisses) up -= id
        }
        up.retainAll(targets.map { it.instanceId }.toSet())
        reachable.value = up
    }

    private fun Peer.withoutEndpoint(): Peer? {
        val rest = sources - DiscoverySource.NSD
        return if (rest.isEmpty()) null else copy(endpoint = null, sources = rest, probeable = false)
    }
}

/** True if something accepts a TCP connection at [endpoint] within [timeoutMs]. */
suspend fun tcpProbe(endpoint: Endpoint, timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {   // bad port in a malformed record
        false
    }
}
