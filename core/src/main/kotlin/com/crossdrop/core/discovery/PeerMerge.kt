package com.crossdrop.core.discovery

import com.crossdrop.core.Peer

/**
 * Folds every source's sightings into one node per [Peer.instanceId]. A sighting that can
 * reach the peer (NSD) speaks for its identity — a BLE sighting that merely knows the
 * peer exists must not replace the name the peer advertised with a placeholder.
 */
fun mergePeers(sources: Iterable<Map<String, Peer>>): Map<String, Peer> {
    val merged = linkedMapOf<String, Peer>()
    for (map in sources) for ((id, p) in map) {
        val have = merged[id]
        merged[id] = if (have == null) p else combine(have, p)
    }
    return merged
}

private fun combine(a: Peer, b: Peer): Peer {
    val (lead, other) = if (a.endpoint == null && b.endpoint != null) b to a else a to b
    return lead.copy(
        endpoint = lead.endpoint ?: other.endpoint,
        sources = lead.sources + other.sources,
        rssi = lead.rssi ?: other.rssi,
        lastSeenMs = maxOf(lead.lastSeenMs, other.lastSeenMs),
        vault = lead.vault ?: other.vault,
        probeable = lead.probeable || other.probeable,
    )
}

/**
 * Which advertised service names currently back each peer id. A peer can be advertised under
 * more than one name (mDNS renames a conflicting registration to "name (2)" and the old record
 * lingers), so losing one name must not drop a peer that is still advertised under another.
 */
class ServiceNames {
    private val idByName = HashMap<String, String>()

    @Synchronized fun add(serviceName: String, id: String) { idByName[serviceName] = id }

    /** Returns the id that just lost its last name, or null if it is still advertised (or unknown). */
    @Synchronized fun remove(serviceName: String): String? {
        val id = idByName.remove(serviceName) ?: return null
        return id.takeUnless { idByName.containsValue(it) }
    }

    @Synchronized fun clear() = idByName.clear()
}
