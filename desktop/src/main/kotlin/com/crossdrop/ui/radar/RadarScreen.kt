package com.crossdrop.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.Peer
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SystemActions
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.DeviceNameDialog
import com.crossdrop.ui.GlassChip
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.QrCard
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.OnAccentMuted
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import java.io.File

/** Glyph for this computer on the radar, the transfer view and Settings. */
internal val selfDeviceIcon: ImageVector get() = if (Platform.isMac) CdIcons.Laptop else CdIcons.Monitor

/**
 * Home tab: brand + network chip, the sonar with every nearby device, then
 * "Send to anyone" and any wireless drives on the network. Tapping a device
 * opens the native file dialog (no photo picker on desktop); a drive opens
 * its sheet.
 */
/** Files or text waiting for devices to be picked (dropped on the window, opened with CrossDrop…). */
sealed interface Pending {
    data class Files(val files: List<File>) : Pending
    data class Text(val text: String) : Pending
}

@Composable
fun RadarScreen(
    selfName: String,
    peers: List<Peer>,
    onSendTo: (Peer, List<File>) -> Unit,
    pending: Pending? = null,
    onSendPending: (List<Peer>) -> Unit = {},
    onClearPending: () -> Unit = {},
    onComposeText: () -> Unit = {},
    onPickForSeveral: () -> Unit = {},
    onShareToAnyone: (List<File>) -> Unit = {},
    onMountVault: (Peer) -> Unit = {},
    onRename: (String) -> Unit,
    selfPort: Int = 0,
    nsdEnabled: Boolean = true,
) {
    var vaultPeer by remember { mutableStateOf<Peer?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var picked by remember(pending) { mutableStateOf(emptySet<String>()) }

    fun startSendFlow(peer: Peer) {
        if (peer.endpoint == null) return
        if (peer.vault != null) { vaultPeer = peer; return }
        // Something is waiting to go: a device tap picks (or un-picks) it as a destination.
        if (pending != null) {
            picked = if (peer.instanceId in picked) picked - peer.instanceId else picked + peer.instanceId
            return
        }
        val picked = SystemActions.pickFiles(title = "Send to ${peer.name}")
        if (picked.isNotEmpty()) onSendTo(peer, picked)
    }

    fun startAnyoneFlow() {
        val picked = SystemActions.pickFiles(title = "Share via QR — anyone with a browser can download")
        if (picked.isNotEmpty()) onShareToAnyone(picked)
    }

    val devices = peers.filter { it.vault == null }
    val drives = peers.filter { it.vault != null && it.endpoint != null }

    Column(Modifier.fillMaxSize()) {
        // ---- header
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Radar", style = displayStyle(36, tracking = -0.04f).copy(lineHeight = 36.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        when (devices.size) {
                            0 -> "Looking for devices…"
                            1 -> "1 device nearby"
                            else -> "${devices.size} devices nearby"
                        },
                        color = TextDim, fontSize = 14.sp,
                    )
                }
                GlassChip(
                    if (nsdEnabled) "Local network only" + (if (selfPort > 0) " · :$selfPort" else "")
                    else "Discovery off",
                    dot = if (nsdEnabled) Accent else TextDim,
                )
            }
        }

        // ---- sonar
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp).heightIn(min = 240.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = minOf(size.width, size.height) * 0.69f
                drawCircle(
                    Brush.radialGradient(listOf(Accent.copy(alpha = 0.18f), Color.Transparent), center, r),
                    radius = r, center = center,
                )
            }
            RadarCanvas(
                selfName = selfName,
                peers = devices,
                onPeerTap = ::startSendFlow,
                onSelfTap = { renaming = true },
                selfIcon = selfDeviceIcon,
            )
            if (devices.isEmpty()) {
                Text(
                    "Open CrossDrop on a phone or another ${Platform.current.computerNoun} on this network",
                    color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }
        }

        // ---- actions
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pending != null) {
                PendingCard(
                    pending, devices.filter { it.endpoint != null }, picked,
                    onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                    onSend = { onSendPending(devices.filter { it.instanceId in picked }) },
                    onClear = onClearPending,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            } else {
                SendToAnyoneCard(::startAnyoneFlow, Modifier.widthIn(max = 560.dp))
                Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("Send text or a link", CdIcons.Note, Modifier.weight(1f), onComposeText)
                    QuickAction("Send to several devices", CdIcons.Transfers, Modifier.weight(1f), onPickForSeveral)
                }
            }
            drives.take(2).forEach { d -> DriveRow(d, Modifier.widthIn(max = 560.dp)) { startSendFlow(d) } }
        }
    }

    if (renaming) {
        DeviceNameDialog(current = selfName, onDone = { new ->
            renaming = false
            new?.let(onRename)
        })
    }
    vaultPeer?.let { peer ->
        VaultMountSheet(peer = peer, onDismiss = { vaultPeer = null }, onMount = { vaultPeer = null; onMountVault(peer) })
    }
}

