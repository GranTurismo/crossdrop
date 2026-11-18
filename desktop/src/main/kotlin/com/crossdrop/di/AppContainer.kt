package com.crossdrop.di

import com.crossdrop.core.CrossDrop
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.PeerDiscovery
import com.crossdrop.core.discovery.PeerLiveness
import com.crossdrop.core.discovery.mergePeers
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.discovery.MdnsDiscovery
import com.crossdrop.discovery.MdnsHub
import com.crossdrop.discovery.VaultAdvertiser
import com.crossdrop.discovery.VaultDiscovery
import com.crossdrop.net.QrShareManager
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.net.VaultExplorerManager
import com.crossdrop.net.VaultHostManager
import com.crossdrop.net.isTerminal
import com.crossdrop.platform.Platform
import com.crossdrop.platform.Prefs
import com.crossdrop.platform.SleepGuard
import com.crossdrop.storage.HistoryStore
import com.crossdrop.platform.AppDirs
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Hand-rolled service locator, mirroring the Android app's container: no DI
 * framework, everything lives for the process lifetime. Bonjour discovery is
 * JmDNS (one shared responder, [MdnsHub]); there is no BLE layer on desktop.
 */
class AppContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Rotating per-launch identity token shared across mDNS TXT records. */
    val instanceId: String = run {
        val bytes = ByteArray(CrossDrop.INSTANCE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val prefs = Prefs().also { p ->
        // Where the in-app language pick is stored ("auto" = follow the OS).
        com.crossdrop.core.i18n.I18n.setPersist { choice -> p.putString("language", choice ?: "auto") }
    }

    /** Is there a Wi-Fi/Ethernet address to find other devices on? Drives the radar's "can't see it?" help. */
    private val _hasNetwork = MutableStateFlow(com.crossdrop.net.LocalIp.wifiIpv4() != null)
    val hasNetwork: StateFlow<Boolean> = _hasNetwork

    init {
        scope.launch {
            while (true) {
                delay(4_000)
                _hasNetwork.value = runCatching { com.crossdrop.net.LocalIp.wifiIpv4() != null }.getOrDefault(true)
            }
        }
    }

    private val _deviceName = MutableStateFlow(loadDeviceName())
    val deviceName: StateFlow<String> = _deviceName

    fun setDeviceName(name: String) {
        val clean = name.trim().ifBlank { return }
        prefs.putString("device_name", clean)
        _deviceName.value = clean
        mdns.advertise()          // refresh the TXT record peers read our name from
    }

    private fun loadDeviceName(): String =
        prefs.getString("device_name")
            ?: runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: Platform.current.computerNoun

    val identity = SessionIdentity()
    val engine = TransferEngine(identity, deviceName::value, instanceId)
    val inbox = InboxStore(AppDirs.inbox, AppDirs.inboxState).also { store ->
        // Received files used to live in Application Support; bring them to Downloads once (cheap when there is nothing).
        kotlin.concurrent.thread(name = "inbox-migrate", isDaemon = true) {
            runCatching { store.migrateFrom(AppDirs.legacyInbox) }
        }
    }
    val history = HistoryStore()

    /** The first-run welcome is shown once; anyone who has already transferred something has seen enough. */
    var welcomeSeen: Boolean
        get() = prefs.getBoolean("welcome_seen", false) || history.list().isNotEmpty()
        set(v) = prefs.putBoolean("welcome_seen", v)

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, engine, server, inbox, history)

    /** A phone "switching" to this computer: its library lands in a Pictures folder. */
    val switchReceiver = com.crossdrop.switchphones.DesktopSwitchReceiver(coordinator.hub, history)
        .also { coordinator.hub.switchReceiver = it }

    /** Files or text waiting for the user to pick devices (drag-and-drop, Open With, Send to…). */
    val pending = MutableStateFlow<com.crossdrop.ui.radar.Pending?>(null)

    /** Readable files only; folders and anything else are skipped. Never sends by itself. */
    fun openFiles(files: List<java.io.File>) {
        // Network paths are skipped before touching them: even a stat would authenticate to that server.
        val ok = files.filter { !com.crossdrop.platform.isNetworkPath(it.path) && it.isFile && it.canRead() }.distinct()
        if (ok.isNotEmpty()) pending.value = com.crossdrop.ui.radar.Pending.Files(ok)
    }

    /** "Send to anyone" — zero-install receiver path via QR + embedded HTTP. */
    val qrShare = QrShareManager(scope, deviceName::value, history)

    private val hub = MdnsHub()

    /** Local Cloud Drive: host side (server + Bonjour advert) and client side. */
    val vaultHost = VaultHostManager(scope, identity, deviceName::value, VaultAdvertiser(hub))
    val vaultExplorer = VaultExplorerManager(
        scope, identity, deviceName::value, instanceId, inbox, history)

    private val mdns = MdnsDiscovery(hub, deviceName::value, instanceId)
    private val vaultDiscovery = VaultDiscovery(hub) { vaultHost.vaultId }

    private val discoveries: List<PeerDiscovery> = listOf(mdns, vaultDiscovery)

    // --------------------------------------------------- discovery toggle --

    private val _nsdEnabled = MutableStateFlow(prefs.getBoolean("nsd_enabled", true))
    val nsdEnabled: StateFlow<Boolean> = _nsdEnabled

    fun setNsdEnabled(enabled: Boolean) {
        prefs.putBoolean("nsd_enabled", enabled)
        _nsdEnabled.value = enabled
        runCatching { if (enabled) mdns.start() else mdns.stop() }
        runCatching { if (enabled) vaultDiscovery.start() else vaultDiscovery.stop() }
    }

    /** Port our transfer listener bound to. */
    val listenerPort = MutableStateFlow(0)

    /** All sightings merged by instanceId, minus peers whose listener no longer answers. */
    val peers: StateFlow<Map<String, Peer>> = run {
        val sighted = combine(discoveries.map { it.peers }) { mergePeers(it.asList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
        PeerLiveness(scope, sighted).peers
    }

    /** Re-bind Bonjour — e.g. after switching networks or approving the macOS Local Network / Windows Firewall prompt. */
    fun restartDiscovery() {
        if (_nsdEnabled.value) hub.restart()
    }

    fun start() {
        runCatching { server.start() }   // listener comes up first so we know the port…
        if (_nsdEnabled.value) {
            runCatching { mdns.start() }
            runCatching { vaultDiscovery.start() }
        }
        coordinator.start()

        // Don't let App Nap throttle the NIC mid-transfer or while serving the drive.
        combine(coordinator.hub.busy, qrShare.state, vaultHost.state, vaultExplorer.state) { busy, q, v, x ->
            busy || q != null || v != null ||
                x?.jobs?.any { !it.done && it.failed == null } == true
        }.onEach(SleepGuard::hold).launchIn(scope)

        // Windows: put CrossDrop in Explorer's "Send to" menu (once; per-user, no admin).
        com.crossdrop.platform.WindowsIntegration.ensureSendTo(prefs)
    }

    fun shutdown() {
        SleepGuard.hold(false)
        runCatching { qrShare.stop() }
        runCatching { vaultHost.stop() }
        runCatching { vaultExplorer.close() }
        runCatching { mdns.stop() }
        runCatching { vaultDiscovery.stop() }
        runCatching { hub.stop() }
        runCatching { server.stop() }
    }

    /** Called by [TransferServer] once the listener socket is bound. */
    fun onListenerBound(port: Int) {
        listenerPort.value = port
        mdns.advertisedPort = port
    }
}
