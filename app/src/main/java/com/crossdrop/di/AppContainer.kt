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
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.outgoingFileFor
import com.crossdrop.net.outgoingFolder
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
    val engine = TransferEngine(identity, deviceName::value, instanceId, os = com.crossdrop.core.Platforms.ANDROID)
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

    /** Asks for a store rating after a run of good transfers; see [com.crossdrop.core.review.ReviewPolicy]. */
    val review = com.crossdrop.review.ReviewPrompter(app).also { r -> coordinator.onOutcome = r::onTransfer }

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

    /** Apps another Android phone sent, waiting to be installed (staging left by a dead process is cleared). */
    val appInbox = com.crossdrop.apps.AppInbox(app, scope)
        .also { it.cleanUp(); coordinator.hub.appReceiver = it }

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

    // ------------------------------------------------------ shake to connect --

    private val _shakeEnabled = MutableStateFlow(prefs.getBoolean("shake_enabled", true))
    val shakeEnabled: StateFlow<Boolean> = _shakeEnabled

    fun setShakeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("shake_enabled", enabled) }
        _shakeEnabled.value = enabled
    }

    /** Has shaking ever found a device here? Until then the radar teaches it with an animated card. */
    private val _shakeLearned = MutableStateFlow(prefs.getBoolean("shake_learned", false))
    val shakeLearned: StateFlow<Boolean> = _shakeLearned

    fun markShakeLearned() {
        if (_shakeLearned.value) return
        prefs.edit { putBoolean("shake_learned", true) }
        _shakeLearned.value = true
    }

    /** When this phone was last shaken without a match yet: the radar says "now shake the other phone". */
    val shakeWaitingSince = MutableStateFlow(0L)

    /** This phone was shaken: tell the radar's devices, and match one that shook at the same time. */
    fun shakeNow() {
        val reachable = peers.value.values.filter { it.endpoint != null && it.vault == null }
        coordinator.hub.shake(reachable, listenerPort.value)
        if (coordinator.hub.shakeMatch.value == null) shakeWaitingSince.value = System.currentTimeMillis()
    }

    /** Port our transfer listener bound to — shown on the radar in debug builds. */
    val listenerPort = MutableStateFlow(0)

    /** Debug: place a peer on the radar by address, as if NSD had found it. */
    fun injectPeer(name: String?, host: String, port: Int) {
        // Port 0: this phone's own listener (a send to self, e.g. apps on a single emulator).
        val p = if (port == 0) listenerPort.value else port
        manualDiscovery?.add(name ?: "$host:$p", host, p)
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

    // ------------------------------------------------ auto-resume + send when it's back --

    /** Which of "My devices" are on the radar: proven with their pairing, not guessed from a name. */
    val presence: com.crossdrop.net.PairedPresence by lazy {
        com.crossdrop.net.PairedPresence(scope, engine, pairings, peers).also { coordinator.hub.presence = it }
    }

    /** Things picked for one of "My devices" while it was away; they go when it shows up (if CrossDrop is running). */
    val outbox = com.crossdrop.net.Outbox(java.io.File(app.filesDir, "outbox.json"), onRemoved = ::forgetQueued)

    /** A line about the last queued send, for a few seconds ("Sent to Laptop"). */
    private val _outboxNote = MutableStateFlow<String?>(null)
    val outboxNote: StateFlow<String?> = _outboxNote

    val outboxRunner: com.crossdrop.net.OutboxRunner by lazy {
        com.crossdrop.net.OutboxRunner(
            scope, coordinator.hub, presence, outbox, pairings,
            resolve = ::resolveQueued,
            keepAlive = { com.crossdrop.service.TransferService.start(app) },
            onResult = { r ->
                _outboxNote.value =
                    if (r.delivered) tr("Sent to {0}", r.entry.deviceName) else tr("Couldn't send to {0}", r.entry.deviceName)
                scope.launch { kotlinx.coroutines.delay(8_000); _outboxNote.value = null }
                com.crossdrop.service.OutboxNotifier.show(app, r)
            },
        )
    }

    /**
     * Queue [uris] (picked with the document picker) for [device]. Each keeps its read grant so it can still be read
     * when the device shows up; one whose grant can't be kept (the photo picker's) is copied into private storage.
     */
    fun queueFiles(device: com.crossdrop.core.pairing.PairedDevice, uris: List<android.net.Uri>) {
        val staging = java.io.File(app.filesDir, "outbox/" + System.nanoTime()).also { it.mkdirs() }
        val items = uris.withIndex().mapNotNull { (n, uri) ->
            val name = displayName(uri)
            if (runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess)
                com.crossdrop.net.OutboxItem("uri", uri.toString(), name)
            else runCatching {
                // each copy in its own folder: two picked files can have the same display name
                val copy = java.io.File(java.io.File(staging, "$n").also { it.mkdirs() }, com.crossdrop.core.protocol.FileNames.sanitize(name))
                (app.contentResolver.openInputStream(uri) ?: error("cannot open")).use { src -> copy.outputStream().use { src.copyTo(it) } }
                com.crossdrop.net.OutboxItem("copy", copy.path, name)
            }.getOrNull()
        }
        if (items.none { it.kind == "copy" }) staging.deleteRecursively()
        outbox.add(device.id, device.name, items)
    }

    private fun displayName(uri: android.net.Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

    private suspend fun resolveQueued(e: com.crossdrop.net.OutboxEntry): List<com.crossdrop.core.transfer.OutgoingFile> =
        e.items.flatMap { item ->
            when (item.kind) {
                "uri" -> listOfNotNull(app.outgoingFileFor(android.net.Uri.parse(item.ref)))
                "tree" -> app.outgoingFolder(android.net.Uri.parse(item.ref))
                "copy" -> java.io.File(item.ref).takeIf { it.isFile }?.let { listOf(com.crossdrop.net.TransferCoordinator.localFile(it)) }.orEmpty()
                else -> emptyList()
            }
        }

    /**
     * A folder send keeps its read grant past the screen (the send goes on in the background, and Retry reads again). Each
     * grant counts against Android's cap on persisted ones (128 before Android 11), and the cap drops the oldest, which could
     * be the picked inbox folder: so only the last few of the grants a folder send took *itself* are kept.
     */
    fun trackFolderGrant(tree: android.net.Uri) {
        val prefs = app.getSharedPreferences("send_folders", Context.MODE_PRIVATE)
        val kept = (prefs.getString("trees", "").orEmpty().split('\n').filter { it.isNotBlank() } - tree.toString() + tree.toString())
        kept.dropLast(MAX_FOLDER_GRANTS).forEach {
            runCatching { app.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(it), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit { putString("trees", kept.takeLast(MAX_FOLDER_GRANTS).joinToString("\n")) }
    }

    /** An entry left the queue: delete what was copied for it and let go of the grants it held. */
    private fun forgetQueued(e: com.crossdrop.net.OutboxEntry) {
        for (item in e.items) when (item.kind) {
            // its own folder goes, and the batch's folder once nothing is left in it (a big pick is several entries)
            "copy" -> runCatching {
                val own = java.io.File(item.ref).parentFile
                own?.deleteRecursively()
                own?.parentFile?.takeIf { it.parentFile?.name == "outbox" && it.list().isNullOrEmpty() }?.delete()
            }
            // A grant belongs to the document, not to the entry: the same file may be waiting for another device too.
            "uri" -> if (outbox.entries.value.none { x -> x.items.any { it.kind == "uri" && it.ref == item.ref } })
                runCatching { app.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(item.ref), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /**
     * "My devices": the computer this phone backs up to, and the devices added with "Add a device" (they send
     * each other files without asking). The hub gets the store, so another device's pairing request is shown to
     * the person; a phone still never keeps backups for others, because only a backup pairing can do that.
     */
    val pairings = com.crossdrop.core.pairing.PairingStore(java.io.File(app.filesDir, "pairings.json"))
        .also { coordinator.hub.pairings = it }

    /** Accept (or ask about) files from a paired device. */
    fun setAccept(id: String, on: Boolean) = pairings.setAccept(id, on)

    // ------------------------------------------------ clipboard sharing --

    /** This phone's clipboard: read only while CrossDrop has focus (Android's rule), written whenever a copy arrives. */
    val phoneClipboard = com.crossdrop.clipboard.PhoneClipboard(app, scope, pairings) { clipboard }

    /** Copy on one of "My devices", paste on another (text only; each device's switch in Settings → My devices). */
    val clipboard: com.crossdrop.net.ClipboardSync by lazy {
        com.crossdrop.net.ClipboardSync(scope, engine, pairings, presence.present, apply = phoneClipboard::write)
            .also { coordinator.hub.clipboardReceiver = it }
    }

    fun setClipboard(id: String, on: Boolean) = pairings.setClipboard(id, on)

    /** "Add a device": pair with [peer] as one of "My devices" (both screens show the code). */
    suspend fun pairDevice(peer: com.crossdrop.core.Peer, onCode: (String) -> Unit): com.crossdrop.ui.pairing.AddDeviceResult =
        if (coordinator.hub.pairWith(peer, onCode) != null) com.crossdrop.ui.pairing.AddDeviceResult.PAIRED
        else com.crossdrop.ui.pairing.AddDeviceResult.DECLINED

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
        // Interrupted sends and "send when it's back" look at the radar and at which paired devices are on it.
        coordinator.hub.radar = peers
        presence; outboxRunner; clipboard
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

    private companion object { const val MAX_FOLDER_GRANTS = 4 }
}