@Composable
private fun SendToAnyoneCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 74.dp)
            .clip(RoundedCornerShape(26.dp)).background(GlassMid)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { CdIcon(CdIcons.Qr, Accent, size = 24.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Send to anyone", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("No app needed. They scan a QR code.", color = TextDim, fontSize = 12.sp)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) { CdIcon(CdIcons.ArrowRight, OnAccent, size = 18.dp) }
    }
}

@Composable
private fun DriveRow(peer: Peer, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val vault = peer.vault ?: return
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp)
            .clip(RoundedCornerShape(22.dp)).background(GlassLow)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = "Open drive", onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(CdIcons.Drive, TextPrimary, size = 22.dp)
        Text(
            vault.vaultName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        when {
            vault.needsPin -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CdIcon(CdIcons.Lock, Amber, size = 12.dp)
                Text("PIN protected", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            vault.writable -> Text("Editable", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            else -> Text("Read-only", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------- sheet --

/**
 * Drive beacon sheet: open it here (encrypted session, native explorer) or
 * hand the browser address to anyone nearby — copied, with a QR to scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultMountSheet(peer: Peer, onDismiss: () -> Unit, onMount: () -> Unit) {
    val vault = peer.vault!!
    val url = peer.endpoint?.let { "http://${it.host}:${it.port}/" } ?: ""
    var showQr by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = InkSheet,
        contentColor = TextPrimary,
        scrimColor = Color(0x9E040609),
        shape = RoundedCornerShape(36.dp),
        tonalElevation = 0.dp,
        sheetMaxWidth = 560.dp,
        modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 10.dp).size(40.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(62.dp)
                        .dropShadow(CircleShape, Shadow(18.dp, Accent.copy(alpha = 0.22f)))
                        .clip(CircleShape).background(GlassRaised)
                        .border(1.5.dp, Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(CdIcons.Drive, Amber, size = 26.dp) }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Wireless drive", color = TextDim, fontSize = 13.sp)
                    Text(vault.vaultName, style = displayStyle(26).copy(lineHeight = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (vault.needsPin) "PIN protected · " else "") +
                            if (vault.writable) "you can add & edit" else "view & download",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigTile("Open here", "Browse, stream and save — encrypted", CdIcons.Drive, true, onMount, Modifier.weight(1f))
                BigTile(
                    "Open in browser", if (showQr) "Link copied — or scan below" else "Copy the link, show a QR",
                    CdIcons.Globe, false,
                    {
                        if (url.isNotEmpty()) SystemActions.copyToClipboard(url)
                        showQr = !showQr
                    },
                    Modifier.weight(1f),
                )
            }
            AnimatedVisibility(showQr && url.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCard(url, size = 200.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(url, style = monoStyle(13, TextSecondary))
                }
            }
            PillButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
        }
    }
}

@Composable
private fun BigTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.height(178.dp)
            .then(if (primary) Modifier.dropShadow(shape, Shadow(40.dp, Accent.copy(alpha = 0.18f), offset = DpOffset(0.dp, 12.dp))) else Modifier)
            .clip(shape)
            .background(if (primary) Accent else GlassMid)
            .then(if (!primary) Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CdIcon(icon, if (primary) OnAccent else TextPrimary, size = 32.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = displayStyle(19, tracking = -0.02f, color = if (primary) OnAccent else TextPrimary))
            Text(subtitle, color = if (primary) OnAccentMuted else TextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(22.dp)).background(GlassLow)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CdIcon(icon, Accent, size = 18.dp)
        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What's waiting to be sent, and every device it can go to — pick any number, then Send. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    pending: Pending,
    devices: List<Peer>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(GlassMid)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                CdIcon(if (pending is Pending.Text) CdIcons.Note else CdIcons.ArrowUp, Accent, size = 20.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when (pending) {
                        is Pending.Files -> if (pending.files.size == 1) pending.files.first().name else "${pending.files.size} files"
                        is Pending.Text -> pending.text.replace('\n', ' ')
                    },
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (devices.isEmpty()) "Waiting for devices on this network…" else "Pick where it goes — on the radar or here",
                    color = TextDim, fontSize = 12.sp,
                )
            }
            com.crossdrop.ui.ChipButton("Clear", onClear, height = 34.dp)
        }
        if (devices.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                devices.forEach { p ->
                    val on = p.instanceId in picked
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (on) Accent else GlassRaised)
                            .clickable(role = Role.Checkbox) { onToggle(p.instanceId) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CdIcon(if (on) CdIcons.Check else deviceIconFor(p.name), if (on) OnAccent else TextPrimary, size = 14.dp)
                        Text(p.name, color = if (on) OnAccent else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        PillButton(
            when (picked.size) {
                0 -> "Pick a device"
                1 -> "Send to " + (devices.firstOrNull { it.instanceId in picked }?.name ?: "1 device")
                else -> "Send to ${picked.size} devices"
            },
            onSend, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty(), icon = CdIcons.ArrowUp, glow = true,
        )
    }
}
