package com.crossdrop.discovery

import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.discovery.PeerDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dev-only discovery backend: peers added by hand appear on the radar like
 * real sightings. Bridges the mDNS gap on emulators, where multicast can't
 * cross the virtual Wi-Fi link but unicast TCP can.
 */
class ManualDiscovery : PeerDiscovery {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    fun add(name: String, host: String, port: Int) {
        val id = "manual-$host:$port"
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = name.ifBlank { "$host:$port" },
            endpoint = Endpoint(host, port),
            sources = setOf(DiscoverySource.NSD),
            lastSeenMs = System.currentTimeMillis(),
        ))
    }

    fun removeAll() {
        _peers.value = emptyMap()
    }

    override fun start() = Unit
    override fun stop() = removeAll()
}
