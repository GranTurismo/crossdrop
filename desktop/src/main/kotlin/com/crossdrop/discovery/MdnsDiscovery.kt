package com.crossdrop.discovery

import com.crossdrop.core.CrossDrop
import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.VaultDescriptor
import com.crossdrop.core.discovery.PeerDiscovery
import com.crossdrop.platform.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

private fun String.asJmdnsType() = trimEnd('.') + ".local."

/**
 * Wire-compatible with the Android NSD side: registers `_crossdrop._tcp.` as
 * `crossdrop-<instanceId>` with TXT `id`/`name`, and resolves peers advertising
 * the same type. Android phones see this Mac on their radar and vice versa.
 */
class MdnsDiscovery(
    private val hub: MdnsHub,
    private val deviceName: () -> String,
    private val instanceId: String,
) : PeerDiscovery, MdnsClient {

    private val type = CrossDrop.NSD_SERVICE_TYPE.asJmdnsType()
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    private val nameToId = ConcurrentHashMap<String, String>()
    private var current: JmDNS? = null
    private var ours: ServiceInfo? = null
    @Volatile private var started = false

    /** Set once the transfer listener binds; (re)registers the service. */
    var advertisedPort: Int = 0
        set(value) {
            if (value == field || value == 0) return
            field = value
            advertise()
        }

    private val listener = object : ServiceListener {
        override fun serviceAdded(e: ServiceEvent) {
            if (e.name.contains(instanceId)) return
            e.dns.requestServiceInfo(e.type, e.name, true)
        }
        override fun serviceRemoved(e: ServiceEvent) {
            nameToId.remove(e.name)?.let { _peers.value = _peers.value - it }
        }
        override fun serviceResolved(e: ServiceEvent) = onResolved(e.info)
    }

    override fun start() {
        if (started) return
        started = true
        hub.register(this)
        hub.start()
    }

    override fun stop() {
        if (!started) return
        started = false
        hub.unregister(this)
        _peers.value = emptyMap()
    }

    override fun attach(dns: JmDNS) {
        current = dns
        dns.addServiceListener(type, listener)
        advertise()
    }

    override fun detach(dns: JmDNS) {
        runCatching { dns.removeServiceListener(type, listener) }
        ours?.let { runCatching { dns.unregisterService(it) } }
        ours = null
        current = null
        nameToId.clear()
        _peers.value = emptyMap()
    }

    /** (Re)publish our record — also called after a rename so TXT `name` stays current. */
    fun advertise() {
        val dns = current ?: return
        if (advertisedPort == 0) return
        ours?.let { runCatching { dns.unregisterService(it) } }
        val info = ServiceInfo.create(
            type, "crossdrop-$instanceId", advertisedPort, 0, 0,
            mapOf("id" to instanceId, "name" to deviceName().take(48)),
        )
        ours = info
        runCatching { dns.registerService(info) }
            .onSuccess { Log.i(TAG, "registered crossdrop-$instanceId:$advertisedPort") }
            .onFailure { Log.w(TAG, "register failed", it) }
    }

    private fun onResolved(info: ServiceInfo) {
        val id = info.getPropertyString("id") ?: info.name.removePrefix("crossdrop-")
        if (id == instanceId) return
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.hostAddresses.firstOrNull() ?: return
        val name = info.getPropertyString("name")?.takeIf { it.isNotBlank() } ?: info.name
        nameToId[info.name] = id
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = name,
            endpoint = Endpoint(host, info.port),
            sources = setOf(DiscoverySource.NSD),
        ))
    }

    private companion object { const val TAG = "MdnsDiscovery" }
}

/**
 * Discovers `_crossdrop-vault._tcp.` services — vaults are their own radar
 * nodes keyed by the vault's ephemeral TXT `id`, independent of the host's
 * transfer instanceId.
 */
class VaultDiscovery(
    private val hub: MdnsHub,
    /** Our own vault's current id — filtered from sightings while hosting. */
    private val selfVaultId: () -> String?,
) : PeerDiscovery, MdnsClient {

    private val type = CrossDrop.NSD_VAULT_SERVICE_TYPE.asJmdnsType()
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    private val nameToId = ConcurrentHashMap<String, String>()
    @Volatile private var started = false

    private val listener = object : ServiceListener {
        override fun serviceAdded(e: ServiceEvent) {
            e.dns.requestServiceInfo(e.type, e.name, true)
        }
        override fun serviceRemoved(e: ServiceEvent) {
            nameToId.remove(e.name)?.let { _peers.value = _peers.value - it }
        }
        override fun serviceResolved(e: ServiceEvent) = onResolved(e.info)
    }

    override fun start() {
        if (started) return
        started = true
        hub.register(this)
        hub.start()
    }

    override fun stop() {
        if (!started) return
        started = false
        hub.unregister(this)
        _peers.value = emptyMap()
    }

    override fun attach(dns: JmDNS) { dns.addServiceListener(type, listener) }

    override fun detach(dns: JmDNS) {
        runCatching { dns.removeServiceListener(type, listener) }
        nameToId.clear()
        _peers.value = emptyMap()
    }

    private fun onResolved(info: ServiceInfo) {
        val id = info.getPropertyString("id") ?: info.name.removePrefix("xdrop-vault-")
        if (id == selfVaultId()) return                       // our own beacon
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.hostAddresses.firstOrNull() ?: return
        val name = info.getPropertyString("name")?.takeIf { it.isNotBlank() } ?: "Vault"
        nameToId[info.name] = id
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = name,
            endpoint = Endpoint(host, info.port),
            sources = setOf(DiscoverySource.NSD),
            vault = VaultDescriptor(
                vaultName = name,
                writable = info.getPropertyString("mode") == "rw",
                needsPin = info.getPropertyString("auth") == "pin",
            ),
        ))
    }
}

/**
 * Publishes this Mac's drive as `xdrop-vault-<id>` with TXT `id/name/mode/auth/ver`
 * — the same record the Android host emits, so phones mount it from their radar.
 */
class VaultAdvertiser(private val hub: MdnsHub) : MdnsClient {
    private val type = CrossDrop.NSD_VAULT_SERVICE_TYPE.asJmdnsType()
    private var current: JmDNS? = null
    private var info: ServiceInfo? = null
    private var registered = false

    init { hub.register(this) }

    @Synchronized
    fun start(vaultId: String, name: String, port: Int, writable: Boolean, pinProtected: Boolean) {
        stop()
        info = ServiceInfo.create(
            type, "xdrop-vault-$vaultId", port, 0, 0,
            mapOf(
                "id" to vaultId,
                "name" to name.toByteArray(Charsets.UTF_8).take(48).toByteArray().toString(Charsets.UTF_8),
                "mode" to if (writable) "rw" else "ro",
                "auth" to if (pinProtected) "pin" else "open",
                "ver" to "1",
            ),
        )
        hub.start()
        current?.let { publish(it) }
    }

    @Synchronized
    fun stop() {
        val i = info ?: return
        if (registered) current?.let { runCatching { it.unregisterService(i) } }
        registered = false
        info = null
    }

    @Synchronized
    override fun attach(dns: JmDNS) { current = dns; publish(dns) }

    @Synchronized
    override fun detach(dns: JmDNS) {
        info?.let { runCatching { dns.unregisterService(it) } }
        registered = false
        current = null
    }

    private fun publish(dns: JmDNS) {
        val i = info ?: return
        runCatching { dns.registerService(i); registered = true }
            .onFailure { Log.w("VaultAdvertiser", "vault register failed", it) }
    }
}
