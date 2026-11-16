package com.crossdrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.di.AppContainer
import com.crossdrop.net.isTerminal
import androidx.compose.ui.graphics.toComposeImageBitmap
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

enum class Tab(val label: String) {
    RADAR("Radar"),
    TRANSFERS("Transfers"),
    INBOX("Inbox"),
    SETTINGS("Settings"),
}

/**
 * Desktop shell: the phone design's floating glass pill, stood on its side as
 * a rail at the window's left edge. Overlays (live transfer, QR share,
 * wireless drive, mounted drive) cover the whole window; an inbound offer
 * takes the window on top. Esc acts as Back (CMP's BackHandler).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Root(container: AppContainer) {
    val peers by container.peers.collectAsState()
    val groups by container.coordinator.groups.collectAsState()
    val focused by container.coordinator.focusedGroup.collectAsState()
    val allSessions by container.coordinator.sessions.collectAsState()
    val live by container.coordinator.live.collectAsState()
    val switchIn by container.switchReceiver.state.collectAsState()
    val pending by container.pending.collectAsState()
    val shareState by container.qrShare.state.collectAsState()
    val vaultUi by container.vaultExplorer.state.collectAsState()
    val vaultHost by container.vaultHost.state.collectAsState()
    val incomingOffer by container.coordinator.incomingOffer.collectAsState()
    val deviceName by container.deviceName.collectAsState()
    val nsdEnabled by container.nsdEnabled.collectAsState()
    val listenerPort by container.listenerPort.collectAsState()

    var tabName by remember { mutableStateOf(Tab.RADAR.name) }
    val tab = Tab.valueOf(tabName)
    var inboxCount by remember { mutableIntStateOf(container.inbox.list().size) }
    var historyTick by remember { mutableIntStateOf(0) }
    var inboxTick by remember { mutableIntStateOf(0) }
    var driveOpen by remember { mutableStateOf(false) }
    var transferHidden by remember { mutableStateOf(false) }
    var liveHidden by remember { mutableStateOf(false) }
    var switchHidden by remember { mutableStateOf(false) }
    var composingText by remember { mutableStateOf(false) }
    val doneCount = allSessions.count { it.status.isTerminal }

    // Files may land while we're anywhere in the app — keep badges fresh.
    LaunchedEffect(doneCount) { inboxCount = container.inbox.list().size }
    // A newly focused group always opens the transfer view.
    LaunchedEffect(focused?.id) { if (focused != null) transferHidden = false }
    // A minimised group that finishes is cleared — history already has it.
    LaunchedEffect(transferHidden, focused?.isTerminal) {
        val f = focused
        if (transferHidden && f != null && f.isTerminal) container.coordinator.dismissGroup(f.id)
    }
    LaunchedEffect(live?.grantId) { liveHidden = false }
    LaunchedEffect(switchIn?.folder) { switchHidden = false }
    // Something to send arrived (drop, Open With, Send to): show the radar to pick devices.
    LaunchedEffect(pending) { if (pending != null) tabName = Tab.RADAR.name }

    BackHandler(enabled = tab != Tab.RADAR) { tabName = Tab.RADAR.name }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxSize()) {
            SideNav(
                tab = tab,
                onSelect = { tabName = it.name },
                inboxCount = inboxCount,
                transferLive = groups.any { !it.isTerminal } && (transferHidden || focused == null),
                modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 18.dp),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.RADAR -> RadarScreen(
                        selfName = deviceName,
                        peers = peers.values.sortedBy { it.lastSeenMs },
                        onSendTo = { peer, files -> container.coordinator.sendTo(peer, files) },
                        pending = pending,
                        onSendPending = { targets ->
                            when (val p = pending) {
                                is com.crossdrop.ui.radar.Pending.Files -> container.coordinator.sendTo(targets, p.files)
                                is com.crossdrop.ui.radar.Pending.Text -> container.coordinator.sendText(targets, p.text)
                                null -> Unit
                            }
                            container.pending.value = null
                        },
                        onClearPending = { container.pending.value = null },
                        onComposeText = { composingText = true },
                        onPickForSeveral = {
                            val picked = com.crossdrop.platform.SystemActions.pickFiles(title = "Choose files to send")
                            container.openFiles(picked)
                        },
                        onShareToAnyone = { files -> container.qrShare.start(files) },
                        onMountVault = { peer -> container.vaultExplorer.mount(peer) },
                        onRename = container::setDeviceName,
                        selfPort = listenerPort,
                        nsdEnabled = nsdEnabled,
                    )
                    Tab.TRANSFERS -> TransfersScreen(
                        groups = groups,
                        history = container.history,
                        onClearHistory = { container.history.clear(); historyTick++ },
                        refreshSignal = listOf(doneCount, historyTick, switchIn?.phase),
                        onOpenActive = { id -> container.coordinator.focus(id); transferHidden = false },
                    )
                    Tab.INBOX -> InboxScreen(
                        inbox = container.inbox,
                        onChanged = { inboxCount = container.inbox.list().size },
                        refreshSignal = listOf(doneCount, inboxTick),
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        deviceName = deviceName,
                        instanceId = container.instanceId,
                        port = listenerPort,
                        nsdEnabled = nsdEnabled,
                        onRename = container::setDeviceName,
                        onSetNsd = container::setNsdEnabled,
                        onRescan = container::restartDiscovery,
                        inboxStats = { container.inbox.list().let { it.size to it.sumOf { f -> f.size } } },
                        onWipeInbox = {
                            container.inbox.shredAll()
                            inboxCount = 0
                            inboxTick++
                        },
                        onClearHistory = { container.history.clear(); historyTick++ },
                        vaultState = vaultHost,
                        onOpenDrive = { driveOpen = true },
                        refreshSignal = listOf(doneCount, inboxTick, historyTick),
                    )
                }
            }
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

        // ---- a phone's camera, live on this screen
        Overlay(live?.takeIf { !liveHidden }) { feed ->
            BackHandler { liveHidden = true }
            com.crossdrop.ui.live.LiveViewerScreen(
                feed = feed,
                loadImage = ::decodeImage,
                onOpen = { shot -> com.crossdrop.platform.SystemActions.open(shot.file) },
                onStop = container.coordinator::stopLive,
                onHide = { liveHidden = true },
            )
        }

        // ---- a phone moving its library here
        Overlay(switchIn?.takeIf { !switchHidden }) { st ->
            BackHandler { switchHidden = true }
            com.crossdrop.ui.switchphones.SwitchReceiveScreen(
                state = st,
                receiving = allSessions.lastOrNull {
                    it.kind == com.crossdrop.core.protocol.OfferKind.SWITCH && !it.status.isTerminal
                },
                onStop = container.switchReceiver::stop,
                onHide = { switchHidden = true },
                onDone = container.switchReceiver::close,
            )
        }

        // ---- "send to anyone" (QR + embedded HTTP share)
        Overlay(shareState) { s ->
            BackHandler { container.qrShare.stop() }
            com.crossdrop.ui.qrshare.QrShareScreen(state = s, onStop = container.qrShare::stop)
        }

        // ---- wireless drive (this computer as a network drive)
        Overlay(if (driveOpen) Unit else null) {
            com.crossdrop.ui.drive.DriveHostScreen(
                state = vaultHost,
                onStart = { rw, pin, folder -> container.vaultHost.start(rw, pin, folder = folder) },
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
                onUpload = container.vaultExplorer::upload,
                onStream = { path -> container.vaultExplorer.streamUrl(path) },
                isMedia = { e -> container.vaultExplorer.isMediaPath(e.name, e.mime) },
                onClose = container.vaultExplorer::close,
                onUnlock = container.vaultExplorer::unlock,
            )
        }

        // ---- inbound offer — always on top; answered only by its buttons
        Overlay(incomingOffer, fade = true) { offer ->
            IncomingOfferSheet(offer) { accept -> container.coordinator.respondToOffer(accept) }
        }
    }

    if (composingText) {
        val clip = remember {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            }.getOrNull().orEmpty().take(4000)
        }
        SendTextDialog(initial = clip, target = "devices nearby") { text ->
            composingText = false
            if (!text.isNullOrBlank()) container.pending.value = com.crossdrop.ui.radar.Pending.Text(text)
        }
    }
}

/**
 * Live-camera shots: decoded with Skia (which applies the EXIF orientation phone cameras use, so
 * photos come out upright — ExifOrientationTest), scaled down to about [maxPx] on the long side.
 */
