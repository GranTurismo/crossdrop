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
import com.crossdrop.net.toOutgoingFiles
import com.crossdrop.core.i18n.tr
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
    val engine = TransferEngine(identity, deviceName::value, instanceId, os = com.crossdrop.platform.Platform.wireName)
    /** The folder picked in Settings, when it is still there and writable (a removed USB drive → the default, for now). */
    private val chosenInbox: java.io.File? = prefs.getString("inbox_dir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        ?.takeIf { it.isDirectory && it.canWrite() }

    val inbox = InboxStore(chosenInbox ?: AppDirs.inbox, AppDirs.inboxState, custom = chosenInbox != null).also { store ->
        // Received files used to live in Application Support; bring them to Downloads once (cheap when there is nothing).
        kotlin.concurrent.thread(name = "inbox-migrate", isDaemon = true) {
            runCatching { store.migrateFrom(AppDirs.legacyInbox) }
        }
    }
    val history = HistoryStore()

    enum class InboxMove { OK, NOT_ALLOWED, BUSY }

    /**
     * Receive into [dir] from now on (kept across launches); null = back to `<Downloads>/CrossDrop`.
     * The files already in the inbox follow in the background ([onMoved] fires when they have).
     * Refused for folders that are not safe to fill and wipe (the home folder, system and app-state
     * folders: the same rule as a shared drive), and while a transfer is running, because its
     * half-written files belong to the old folder.
     */
    fun setInboxFolder(dir: java.io.File?, onMoved: () -> Unit = {}): InboxMove {
        if (dir != null && (!vaultHost.isShareable(dir) || !dir.canWrite())) return InboxMove.NOT_ALLOWED
        if (coordinator.sessions.value.any { !it.status.isTerminal }) return InboxMove.BUSY
        val old = inbox.switchTo(dir ?: AppDirs.inbox, custom = dir != null)
        prefs.putString("inbox_dir", dir?.canonicalPath ?: "")
        kotlin.concurrent.thread(name = "inbox-move", isDaemon = true) {
            runCatching { inbox.moveFrom(old) }
            onMoved()
        }
        return InboxMove.OK
    }

    /** The first-run welcome is shown once; anyone who has already transferred something has seen enough. */
    var welcomeSeen: Boolean
        get() = prefs.getBoolean("welcome_seen", false) || history.list().isNotEmpty()
        set(v) = prefs.putBoolean("welcome_seen", v)

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, engine, server, inbox, history)

    /** A phone "switching" to this computer: its library lands in a Pictures folder. */
    val switchReceiver = com.crossdrop.switchphones.DesktopSwitchReceiver(coordinator.hub, history)
        .also { coordinator.hub.switchReceiver = it }

    /** "My devices": phones paired with this computer. */
    val pairings = com.crossdrop.core.pairing.PairingStore(java.io.File(AppDirs.support, "pairings.json"))
        .also { coordinator.hub.pairings = it }

    /** Where phone backups go (each phone in its own folder); Settings can move it. */
    val backupRoot: java.io.File
        get() = prefs.getString("backup_root")?.let { java.io.File(it) }
            ?: java.io.File(System.getProperty("user.home"), "Pictures/CrossDrop Backup")

    fun setBackupRoot(dir: java.io.File) = prefs.putString("backup_root", dir.path)

    /** Paired phones back up here without asking. */
    val backups = com.crossdrop.backup.DesktopBackupReceiver(
        pairings, history, root = { backupRoot }, stateDir = AppDirs.support,
        folderNames = object : com.crossdrop.backup.DesktopBackupReceiver.FolderNames {
            override fun get(pairId: String) = prefs.getString("backup_folder_$pairId")
            override fun put(pairId: String, name: String) = prefs.putString("backup_folder_$pairId", name)
        },
    ).also { coordinator.hub.backupReceiver = it }

    /** Accept (or ask about) files from a paired device. */
    fun setAccept(id: String, on: Boolean) = pairings.setAccept(id, on)

    /** "Add a device": pair with [peer] as one of "My devices" (both screens show the code). */
    suspend fun pairDevice(peer: com.crossdrop.core.Peer, onCode: (String) -> Unit): com.crossdrop.ui.pairing.AddDeviceResult =
        if (coordinator.hub.pairWith(peer, onCode) != null) com.crossdrop.ui.pairing.AddDeviceResult.PAIRED
        else com.crossdrop.ui.pairing.AddDeviceResult.DECLINED

    /** Unpair a device: it can no longer back up here (its files stay where they are). */
    fun unpair(id: String) {
        pairings.remove(id)
        backups.forget(id)
    }

    /** Files or text waiting for the user to pick devices (drag-and-drop, Open With, Send to…). */
    val pending = MutableStateFlow<com.crossdrop.ui.radar.Pending?>(null)

    /** Readable files and folders (a folder goes with everything inside it); anything else is skipped. Never sends by itself. */
    fun openFiles(files: List<java.io.File>) {
        // Network paths are skipped before touching them: even a stat would authenticate to that server.
        val ok = files.filter { !com.crossdrop.platform.isNetworkPath(it.path) && (it.isFile || it.isDirectory) && it.canRead() }.distinct()
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

    /** Devices that announced themselves (a phone that joined the same link, a network that blocks mDNS). */
    val announced = com.crossdrop.core.discovery.AnnouncedPeers(scope).also { coordinator.hub.announced = it }

    // After mDNS: for a device seen both ways, its own advert speaks for it.
    private val discoveries: List<PeerDiscovery> = listOf(mdns, vaultDiscovery, announced)

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

    // ------------------------------------------------ auto-resume + send when it's back --

    /** Which of "My devices" are on the radar: proven with their pairing, not guessed from a name. */
    val presence = com.crossdrop.net.PairedPresence(scope, engine, pairings, peers)
        .also { coordinator.hub.presence = it; coordinator.hub.radar = peers }

    /** Things picked for one of "My devices" while it was away; they go when it shows up. */
    val outbox = com.crossdrop.net.Outbox(java.io.File(AppDirs.support, "outbox.json"))

    /** A line about the last queued send, for a few seconds ("Sent to Laptop"). */
    private val _outboxNote = MutableStateFlow<String?>(null)
    val outboxNote: StateFlow<String?> = _outboxNote

    val outboxRunner = com.crossdrop.net.OutboxRunner(
        scope, coordinator.hub, presence, outbox, pairings,
        resolve = { e -> e.items.map { java.io.File(it.ref) }.toOutgoingFiles() },
        onResult = { r ->
            _outboxNote.value =
                if (r.delivered) tr("Sent to {0}", r.entry.deviceName) else tr("Couldn't send to {0}", r.entry.deviceName)
            scope.launch { delay(8_000); _outboxNote.value = null }
        },
    )

    /** Queue [files] (and folders) for [device]; they go the next time it is here. */
    fun queueFor(device: com.crossdrop.core.pairing.PairedDevice, files: List<java.io.File>) {
        val ok = files.filter { (it.isFile || it.isDirectory) && it.canRead() && !com.crossdrop.platform.isNetworkPath(it.path) }
        outbox.add(device.id, device.name, ok.map { com.crossdrop.net.OutboxItem("path", it.absolutePath, it.name) })
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
