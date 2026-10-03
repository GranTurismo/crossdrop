package com.crossdrop.core.discovery

import com.crossdrop.core.Peer
import kotlinx.coroutines.flow.StateFlow

/**
 * Source of nearby peers (NSD, BLE, Wi-Fi Direct...). Implementations emit an
 * up-to-date map of sightings keyed by [Peer.instanceId]. The app merges all
 * sources; peers seen over several transports converge on one radar node.
 */
interface PeerDiscovery {
    val peers: StateFlow<Map<String, Peer>>
    fun start()
    fun stop()
}