internal fun decodeImage(file: java.io.File, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    if (com.crossdrop.ui.live.isVideoName(file.name, null)) return null
    val img = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
    val scale = maxPx.toFloat() / maxOf(img.width, img.height)
    if (scale >= 1f) return img.toComposeImageBitmap()
    val w = (img.width * scale).toInt().coerceAtLeast(1)
    val h = (img.height * scale).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

/** Full-window layer shown while [value] is non-null; keeps the last value through its exit animation. */
@Composable
private fun <T : Any> Overlay(value: T?, fade: Boolean = false, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    AnimatedVisibility(
        visible = value != null,
        enter = if (fade) fadeIn() else slideInVertically { it / 6 } + fadeIn(),
        exit = if (fade) fadeOut() else slideOutVertically { it / 6 } + fadeOut(),
    ) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            last?.let { content(it) }
        }
    }
}

/** The glass pill nav, vertical: brand mark, then one capsule per tab (accent when active). */
@Composable
private fun SideNav(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    inboxCount: Int,
    transferLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier.width(84.dp).fillMaxHeight()
            .dropShadow(shape, Shadow(30.dp, Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 10.dp)))
            .clip(shape)
            .background(InkNav)
            .border(1.dp, Hairline, shape)
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).border(2.dp, Accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Accent))
            }
        }
        Tab.entries.forEach { t ->
            val selected = t == tab
            val bg by animateColorAsState(if (selected) Accent else Color.Transparent, label = "tabBg")
            val fg = if (selected) OnAccent else NavIdle
            Column(
                Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(26.dp)).background(bg)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(t) })
                    .semantics {
                        contentDescription = t.label + when {
                            t == Tab.INBOX && inboxCount > 0 -> ", $inboxCount files"
                            t == Tab.TRANSFERS && transferLive -> ", transfer in progress"
                            else -> ""
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    CdIcon(tabIcon(t), fg, size = 22.dp)
                    val badge = when {
                        t == Tab.INBOX && inboxCount > 0 -> if (inboxCount > 99) "99+" else "$inboxCount"
                        t == Tab.TRANSFERS && transferLive -> ""
                        else -> null
                    }
                    if (badge != null && !selected) {
                        val m = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-5).dp)
                        if (badge.isEmpty()) Dot(Accent, m, size = 8.dp)
                        else Box(
                            m.height(16.dp).widthIn(min = 16.dp).clip(CircleShape).background(Accent).padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(badge, color = OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(t.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f).fillMaxWidth())
    }
}

private fun tabIcon(t: Tab) = when (t) {
    Tab.RADAR -> CdIcons.Radar
    Tab.TRANSFERS -> CdIcons.Transfers
    Tab.INBOX -> CdIcons.Inbox
    Tab.SETTINGS -> CdIcons.Sliders
}
