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
import com.crossdrop.core.UNNAMED_PEER
import com.crossdrop.core.discovery.PeerDiscovery
import com.crossdrop.core.discovery.ServiceNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS discovery via NsdManager: we register `_crossdrop._tcp.` on our
 * listener port and continuously resolve peers advertising the same type.
 * Works on any shared LAN — including between emulators on one host.
 */
class NsdDiscovery(
    private val nsd: NsdManager,
    private val wifi: WifiManager?,
    private val deviceName: () -> String,
    private val instanceId: String,
) : PeerDiscovery {

    /** Set once [com.crossdrop.net.TransferServer] binds; (re)registers the service. */
    var advertisedPort: Int = 0
        set(value) {
            if (value == field || value == 0) return
            field = value
            registerService()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    private val names = ServiceNames()
    private val infoCallbacks = ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
    private var registration: NsdManager.RegistrationListener? = null
    /** An earlier registration NSD has been asked to drop but hasn't confirmed yet. */
    private var unregistering: NsdManager.RegistrationListener? = null
    private var publishPending = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        runCatching {
            multicastLock = wifi?.createMulticastLock("crossdrop")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            startDiscovery()
            if (advertisedPort != 0) registerService()
        }.onFailure {
            // NEARBY_WIFI_DEVICES denied, no LAN, … — NSD layer stays dormant.
            Log.w(TAG, "NSD start failed", it)
        }
    }

    @Synchronized
    override fun stop() {
        started = false
        publishPending = false
        dropRegistration()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
        }
        discovery = null
        names.clear()
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        _peers.value = emptyMap()
    }

    // ------------------------------------------------------------ register --

    /**
     * (Re)publish our record. Registering under the same name while the previous registration is
     * still being torn down makes NSD rename ours to "crossdrop-<id> (2)" and leaves both
     * advertised — other devices then list this phone twice — so [publish] waits for the old one
     * to be confirmed gone. Covers a port change as well as stop() → start().
     */
    @Synchronized
    private fun registerService() {
        if (!started) return
        dropRegistration()
        publish()
    }

    @Synchronized
    private fun dropRegistration() {
        val old = registration ?: return
        registration = null
        unregistering = old
        runCatching { nsd.unregisterService(old) }.onFailure { unregistering = null }
    }

    @Synchronized
    private fun publish() {
        if (!started) return
        if (unregistering != null) {
            publishPending = true
            // NSD confirms an unregister, but never stay invisible if it somehow doesn't.
            scope.launch { delay(UNREGISTER_GRACE_MS); stillWaiting() }
            return
        }
        val info = NsdServiceInfo().apply {
            serviceType = CrossDrop.NSD_SERVICE_TYPE.trimEnd('.')
            serviceName = "crossdrop-$instanceId"
            port = advertisedPort
            setAttribute("id", instanceId)
            setAttribute("name", deviceName().take(48))
            setAttribute(CrossDrop.TXT_PROBE, "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "registered as ${serviceInfo.serviceName}:${serviceInfo.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = unregistered(this)
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = unregistered(this)
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService threw", it) }
    }

    @Synchronized
    private fun stillWaiting() {
        if (!publishPending) return
        unregistering = null
        publishPending = false
        publish()
    }

    @Synchronized
    private fun unregistered(listener: NsdManager.RegistrationListener) {
        if (unregistering !== listener) return
        unregistering = null
        if (publishPending) { publishPending = false; publish() }
    }

    // ------------------------------------------------------------ discover --

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.contains(instanceId)) return // ourselves
                scope.launch { resolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                names.remove(info.serviceName)?.let { id -> _peers.update { it - id } }
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started on $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching {
            nsd.discoverServices(CrossDrop.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(TAG, "discoverServices threw", it) }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            resolveV34(info)
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress
                    onResolved(resolved, resolved.port, host?.let { listOf(it) } ?: emptyList())
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    // Transient — NSD retries happen on next serviceFound wave.
                }
            })
        }
    }

    @androidx.annotation.RequiresApi(34)
    private fun resolveV34(info: NsdServiceInfo) {
        runCatching {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    val hosts = resolved.hostAddresses.mapNotNull { it.hostAddress }
                    onResolved(resolved, resolved.port, hosts)
                }
                override fun onServiceLost() {
                    names.remove(info.serviceName)?.let { id -> _peers.update { it - id } }
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks.add(cb)
            nsd.registerServiceInfoCallback(info, java.util.concurrent.Executor { it.run() }, cb)
        }
    }

    private fun onResolved(resolved: NsdServiceInfo, port: Int, hosts: List<String>) {
        val attrs = resolved.attributes
        val id = attrs["id"]?.let { String(it, Charsets.UTF_8) }
            ?: resolved.serviceName.removePrefix("crossdrop-")
        if (id == instanceId) return
        val host = hosts.firstOrNull { it.contains('.') } ?: hosts.firstOrNull() ?: return
        // onServiceUpdated fires before the TXT record is in on a fresh resolve — keep what an earlier,
        // complete update told us rather than showing the raw `crossdrop-<id>` service name.
        val prev = _peers.value[id]
        val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
            ?: prev?.name ?: UNNAMED_PEER
        val probeable = attrs[CrossDrop.TXT_PROBE]?.let { String(it, Charsets.UTF_8) == "1" }
            ?: prev?.probeable ?: false
        names.add(resolved.serviceName, id)
        _peers.update { it + (id to Peer(
            instanceId = id,
            name = name,
            endpoint = Endpoint(host, port),
            sources = setOf(DiscoverySource.NSD),
            probeable = probeable,
        )) }
    }

    private companion object {
        const val TAG = "NsdDiscovery"
        const val UNREGISTER_GRACE_MS = 2_000L
    }
}
