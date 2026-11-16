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
import com.crossdrop.di.AppContainer
import com.crossdrop.net.isTerminal
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

enum class Tab(val label: String) {
    RADAR("Radar"),
    TRANSFERS("Transfers"),
    INBOX("Inbox"),
    SETTINGS("Settings"),
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
    val session by container.coordinator.session.collectAsState()
    val shareState by container.qrShare.state.collectAsState()
    val vaultUi by container.vaultExplorer.state.collectAsState()
    val vaultHost by container.vaultHost.state.collectAsState()
    val incomingOffer by container.coordinator.incomingOffer.collectAsState()
    val deviceName by container.deviceName.collectAsState()
    val nsdEnabled by container.nsdEnabled.collectAsState()
    val bleEnabled by container.bleEnabled.collectAsState()
    val listenerPort by container.listenerPort.collectAsState()

    var tabName by rememberSaveable { mutableStateOf(Tab.RADAR.name) }
    val tab = Tab.valueOf(tabName)
    var inboxCount by remember { mutableIntStateOf(container.inbox.list().size) }
    var historyTick by remember { mutableIntStateOf(0) }
    var inboxTick by remember { mutableIntStateOf(0) }
    var driveOpen by remember { mutableStateOf(false) }
    var transferHidden by remember { mutableStateOf(false) }

    // Files may land while we're anywhere in the app — keep badges fresh.
    LaunchedEffect(session) { inboxCount = container.inbox.list().size }
    // A new session always opens the transfer view.
    LaunchedEffect(session == null) { if (session == null) transferHidden = false }
    // A minimised session that finishes is cleared — history already has it.
    LaunchedEffect(transferHidden, session?.status) {
        if (transferHidden && session?.status?.isTerminal == true) container.coordinator.dismissSession()
    }

    BackHandler(enabled = tab != Tab.RADAR) { tabName = Tab.RADAR.name }

    val navInset = 66.dp + 18.dp + 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalNavInset provides navInset) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            when (tab) {
                Tab.RADAR -> RadarScreen(
                    selfName = deviceName,
                    peers = peers.values.sortedBy { it.lastSeenMs },
                    onSendTo = { peer, uris ->
                        TransferService.start(container.contextForService())
                        container.coordinator.sendTo(peer, uris)
                    },
                    onShareToAnyone = { uris ->
                        TransferService.start(container.contextForService())
                        container.qrShare.start(uris)
                    },
                    onMountVault = { peer -> container.vaultExplorer.mount(peer) },
                    onRename = container::setDeviceName,
                    onPermissionsGranted = container::restartDiscovery,
                    selfPort = listenerPort,
                    nsdEnabled = nsdEnabled,
                )
                Tab.TRANSFERS -> TransfersScreen(
                    session = session,
                    history = container.history,
                    onClearHistory = { container.history.clear(); historyTick++ },
                    refreshSignal = listOf(session?.status, historyTick),
                    onOpenActive = { transferHidden = false },
                )
                Tab.INBOX -> InboxScreen(
                    inbox = container.inbox,
                    onChanged = { inboxCount = container.inbox.list().size },
                    refreshSignal = listOf(session?.status, inboxTick),
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
                    vaultState = vaultHost,
                    onOpenDrive = { driveOpen = true },
                    onSelfTest = if (com.crossdrop.BuildConfig.DEBUG)
                        ({ container.coordinator.selfTransfer() }) else null,
                    onAddPeer = if (com.crossdrop.BuildConfig.DEBUG)
                        ({ h, p -> container.injectPeer(null, h, p) }) else null,
                    refreshSignal = listOf(session?.status, inboxTick, historyTick),
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
                transferLive = transferHidden && session?.status?.isTerminal == false,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            )

            // ---- live transfer
            Overlay(session?.takeIf { !transferHidden }) { s ->
                BackHandler { if (s.status.isTerminal) container.coordinator.dismissSession() else transferHidden = true }
                TransferScreen(
                    session = s,
                    selfName = deviceName,
                    onCancel = container.coordinator::cancelSession,
                    onClose = container.coordinator::dismissSession,
                    onHide = { transferHidden = true },
                    onOpenInbox = {
                        container.coordinator.dismissSession()
                        tabName = Tab.INBOX.name
                    },
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

            // ---- inbound offer — always on top; Back declines
            Overlay(incomingOffer, fade = true) { offer ->
                BackHandler {
                    container.coordinator.respondToOffer(false)
                }
                IncomingOfferSheet(offer) { accept ->
                    if (accept) TransferService.start(container.contextForService())
                    container.coordinator.respondToOffer(accept)
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
                        contentDescription = t.label + when {
                            t == Tab.INBOX && inboxCount > 0 -> ", $inboxCount files"
                            t == Tab.TRANSFERS && transferLive -> ", transfer in progress"
                            else -> ""
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
