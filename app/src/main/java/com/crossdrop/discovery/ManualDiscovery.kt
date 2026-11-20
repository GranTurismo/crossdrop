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
            // Hand-added peers are the other emulator in Android tests.
            platform = com.crossdrop.core.Platforms.ANDROID,
        ))
    }

    /** Vault beacon variant — exercises the orbital-node render + mount sheet. */
    fun addVault(name: String, host: String, port: Int,
                 writable: Boolean, pin: Boolean) {
        val id = "manual-vault-$host:$port"
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = name.ifBlank { "$host:$port" },
            endpoint = Endpoint(host, port),
            sources = setOf(DiscoverySource.NSD),
            lastSeenMs = System.currentTimeMillis(),
            vault = com.crossdrop.core.VaultDescriptor(
                vaultName = name.ifBlank { "Vault" },
                writable = writable, needsPin = pin),
        ))
    }

    /** A phone heard over Bluetooth only (near, not on this Wi-Fi): no address, so it can't be sent to. */
    fun addNearby(name: String) {
        val id = "0000" + name.hashCode().toUInt().toString(16).padStart(8, '0')
        _peers.value = _peers.value + (id to Peer(
            instanceId = id, name = name, endpoint = null,
            sources = setOf(DiscoverySource.BLE), rssi = -62, lastSeenMs = System.currentTimeMillis(),
        ))
    }

    fun removeAll() {
        _peers.value = emptyMap()
    }

    override fun start() = Unit
    override fun stop() = removeAll()
}
