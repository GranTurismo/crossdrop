package com.crossdrop.core

/** How a peer was sighted. Multiple sources can converge on the same [Peer.instanceId]. */
enum class DiscoverySource { NSD, BLE }

/** A reachable (or at least visible) CrossDrop instance on the local network. */
data class Endpoint(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

data class Peer(
    /** Rotating per-session identity token (hex). Dedupe key across transports. */
    val instanceId: String,
    val name: String,
    /** Where this peer accepts connections. Null for BLE-only sightings. */
    val endpoint: Endpoint? = null,
    val sources: Set<DiscoverySource> = emptySet(),
    /** BLE signal strength, when seen over BLE. Drives radar distance. */
    val rssi: Int? = null,
    val lastSeenMs: Long = System.currentTimeMillis(),
    /** Non-null when this node is a LAN vault beacon (not a transfer peer). */
    val vault: VaultDescriptor? = null,
) {
    /** Approximate distance in metres from RSSI, for radar placement. */
    val estimatedDistanceMeters: Double?
        get() = rssi?.let { Math.pow(10.0, (-59 - it) / (10.0 * 2.2)) }
}

/** TXT-advertised properties of a `_crossdrop-vault._tcp.` service. */
data class VaultDescriptor(
    val vaultName: String,     // user-set display label
    val writable: Boolean,     // "rw" vs "ro"
    val needsPin: Boolean,     // "pin" vs "open"
)
