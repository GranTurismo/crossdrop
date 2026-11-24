package com.crossdrop.discovery

import android.util.Log
import com.crossdrop.core.Peer
import com.crossdrop.core.Platforms
import com.crossdrop.core.discovery.LinkMdns
import com.crossdrop.core.discovery.MdnsAdvert
import com.crossdrop.core.discovery.MulticastTransport
import com.crossdrop.net.HostedLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Keeps one [LinkMdns] running on every interface this phone hosts (its hotspot and the like), so devices that
 * joined it and this phone see each other. Hotspots come and go while the app runs, so the set of interfaces is
 * looked at every few seconds. Sightings from all of them are merged into [peers], which
 * [NsdDiscovery] folds into its own.
 */
class HostedMdns(
    private val links: HostedLinks,
    private val deviceName: () -> String,
    private val instanceId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    /** Our transfer listener's port, set once it binds; until then there is nothing to advertise (we still browse). */
    @Volatile var port: Int = 0

    private class Running(val mdns: LinkMdns) { @Volatile var peers: Map<String, Peer> = emptyMap() }

    private val running = HashMap<String, Running>()
    private val failedAt = HashMap<String, Long>()
    private var watch: Job? = null

    @Synchronized
    fun start() {
        if (watch != null) return
        watch = scope.launch {
            while (isActive) {
                runCatching { sync(links.find()) }.onFailure { Log.w(TAG, "hosted links check failed", it) }
                delay(POLL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        watch?.cancel(); watch = null
        running.values.forEach { runCatching { it.mdns.stop() } }
        running.clear(); failedAt.clear()
        _peers.value = emptyMap()
    }

    @Synchronized
    private fun sync(found: List<HostedLinks.Link>) {
        if (watch == null) return
        val wanted = found.associateBy { it.key }
        running.keys.filter { it !in wanted }.toList().forEach { key ->
            running.remove(key)?.let { runCatching { it.mdns.stop() } }
            Log.i(TAG, "left hosted link $key")
        }
        val now = System.currentTimeMillis()
        for ((key, link) in wanted) {
            if (key in running) continue
            if (now - (failedAt[key] ?: 0L) < RETRY_MS) continue
            val r = arrayOfNulls<Running>(1)
            val mdns = LinkMdns(
                transport = MulticastTransport(link.iface),
                local = link.address,
                onLink = onLink(link),
                advert = { port.takeIf { it > 0 }?.let { MdnsAdvert(instanceId, deviceName(), it, Platforms.ANDROID) } },
                onPeers = { map -> r[0]?.peers = map; merge() },
            )
            r[0] = Running(mdns)
            try {
                mdns.start()
                running[key] = r[0]!!
                failedAt.remove(key)
                Log.i(TAG, "mDNS on hosted link $key")
            } catch (e: Exception) {
                failedAt[key] = now
                Log.w(TAG, "can't run mDNS on hosted link $key", e)
                runCatching { mdns.stop() }
            }
        }
        // A link that went away takes its sightings with it.
        merge()
    }

    private fun merge() {
        val all = LinkedHashMap<String, Peer>()
        synchronized(this) { running.values.forEach { all.putAll(it.peers) } }
        if (all != _peers.value) _peers.value = all
    }

    private fun onLink(link: HostedLinks.Link): (InetAddress) -> Boolean {
        val mask = if (link.prefix == 0) 0 else -1 shl (32 - link.prefix)
        val net = toInt(link.address) and mask
        return { a -> a is Inet4Address && (toInt(a) and mask) == net }
    }

    private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    private companion object {
        const val TAG = "HostedMdns"
        const val POLL_MS = 4_000L
        const val RETRY_MS = 30_000L
    }
}
