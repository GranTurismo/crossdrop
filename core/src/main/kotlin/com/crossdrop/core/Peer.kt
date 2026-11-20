package com.crossdrop.core

import com.crossdrop.core.i18n.tr

/** How a peer was sighted. Multiple sources can converge on the same [Peer.instanceId]. */
enum class DiscoverySource { NSD, BLE }

/**
 * Shown when a sighting carries no name of its own (BLE without a device name, partial mDNS resolve).
 * A getter, not a constant: the text is translated when it is read.
 */
val UNNAMED_PEER: String get() = tr("Nearby device")

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
    /**
     * True when [endpoint] tolerates a bare connect-and-close, so [com.crossdrop.core.discovery.PeerLiveness]
     * may use one to check the peer is still there. Older builds report a failed session for such a
     * connection, so this is opt-in via the peer's own advertisement.
     */
    val probeable: Boolean = false,
    /** What the device runs on ([Platforms]), when it said so (TXT `os` or its handshake); null for older builds. */
    val platform: String? = null,
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
