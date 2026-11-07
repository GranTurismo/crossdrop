package com.crossdrop.discovery

import com.crossdrop.net.LocalIp
import com.crossdrop.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.jmdns.JmDNS
import java.net.InetAddress

/** Something that needs a live Bonjour responder (browser, advertiser…). */
interface MdnsClient {
    /** The responder is up (or came back after a network change). */
    fun attach(dns: JmDNS)
    /** The responder is going away; drop registrations and sightings. */
    fun detach(dns: JmDNS)
}

/**
 * One shared JmDNS responder bound to the active LAN interface. Owning a single
 * instance keeps multicast traffic to one socket and gives every consumer the
 * same view of "are we on a network". Re-binds when the Wi-Fi address changes
 * (roaming, sleep/wake) — JmDNS itself stays pinned to the address it started on.
 */
class MdnsHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = java.util.concurrent.CopyOnWriteArrayList<MdnsClient>()

    @Volatile private var dns: JmDNS? = null
    @Volatile private var boundIp: String? = null
    private var watcher: Job? = null
    private val lock = Any()

    fun register(client: MdnsClient) {
        clients += client
        dns?.let { client.attach(it) }
    }

    fun unregister(client: MdnsClient) {
        clients -= client
        dns?.let { client.detach(it) }
    }

    /** Idempotent. Brings the responder up and starts watching for interface changes. */
    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive) {
                val ip = LocalIp.wifiIpv4()
                if (ip != boundIp || dns == null) rebind(ip)
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        watcher?.cancel(); watcher = null
        scope.launch { synchronized(lock) { teardown(); boundIp = null } }
    }

    /** Force a fresh responder — Settings "rescan". */
    fun restart() {
        scope.launch { rebind(LocalIp.wifiIpv4()) }
    }

    private fun rebind(ip: String?) = synchronized(lock) {
        teardown()
        boundIp = ip
        if (ip == null) return@synchronized      // offline — the watcher retries
        runCatching {
            val d = JmDNS.create(InetAddress.getByName(ip), "crossdrop-${System.nanoTime().toString(16)}")
            dns = d
            Log.i(TAG, "mDNS up on $ip")
            clients.forEach { c -> runCatching { c.attach(d) } }
        }.onFailure {
            Log.w(TAG, "mDNS start failed on $ip", it)
            dns = null
        }
    }

    private fun teardown() {
        val d = dns ?: return
        dns = null
        clients.forEach { c -> runCatching { c.detach(d) } }
        runCatching { d.close() }
    }

    private companion object {
        const val TAG = "MdnsHub"
        const val WATCH_INTERVAL_MS = 5_000L
    }
}
