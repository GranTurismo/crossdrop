package com.crossdrop.di

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import com.crossdrop.BuildConfig
import com.crossdrop.core.CrossDrop
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.PeerDiscovery
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.discovery.BleDiscovery
import com.crossdrop.discovery.ManualDiscovery
import com.crossdrop.discovery.NsdDiscovery
import com.crossdrop.net.QrShareManager
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom
import java.util.UUID

/**
 * Hand-rolled service locator — no DI framework, keeps the binary lean.
 * Everything here lives for the process lifetime.
 */
class AppContainer(private val app: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Rotating per-launch identity token shared across NSD TXT + BLE service data. */
    val instanceId: String = run {
        val bytes = ByteArray(CrossDrop.INSTANCE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val prefs = app.getSharedPreferences("crossdrop", Context.MODE_PRIVATE)

    private val _deviceName = MutableStateFlow(loadDeviceName())
    val deviceName: StateFlow<String> = _deviceName

    fun setDeviceName(name: String) {
        val clean = name.trim().ifBlank { return }
        prefs.edit().putString("device_name", clean).apply()
        _deviceName.value = clean
    }

    private fun loadDeviceName(): String =
        prefs.getString("device_name", null)
            ?: runCatching {
                Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    val identity = SessionIdentity()
    val engine = TransferEngine(identity, deviceName::value, instanceId)
    val inbox = InboxStore(app)
    val history = HistoryStore(app)

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, app, engine, server, inbox, history)

    /** "Send to anyone" — zero-install receiver path via QR + embedded HTTP. */
    val qrShare = QrShareManager(scope, app, deviceName::value, history)

    private val nsdManager = app.getSystemService<NsdManager>()!!
    private val wifiManager = app.applicationContext.getSystemService<WifiManager>()

    /** Local Cloud Drive: host side (server + NSD advert) and client side. */
    val vaultHost = com.crossdrop.net.VaultHostManager(
        scope, app, identity, deviceName::value, nsdManager)
    val vaultExplorer = com.crossdrop.net.VaultExplorerManager(
        scope, app, identity, deviceName::value, instanceId, inbox, history)

    /** Debug-only backend: hand-injected peers for emulator cross-testing. */
    private val manualDiscovery = if (BuildConfig.DEBUG) ManualDiscovery() else null

    private val nsdDiscovery = NsdDiscovery(
        nsdManager,
        wifiManager,
        deviceName::value,
        instanceId,
    )
    private val vaultDiscovery = com.crossdrop.discovery.VaultDiscovery(
        nsdManager, wifiManager, { vaultHost.vaultId })
    private val bleDiscovery = BleDiscovery(app, instanceId)

    private val discoveries: List<PeerDiscovery> = buildList {
        add(nsdDiscovery)
        add(vaultDiscovery)
        add(bleDiscovery)
        manualDiscovery?.let { add(it) }
    }

    // --------------------------------------------------- discovery toggles --

    private val _nsdEnabled = MutableStateFlow(prefs.getBoolean("nsd_enabled", true))
    val nsdEnabled: StateFlow<Boolean> = _nsdEnabled

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean("ble_enabled", true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled

    fun setNsdEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("nsd_enabled", enabled).apply()
        _nsdEnabled.value = enabled
        runCatching { if (enabled) nsdDiscovery.start() else nsdDiscovery.stop() }
        runCatching { if (enabled) vaultDiscovery.start() else vaultDiscovery.stop() }
    }

    fun setBleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ble_enabled", enabled).apply()
        _bleEnabled.value = enabled
        runCatching { if (enabled) bleDiscovery.start() else bleDiscovery.stop() }
    }

    /** Port our transfer listener bound to — shown on the radar in debug builds. */
    val listenerPort = MutableStateFlow(0)

    /** Debug: place a peer on the radar by address, as if NSD had found it. */
    fun injectPeer(name: String?, host: String, port: Int) {
        manualDiscovery?.add(name ?: "$host:$port", host, port)
    }

    /** Debug: place a vault beacon on the radar (self-vaults are filtered). */
    fun injectVaultPeer(host: String, port: Int) {
        val resolved = if (port == 0) vaultHost.state.value?.port ?: 0 else port
        manualDiscovery?.addVault("Vault @$host:$resolved", host, resolved,
            writable = true, pin = false)
    }

    /** All sightings merged by instanceId — a peer seen on BLE + NSD is one node. */
    val peers: StateFlow<Map<String, Peer>> = run {
        val flows = discoveries.map { it.peers }
        combine(flows) { arrays ->
            val merged = linkedMapOf<String, Peer>()
            for (map in arrays) for ((id, p) in map) {
                val existing = merged[id]
                merged[id] = if (existing == null) p else p.copy(
                    endpoint = p.endpoint ?: existing.endpoint,
                    sources = p.sources + existing.sources,
                    rssi = p.rssi ?: existing.rssi,
                    lastSeenMs = maxOf(p.lastSeenMs, existing.lastSeenMs),
                )
            }
            merged
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Context accessor for places (Root UI) that need it for intents/services. */
    fun contextForService(): Context = app

    /** Restart discovery — e.g. after permissions are granted post-launch. */
    fun restartDiscovery() {
        if (_nsdEnabled.value) {
            runCatching { nsdDiscovery.stop(); nsdDiscovery.start() }
            runCatching { vaultDiscovery.stop(); vaultDiscovery.start() }
        }
        if (_bleEnabled.value) runCatching { bleDiscovery.stop(); bleDiscovery.start() }
    }

    fun start() {
        runCatching { server.start() }   // listener comes up first so we know the port…
        if (_nsdEnabled.value) {
            runCatching { nsdDiscovery.start() }
            runCatching { vaultDiscovery.start() }
        }
        if (_bleEnabled.value) runCatching { bleDiscovery.start() }
        manualDiscovery?.let { runCatching { it.start() } }
        coordinator.start()
    }

    /** Debug: mount a vault by address (emulator path where mDNS can't cross).
     *  Port 0 → our own hosted vault's live port (loopback self-mount). */
    fun mountVaultAt(host: String, port: Int, pin: String? = null) {
        val resolved = if (port == 0) vaultHost.state.value?.port ?: 0 else port
        vaultExplorer.mount(Peer(
            instanceId = "debug-vault",
            name = "vault@$host:$resolved",
            endpoint = com.crossdrop.core.Endpoint(host, resolved),
            sources = setOf(com.crossdrop.core.DiscoverySource.NSD),
            vault = com.crossdrop.core.VaultDescriptor(
                vaultName = "vault@$host:$resolved", writable = true, needsPin = pin != null),
        ), pin)
    }

    /** Called by [TransferServer] once the listener socket is bound. */
    fun onListenerBound(port: Int) {
        listenerPort.value = port
        nsdDiscovery.advertisedPort = port
    }
}
