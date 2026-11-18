package com.crossdrop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.di.AppContainer
import com.crossdrop.net.isTerminal
import kotlinx.coroutines.launch
import com.crossdrop.service.TransferService
import com.crossdrop.ui.inbox.InboxScreen
import com.crossdrop.ui.radar.RadarScreen
import com.crossdrop.ui.settings.SettingsScreen
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.InkNav
import com.crossdrop.ui.theme.NavIdle
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.transfer.TransferScreen
import com.crossdrop.ui.transfers.TransfersScreen

enum class Tab {
    RADAR, TRANSFERS, INBOX, SETTINGS;

    /** Read at call time so a language change shows up without restarting. */
    val label: String
        get() = when (this) {
            RADAR -> tr("Radar")
            TRANSFERS -> tr("Transfers")
            INBOX -> tr("Inbox")
            SETTINGS -> tr("Settings")
        }
}

/**
 * App shell: four tabs behind a floating glass pill nav. Full-screen
 * overlays (live transfer, QR share, wireless drive, mounted vault) slide up
 * over everything; an inbound offer takes the whole screen on top. The live
 * transfer can be minimised to the Transfers tab and reopened from its card.
 */
@Composable
fun Root(container: AppContainer) {
    val peers by container.peers.collectAsState()
    val groups by container.coordinator.groups.collectAsState()
    val focused by container.coordinator.focusedGroup.collectAsState()
    val allSessions by container.coordinator.sessions.collectAsState()
    val live by container.coordinator.live.collectAsState()
    val switchOut by container.switchSender.state.collectAsState()
    val switchIn by container.switchImporter.state.collectAsState()
    val contactDrop by container.contactInbox.state.collectAsState()
    val shareState by container.qrShare.state.collectAsState()
    val vaultUi by container.vaultExplorer.state.collectAsState()
    val vaultHost by container.vaultHost.state.collectAsState()
    val incomingOffer by container.coordinator.incomingOffer.collectAsState()
    val deviceName by container.deviceName.collectAsState()
    val nsdEnabled by container.nsdEnabled.collectAsState()
    val bleEnabled by container.bleEnabled.collectAsState()
    val listenerPort by container.listenerPort.collectAsState()
    val hasNetwork by container.localNetwork.hasNetwork.collectAsState()
    val directLink by container.directLink.state.collectAsState()
    val directLinkOn = directLink is com.crossdrop.core.connect.DirectLinkState.On

    var tabName by rememberSaveable { mutableStateOf(Tab.RADAR.name) }
    // First run: a skippable welcome. The radar (and its permission prompts) waits until it is done.
    var welcome by rememberSaveable { mutableStateOf(!container.welcomeSeen) }
    val finishWelcome = { container.welcomeSeen = true; welcome = false }
    // "Can't see the other device?" help: the one-tap private link and the plain how-to.
    var connectOpen by rememberSaveable { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(directLink) { container.localNetwork.refresh() }
    val tab = Tab.valueOf(tabName)
    var inboxCount by remember { mutableIntStateOf(container.inbox.list().size) }
    var historyTick by remember { mutableIntStateOf(0) }
    var inboxTick by remember { mutableIntStateOf(0) }
    var driveOpen by remember { mutableStateOf(false) }
    var transferHidden by remember { mutableStateOf(false) }
    var liveHidden by remember { mutableStateOf(false) }
    var cameraTargets by remember { mutableStateOf<List<com.crossdrop.core.Peer>?>(null) }
    var cameraShots by remember { mutableStateOf(emptyList<com.crossdrop.ui.camera.CameraShot>()) }
    var switchTarget by remember { mutableStateOf<com.crossdrop.core.Peer?>(null) }
    var contactTargets by remember { mutableStateOf<List<com.crossdrop.core.Peer>?>(null) }
    // A contact offer accepted while CrossDrop can't write contacts yet: answered after the permission dialog.
    var contactOfferWaiting by remember { mutableStateOf<com.crossdrop.net.IncomingOffer?>(null) }
    val contactsPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        contactOfferWaiting?.let { container.coordinator.respondToOffer(it, true) }
        contactOfferWaiting = null
        if (container.contactInbox.canWrite()) container.scope.launch { container.contactInbox.addKept() }
    }
    val contactsPerms = arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
    var switchInHidden by remember { mutableStateOf(false) }
    val doneCount = allSessions.count { it.status.isTerminal }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Files may land while we're anywhere in the app — keep badges fresh.
    LaunchedEffect(doneCount, switchIn?.phase) { inboxCount = container.inbox.list().size }
    // A newly focused group always opens the transfer view.
    LaunchedEffect(focused?.id) { if (focused != null) transferHidden = false }
    // A minimised group that finishes is cleared — history already has it.
    LaunchedEffect(transferHidden, focused?.isTerminal) {
        val f = focused
        if (transferHidden && f != null && f.isTerminal) container.coordinator.dismissGroup(f.id)
    }
    // A new live feed or switch move shows itself.
    LaunchedEffect(live?.grantId) { liveHidden = false }
    LaunchedEffect(switchIn?.peerName) { switchInHidden = false }

    BackHandler(enabled = tab != Tab.RADAR) { tabName = Tab.RADAR.name }

    val navInset = 66.dp + 18.dp + 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalNavInset provides navInset) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            when (tab) {
                Tab.RADAR -> if (!welcome) RadarScreen(
                    selfName = deviceName,
                    peers = peers.values.sortedBy { it.lastSeenMs },
                    onSendTo = { targets, uris ->
                        TransferService.start(container.contextForService())
                        container.coordinator.sendTo(targets, uris)
                    },
                    onCamera = { targets -> cameraShots = emptyList(); cameraTargets = targets },
                    onSendText = { targets, text ->
                        TransferService.start(container.contextForService())
                        container.coordinator.sendText(targets, text)
                    },
                    onContacts = { targets -> contactTargets = targets },
                    onSwitchPhones = { peer -> container.switchSender.close(); switchTarget = peer },
                    onShareToAnyone = { uris ->
                        TransferService.start(container.contextForService())
                        container.qrShare.start(uris)
                    },
                    onMountVault = { peer -> container.vaultExplorer.mount(peer) },
                    onRename = container::setDeviceName,
                    onPermissionsGranted = container::restartDiscovery,
                    selfPort = listenerPort,
                    nsdEnabled = nsdEnabled,
                    hasNetwork = hasNetwork,
                    directLinkOn = directLinkOn,
                    onConnectDirectly = { connectOpen = true },
                    onShowConnectGuide = { guideOpen = true },
                    onOpenDirectLink = { connectOpen = true },
                )
                Tab.TRANSFERS -> TransfersScreen(
                    groups = groups,
                    history = container.history,
                    onClearHistory = { container.history.clear(); historyTick++ },
                    refreshSignal = listOf(doneCount, historyTick, switchIn?.phase, switchOut?.phase),
                    onOpenActive = { id -> container.coordinator.focus(id); transferHidden = false },
                )
                Tab.INBOX -> InboxScreen(
                    inbox = container.inbox,
                    onChanged = { inboxCount = container.inbox.list().size },
                    refreshSignal = listOf(doneCount, inboxTick, switchIn?.phase),
                )
                Tab.SETTINGS -> SettingsScreen(
                    deviceName = deviceName,
                    instanceId = container.instanceId,
                    port = listenerPort,
                    nsdEnabled = nsdEnabled,
                    bleEnabled = bleEnabled,
                    onRename = container::setDeviceName,
                    onSetNsd = container::setNsdEnabled,
                    onSetBle = container::setBleEnabled,
                    inboxStats = { container.inbox.list().let { it.size to it.sumOf { f -> f.size } } },
                    onWipeInbox = {
                        container.inbox.shredAll()
                        inboxCount = 0
                        inboxTick++
                    },
                    onClearHistory = { container.history.clear(); historyTick++ },
                    onShowWelcome = { welcome = true },
                    vaultState = vaultHost,
                    onOpenDrive = { driveOpen = true },
                    onSelfTest = if (com.crossdrop.BuildConfig.DEBUG)
                        ({ container.coordinator.selfTransfer() }) else null,
                    onAddPeer = if (com.crossdrop.BuildConfig.DEBUG)
                        ({ h, p -> container.injectPeer(null, h, p) }) else null,
                    refreshSignal = listOf(doneCount, inboxTick, historyTick),
                )
            }

            // Scrolled content fades out under the status bar instead of colliding with the clock.
            Box(
                Modifier.fillMaxWidth()
                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
                    .background(Brush.verticalGradient(listOf(Ink, Ink.copy(alpha = 0.85f), Color.Transparent))),
            )

            FloatingNav(
                tab = tab,
                onSelect = { tabName = it.name },
                inboxCount = inboxCount,
                transferLive = groups.any { !it.isTerminal } && (transferHidden || focused == null),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            )

            // ---- first-run welcome (skippable; Settings can replay it)
            Overlay(true.takeIf { welcome }, fade = true) {
                BackHandler { finishWelcome() }
                com.crossdrop.ui.welcome.WelcomeScreen(
                    onFinish = finishWelcome,
                    permissionNote = tr("Android will ask to find nearby devices and to show notifications. Anything else, like camera or contacts, is asked only when you use it."),
                )
            }

            // ---- connect two devices that share no Wi-Fi
            Overlay(true.takeIf { guideOpen }) {
                BackHandler { guideOpen = false }
                com.crossdrop.ui.connect.ConnectGuideScreen(
                    onClose = { guideOpen = false },
                    onConnectDirectly = { guideOpen = false; connectOpen = true },
                )
            }
            Overlay(true.takeIf { connectOpen }) {
                BackHandler { connectOpen = false }
                com.crossdrop.ui.connect.ConnectDirectScreen(
                    link = container.directLink,
                    nearbyNames = peers.values.filter { it.vault == null }.map { it.name },
                    onClose = { connectOpen = false },
                    onGuide = { connectOpen = false; guideOpen = true },
                )
            }

            // ---- live transfer (one device, or a send to several)
            Overlay(focused?.takeIf { !transferHidden }) { g ->
                BackHandler { if (g.isTerminal) container.coordinator.dismissGroup(g.id) else transferHidden = true }
                if (g.multi) {
                    com.crossdrop.ui.transfer.GroupTransferScreen(
                        group = g,
                        onCancelAll = { container.coordinator.cancelGroup(g.id) },
                        onRetry = { container.coordinator.retryFailed(g.id) },
                        onClose = { container.coordinator.dismissGroup(g.id) },
                        onHide = { transferHidden = true },
                    )
                } else {
                    TransferScreen(
                        session = g.primary,
                        selfName = deviceName,
                        onCancel = { container.coordinator.cancelGroup(g.id) },
                        onClose = { container.coordinator.dismissGroup(g.id) },
                        onHide = { transferHidden = true },
                        onOpenInbox = {
                            container.coordinator.dismissGroup(g.id)
                            tabName = Tab.INBOX.name
                        },
                    )
                }
            }

            // ---- camera: every shot goes live to the chosen devices
            Overlay(cameraTargets) { targets ->
                BackHandler { cameraTargets = null }
                com.crossdrop.ui.camera.CameraScreen(
                    targets = targets.map { it.name },
                    shots = cameraShots,
                    sessions = allSessions,
                    onShot = { file, video ->
                        TransferService.start(container.contextForService())
                        val gid = container.coordinator.sendFiles(targets, listOf(file), live = true)
                        cameraShots = (cameraShots + com.crossdrop.ui.camera.CameraShot(file, video, gid)).takeLast(30)
                        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.crossdrop.media.MediaSaver.saveToGallery(
                                context, file, file.name, if (video) "video/mp4" else "image/jpeg",
                                System.currentTimeMillis(), "DCIM/CrossDrop",
                            )
                        }
                    },
                    onClose = { cameraTargets = null; container.coordinator.hub.pruneQuiet(com.crossdrop.core.protocol.OfferKind.CAMERA) },
                )
            }

            // ---- someone else's camera, live on this screen
            Overlay(live?.takeIf { !liveHidden }) { feed ->
                BackHandler { liveHidden = true }
                com.crossdrop.ui.live.LiveViewerScreen(
                    feed = feed,
                    loadImage = com.crossdrop.media.MediaSaver::loadImage,
                    onOpen = { shot -> com.crossdrop.ui.inbox.openFile(context, shot.file) },
                    onStop = container.coordinator::stopLive,
                    onHide = { liveHidden = true },
                )
            }

            // ---- contact cards: pick people, send them
            Overlay(contactTargets) { targets ->
                BackHandler { contactTargets = null }
                com.crossdrop.ui.contacts.ContactPickerScreen(
                    target = if (targets.size == 1) targets.first().name else trPlural(targets.size, "1 device", "{n} devices"),
                    onSend = { ids ->
                        contactTargets = null
                        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val cards = runCatching {
                                com.crossdrop.switchphones.AndroidContacts.readAll(context, withPhotos = true, ids = ids)
                            }.getOrDefault(emptyList())
                            if (cards.isNotEmpty()) container.coordinator.sendContacts(targets, cards)
                        }
                    },
                    onClose = { contactTargets = null },
                )
            }

            // ---- switch phones: this phone → another device
            Overlay(switchTarget) { peer ->
                BackHandler { if (switchOut?.finished != false) { container.switchSender.close(); switchTarget = null } }
                com.crossdrop.ui.switchphones.SwitchSendScreen(
                    peerName = peer.name,
                    sender = container.switchSender,
                    state = switchOut,
                    onStart = { p, v, c ->
                        TransferService.start(container.contextForService())
                        container.switchSender.start(peer, p, v, c)
                    },
                    onClose = { container.switchSender.close(); switchTarget = null },
                )
            }

            // ---- switch phones: another phone → this device
            Overlay(switchIn?.takeIf { !switchInHidden }) { st ->
                BackHandler { switchInHidden = true }
                com.crossdrop.ui.switchphones.SwitchReceiveScreen(
                    importer = container.switchImporter,
                    state = st,
                    receiving = allSessions.lastOrNull {
                        it.kind == com.crossdrop.core.protocol.OfferKind.SWITCH && it.direction == com.crossdrop.net.Direction.RECEIVING && !it.status.isTerminal
                    },
                    onClose = { switchInHidden = true },
                )
            }

            // ---- "send to anyone" (QR + embedded HTTP share)
            Overlay(shareState) { s ->
                BackHandler { container.qrShare.stop() }
                com.crossdrop.ui.qrshare.QrShareScreen(state = s, onStop = container.qrShare::stop)
            }

            // ---- wireless drive (this phone as a network drive)
            Overlay(if (driveOpen) Unit else null) {
                com.crossdrop.ui.drive.DriveHostScreen(
                    state = vaultHost,
                    onStart = { rw, pin, uri ->
                        container.vaultHost.start(rw, pin, treeUri = uri).also { ok ->
                            if (ok) TransferService.start(container.contextForService())
                        }
                    },
                    onStop = container.vaultHost::stop,
                    onClose = { driveOpen = false },
                )
            }

            // ---- mounted LAN drive
            Overlay(vaultUi) { v ->
                com.crossdrop.ui.vault.VaultExplorerScreen(
                    state = v,
                    onBrowse = container.vaultExplorer::browse,
                    onUp = container.vaultExplorer::up,
                    onToggleSelect = container.vaultExplorer::toggleSelect,
                    onDownload = { paths ->
                        container.vaultExplorer.download(paths)
                        inboxCount = container.inbox.list().size + paths.size
                    },
                    onUpload = { uris ->
                        TransferService.start(container.contextForService())
                        container.vaultExplorer.upload(uris)
                    },
                    onStream = { path -> container.vaultExplorer.streamUrl(path) },
                    isMedia = { e -> container.vaultExplorer.isMediaPath(e.name, e.mime) },
                    onClose = container.vaultExplorer::close,
                    onUnlock = container.vaultExplorer::unlock,
                )
            }

            // ---- contact cards that just arrived
            androidx.compose.animation.AnimatedVisibility(
                visible = contactDrop != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = navInset - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                var last by remember { mutableStateOf(contactDrop) }
                contactDrop?.let { last = it }
                last?.let { d ->
                    com.crossdrop.ui.contacts.ContactDropCard(
                        drop = d,
                        onView = { uri ->
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            container.contactInbox.dismiss(d.id)
                        },
                        onAllow = {
                            val prefs = context.getSharedPreferences("contacts_permission", 0)
                            val activity = context as? android.app.Activity
                            val refusedForGood = prefs.getBoolean("asked", false) && activity != null &&
                                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.WRITE_CONTACTS)
                            if (refusedForGood && !container.contactInbox.canWrite()) {
                                // Android won't ask again: the switch is in the app's settings now.
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", context.packageName, null),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            } else {
                                prefs.edit().putBoolean("asked", true).apply()
                                contactsPermission.launch(contactsPerms)
                            }
                        },
                        onDone = { container.contactInbox.dismiss(d.id); inboxCount = container.inbox.list().size },
                    )
                }
            }

            // ---- inbound offer — always on top; Back declines
            Overlay(incomingOffer, fade = true) { offer ->
                BackHandler {
                    container.coordinator.respondToOffer(offer, false)
                }
                IncomingOfferSheet(offer) { accept ->
                    val cards = offer.kind == com.crossdrop.core.protocol.OfferKind.CONTACT
                    // Text and contact cards arrive in a moment; everything else may take a while.
                    if (accept && offer.text == null && !cards) TransferService.start(container.contextForService())
                    if (accept && cards && !container.contactInbox.canWrite()) {
                        // Ask now, while they're looking at it; the answer goes out after the dialog.
                        contactOfferWaiting = offer
                        context.getSharedPreferences("contacts_permission", 0).edit().putBoolean("asked", true).apply()
                        contactsPermission.launch(contactsPerms)
                    } else {
                        container.coordinator.respondToOffer(offer, accept)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen layer that slides up while [value] is non-null and keeps the
 * last value through its exit animation.
 */
@Composable
private fun <T : Any> Overlay(value: T?, fade: Boolean = false, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    AnimatedVisibility(
        visible = value != null,
        enter = if (fade) fadeIn() else slideInVertically { it } + fadeIn(),
        exit = if (fade) fadeOut() else slideOutVertically { it } + fadeOut(),
    ) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            last?.let { content(it) }
        }
    }
}

/** The design's floating glass pill: the active tab expands into an accent capsule with its label. */
@Composable
private fun FloatingNav(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    inboxCount: Int,
    transferLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(33.dp)
    Row(
        modifier.widthIn(max = 440.dp).fillMaxWidth().height(66.dp)
            .dropShadow(shape, Shadow(30.dp, Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 10.dp)))
            .clip(shape)
            .background(InkNav)
            .border(1.dp, Hairline, shape)
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            val bg by animateColorAsState(if (selected) Accent else Color.Transparent, label = "tabBg")
            val badge = when {
                t == Tab.INBOX && inboxCount > 0 -> "$inboxCount"
                t == Tab.TRANSFERS && transferLive -> ""
                else -> null
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(bg)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(t) })
                    .semantics {
                        contentDescription = when {
                            t == Tab.INBOX && inboxCount > 0 -> trPlural(inboxCount, "{0}, 1 file", "{0}, {n} files", t.label)
                            t == Tab.TRANSFERS && transferLive -> tr("{0}, transfer in progress", t.label)
                            else -> t.label
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        CdIcon(tabIcon(t), if (selected) OnAccent else NavIdle, size = if (selected) 20.dp else 22.dp)
                        if (badge != null && !selected) NavBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp))
                    }
                    AnimatedVisibility(selected, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Text(
                            t.label, color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBadge(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) {
        Dot(Accent, modifier.size(8.dp), size = 8.dp)
    } else {
        Box(
            modifier.height(16.dp).widthIn(min = 16.dp).clip(CircleShape).background(Accent).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (text.length > 2) "99+" else text, color = OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
        }
    }
}

private fun tabIcon(t: Tab) = when (t) {
    Tab.RADAR -> CdIcons.Radar
    Tab.TRANSFERS -> CdIcons.Transfers
    Tab.INBOX -> CdIcons.Inbox
    Tab.SETTINGS -> CdIcons.Sliders
}
