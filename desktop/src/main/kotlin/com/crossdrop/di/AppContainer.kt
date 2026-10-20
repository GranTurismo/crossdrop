package com.crossdrop.di

import com.crossdrop.core.CrossDrop
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.PeerDiscovery
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
import com.crossdrop.platform.Prefs
import com.crossdrop.platform.SleepGuard
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val prefs = Prefs()

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
            ?: "Mac"

    val identity = SessionIdentity()
    val engine = TransferEngine(identity, deviceName::value, instanceId)
    val inbox = InboxStore()
    val history = HistoryStore()

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, engine, server, inbox, history)

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

    /** All sightings merged by instanceId. */
    val peers: StateFlow<Map<String, Peer>> = run {
        combine(discoveries.map { it.peers }) { arrays ->
            val merged = linkedMapOf<String, Peer>()
            for (map in arrays) for ((id, p) in map) {
                val existing = merged[id]
                merged[id] = if (existing == null) p else p.copy(
                    endpoint = p.endpoint ?: existing.endpoint,
                    sources = p.sources + existing.sources,
                    lastSeenMs = maxOf(p.lastSeenMs, existing.lastSeenMs),
                )
            }
            merged
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Re-bind Bonjour — e.g. after switching networks or the macOS Local Network prompt. */
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
        combine(coordinator.session, qrShare.state, vaultHost.state, vaultExplorer.state) { s, q, v, x ->
            (s != null && !s.status.isTerminal) || q != null || v != null ||
                x?.jobs?.any { !it.done && it.failed == null } == true
        }.onEach(SleepGuard::hold).launchIn(scope)
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
