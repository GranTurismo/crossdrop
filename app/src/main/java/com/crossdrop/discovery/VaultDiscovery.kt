package com.crossdrop.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.crossdrop.core.CrossDrop
import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.VaultDescriptor
import com.crossdrop.core.discovery.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers `_crossdrop-vault._tcp.` services — vaults are their own radar
 * nodes (Orbital Beacons), keyed by the vault's ephemeral TXT `id`, which is
 * independent of the host device's transfer instanceId. Advertising the host
 * side is [VaultAdvertiser]'s job.
 */
class VaultDiscovery(
    private val nsd: NsdManager,
    private val wifi: WifiManager?,
    /** Our own vault's current id — filtered from sightings while hosting. */
    private val selfVaultId: () -> String?,
) : PeerDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    private val serviceNameToId = ConcurrentHashMap<String, String>()
    private val infoCallbacks = ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        runCatching {
            multicastLock = wifi?.createMulticastLock("crossdrop-vault")?.apply {
                setReferenceCounted(true); acquire()
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    scope.launch { resolve(info) }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    serviceNameToId.remove(info.serviceName)?.let {
                        _peers.value = _peers.value - it
                    }
                }
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "vault discovery started")
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {
                    Log.w(TAG, "vault discovery failed: $e")
                }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }
            discovery = listener
            nsd.discoverServices(
                CrossDrop.NSD_VAULT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(TAG, "vault NSD start failed", it) }
    }

    override fun stop() {
        started = false
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
        }
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        _peers.value = emptyMap()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) { resolveV34(info); return }
        runCatching {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = r.host?.hostAddress
                    onResolved(r, r.port, host?.let { listOf(it) } ?: emptyList())
                }
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
            })
        }
    }

    @androidx.annotation.RequiresApi(34)
    private fun resolveV34(info: NsdServiceInfo) {
        runCatching {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}
                override fun onServiceUpdated(r: NsdServiceInfo) {
                    onResolved(r, r.port,
                        r.hostAddresses.mapNotNull { it.hostAddress })
                }
                override fun onServiceLost() {
                    serviceNameToId.remove(info.serviceName)?.let {
                        _peers.value = _peers.value - it
                    }
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks.add(cb)
            nsd.registerServiceInfoCallback(info, { it.run() }, cb)
        }
    }

    private fun onResolved(r: NsdServiceInfo, port: Int, hosts: List<String>) {
        val attrs = r.attributes
        val id = attrs["id"]?.let { String(it, Charsets.UTF_8) }
            ?: r.serviceName.removePrefix("xdrop-vault-")
        if (id == selfVaultId()) return                       // our own beacon
        val host = hosts.firstOrNull { it.contains('.') } ?: hosts.firstOrNull() ?: return
        val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() } ?: "Vault"
        serviceNameToId[r.serviceName] = id
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = name,
            endpoint = Endpoint(host, port),
            sources = setOf(DiscoverySource.NSD),
            vault = VaultDescriptor(
                vaultName = name,
                writable = attrs["mode"]?.let { String(it, Charsets.UTF_8) } == "rw",
                needsPin = attrs["auth"]?.let { String(it, Charsets.UTF_8) } == "pin",
            ),
        ))
    }

    private companion object { const val TAG = "VaultDiscovery" }
}

/**
 * Registers/unregisters this device's vault service record. Owned by the
 * vault host manager — the TXT `id` is a fresh random token per run, so the
 * beacon is never correlatable across sessions.
 */
class VaultAdvertiser(private val nsd: NsdManager) {

    private var registration: NsdManager.RegistrationListener? = null

    fun start(vaultId: String, name: String, port: Int,
              writable: Boolean, pinProtected: Boolean) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceType = CrossDrop.NSD_VAULT_SERVICE_TYPE.trimEnd('.')
            serviceName = "xdrop-vault-$vaultId"
            this.port = port
            setAttribute("id", vaultId)
            setAttribute("name", name.toByteArray(Charsets.UTF_8)
                .take(48).toByteArray().toString(Charsets.UTF_8))
            setAttribute("mode", if (writable) "rw" else "ro")
            setAttribute("auth", if (pinProtected) "pin" else "open")
            setAttribute("ver", "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.i(TAG, "vault registered ${s.serviceName}:${s.port}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {
                Log.w(TAG, "vault registration failed: $e")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "vault register threw", it) }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private companion object { const val TAG = "VaultAdvertiser" }
}
