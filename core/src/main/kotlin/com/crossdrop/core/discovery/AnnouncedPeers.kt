package com.crossdrop.core.discovery

import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.UNNAMED_PEER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Devices known by address instead of by mDNS: one that announced itself to us (ANNOUNCE, e.g. a phone that
 * scanned our private link's code) and one we reached through a scanned code. They show on the radar like
 * any sighting, are probed for liveness ([Peer.probeable]), and drop out when nobody refreshes them for
 * [ttlMs] (a guest re-announces every 15 s while it is on the link).
 *
 * Anyone on a network could already advertise any name over mDNS, so an announcement is trusted the same
 * way: it only makes a device visible; sending still goes through the normal accept. Entries are capped
 * so a noisy device can't flood the radar. List this source after mDNS, so that for a device seen both
 * ways the advertised record speaks for it.
 */
class AnnouncedPeers(
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 60_000,
    private val max: Int = 32,
) : PeerDiscovery {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    init {
        scope?.launch {
            while (true) {
                delay(ttlMs / 4)
                prune()
            }
        }
    }

    /** Adds or refreshes a device. Ignores ids and names no CrossDrop build would send. */
    fun add(instanceId: String, name: String, endpoint: Endpoint) {
        if (!ID.matches(instanceId) || endpoint.port !in 1..65535) return
        val now = clock()
        _peers.update { cur ->
            val fresh = cur.filterValues { now - it.lastSeenMs <= ttlMs }.toMutableMap()
            fresh[instanceId] = Peer(
                instanceId = instanceId,
                name = name.trim().take(MAX_NAME).ifBlank { UNNAMED_PEER },
                endpoint = endpoint,
                sources = setOf(DiscoverySource.NSD),
                lastSeenMs = now,
                probeable = true,
            )
            while (fresh.size > max) fresh.remove(fresh.values.minBy { it.lastSeenMs }.instanceId)
            fresh
        }
    }

    fun remove(instanceId: String) = _peers.update { it - instanceId }

    fun prune() {
        val now = clock()
        _peers.update { cur -> cur.filterValues { now - it.lastSeenMs <= ttlMs }.takeIf { it.size != cur.size } ?: cur }
    }

    override fun start() = Unit
    override fun stop() { _peers.value = emptyMap() }

    private companion object {
        val ID = Regex("[0-9a-fA-F]{8,64}")
        const val MAX_NAME = 64
    }
}
