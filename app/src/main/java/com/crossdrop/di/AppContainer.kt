package com.crossdrop.di

import androidx.core.content.edit
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
import com.crossdrop.core.discovery.PeerLiveness
import com.crossdrop.core.discovery.mergePeers
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
import kotlinx.coroutines.launch
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
        prefs.edit { putString("device_name", clean) }
        _deviceName.value = clean
    }

    private fun loadDeviceName(): String =
        prefs.getString("device_name", null)
            ?: runCatching {
                Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /** Is there a Wi-Fi/Ethernet network (or our own hotspot) to find other devices on? Drives the radar's help. */
    val localNetwork = com.crossdrop.net.LocalNetworkMonitor(app)

    /** "Connect directly": a private Wi-Fi network this phone makes for a device that has none to share. */
    val directLink = com.crossdrop.net.DirectLink(app, scope, { listenerPort.value }, instanceId)

    val identity = SessionIdentity()
    val engine = TransferEngine(identity, deviceName::value, instanceId)
    val inbox = InboxStore(app).also { store ->
        // Received files used to sit in the app's private folder; move them to Downloads once.
        Thread({ runCatching { store.migrateToDownloads() } }, "inbox-migrate").apply { isDaemon = true }.start()
    }
    val history = HistoryStore(app)

    /**
     * The first-run welcome is shown once. Anyone who has already transferred something has seen
     * enough, so an update never greets them with it; Settings can replay it any time.
     */
    var welcomeSeen: Boolean
        get() = prefs.getBoolean("welcome_seen", false) || history.list().isNotEmpty()
        set(v) = prefs.edit { putBoolean("welcome_seen", v) }

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, app, engine, server, inbox, history)

    /** Devices known by address: ones that announced themselves to us, and the phone whose code we scanned. */
    val announced = com.crossdrop.core.discovery.AnnouncedPeers(scope).also { coordinator.hub.announced = it }

    /** "Scan a code": join another phone's private link (or any Wi-Fi code) and connect to it. */
    val wifiJoin = com.crossdrop.net.WifiJoin(app, scope, engine, { listenerPort.value }, announced)

    /** "Switch phones": this phone's library out, or another phone's library in. */
    val switchSender = com.crossdrop.switchphones.SwitchSender(scope, app, coordinator.hub, history)
    val switchImporter = com.crossdrop.switchphones.SwitchImporter(app, coordinator.hub, inbox, history)
        .also { coordinator.hub.switchReceiver = it }

    /** Contact cards sent on their own: straight into the address book. */
    val contactInbox = com.crossdrop.contacts.ContactInbox(app, coordinator.hub, inbox)
        .also { coordinator.hub.contactReceiver = it }

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
        add(announced)     // after mDNS: for a device seen both ways, its own advert speaks for it
        manualDiscovery?.let { add(it) }
    }

    // --------------------------------------------------- discovery toggles --

    private val _nsdEnabled = MutableStateFlow(prefs.getBoolean("nsd_enabled", true))
    val nsdEnabled: StateFlow<Boolean> = _nsdEnabled

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean("ble_enabled", true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled

    fun setNsdEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("nsd_enabled", enabled) }
        _nsdEnabled.value = enabled
        runCatching { if (enabled) nsdDiscovery.start() else nsdDiscovery.stop() }
        runCatching { if (enabled) vaultDiscovery.start() else vaultDiscovery.stop() }
    }

    fun setBleEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("ble_enabled", enabled) }
        _bleEnabled.value = enabled
        runCatching { if (enabled) bleDiscovery.start() else bleDiscovery.stop() }
    }

    /** Port our transfer listener bound to — shown on the radar in debug builds. */
    val listenerPort = MutableStateFlow(0)

    /** Debug: place a peer on the radar by address, as if NSD had found it. */
    fun injectPeer(name: String?, host: String, port: Int) {
        manualDiscovery?.add(name ?: "$host:$port", host, port)
    }

    /** Debug: a phone heard over Bluetooth only (emulators have no Bluetooth peers). */
    fun injectNearbyPeer(name: String) { manualDiscovery?.addNearby(name) }

    /** Debug: place a vault beacon on the radar (self-vaults are filtered). */
    fun injectVaultPeer(host: String, port: Int) {
        val resolved = if (port == 0) vaultHost.state.value?.port ?: 0 else port
        manualDiscovery?.addVault("Vault @$host:$resolved", host, resolved,
            writable = true, pin = false)
    }

    /**
     * All sightings merged by instanceId — a peer seen on BLE + NSD is one node — minus peers
     * whose listener no longer answers.
     */
    val peers: StateFlow<Map<String, Peer>> = run {
        val sighted = combine(discoveries.map { it.peers }) { mergePeers(it.asList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
        PeerLiveness(scope, sighted).peers
    }

    /**
     * "My devices": the computer this phone backs up to. Phones don't keep backups, so the hub isn't given
     * the store: a pairing request from someone else is declined without a prompt.
     */
    val pairings = com.crossdrop.core.pairing.PairingStore(java.io.File(app.filesDir, "pairings.json"))

    /** "Back up to a computer": photos and videos to the paired computer, and freeing up space after. */
    val backup = com.crossdrop.backup.BackupManager(
        scope, app, coordinator.hub, engine, pairings, peers, history,
        keepAlive = { com.crossdrop.service.TransferService.start(app) },
    )

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
        // Camera shots and contact exports from an earlier run are only temporary copies.
        runCatching { java.io.File(app.cacheDir, "camera").deleteRecursively() }
        runCatching { java.io.File(app.cacheDir, "switch").deleteRecursively() }
        runCatching { server.start() }   // listener comes up first so we know the port…
        if (_nsdEnabled.value) {
            runCatching { nsdDiscovery.start() }
            runCatching { vaultDiscovery.start() }
        }
        if (_bleEnabled.value) runCatching { bleDiscovery.start() }
        manualDiscovery?.let { runCatching { it.start() } }
        coordinator.start()
        // Offers that arrive while the app isn't on screen get a notification.
        scope.launch {
            coordinator.incomingOffer.collect { offer ->
                if (offer == null) com.crossdrop.service.OfferNotifier.clear(app)
                else if (!com.crossdrop.MainActivity.foreground) com.crossdrop.service.OfferNotifier.show(app, offer)
            }
        }
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
