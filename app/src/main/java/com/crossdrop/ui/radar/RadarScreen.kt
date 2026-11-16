package com.crossdrop.ui.radar

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.BuildConfig
import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Peer
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.DeviceNameDialog
import com.crossdrop.ui.GlassChip
import com.crossdrop.ui.LocalNavInset
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.QrCard
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.OnAccentMuted
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import kotlin.math.roundToInt

/** Runtime permissions the radar needs, filtered by SDK level. */
fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}

private fun hasAll(context: android.content.Context): Boolean =
    requiredPermissions().all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Home tab: brand + network chip, the sonar with every nearby device, then
 * "Send to anyone" and any wireless drives on the network. Tapping a device
 * opens the send sheet (photo picker or files); a drive opens its own sheet.
 */
@Composable
fun RadarScreen(
    selfName: String,
    peers: List<Peer>,
    onSendTo: (Peer, List<android.net.Uri>) -> Unit,
    onShareToAnyone: (List<android.net.Uri>) -> Unit = {},
    onMountVault: (Peer) -> Unit = {},
    onRename: (String) -> Unit,
    onPermissionsGranted: () -> Unit = {},
    selfPort: Int = 0,
    nsdEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var pendingPeer by remember { mutableStateOf<Peer?>(null) }
    var pendingAnyone by remember { mutableStateOf(false) }
    var pickingForPeer by remember { mutableStateOf<Peer?>(null) }
    var pickingAnyone by remember { mutableStateOf(false) }
    var vaultPeer by remember { mutableStateOf<Peer?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var permsGranted by remember { mutableStateOf(hasAll(context)) }

    fun dispatch(uris: List<android.net.Uri>?) {
        val peer = pendingPeer
        val anyone = pendingAnyone
        pendingPeer = null
        pendingAnyone = false
        if (uris.isNullOrEmpty()) return
        if (anyone) onShareToAnyone(uris) else if (peer != null) onSendTo(peer, uris)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { dispatch(it) }
    // Default maxItems = the system picker's own ceiling (100+ on Android 13+).
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { dispatch(it) }

    fun tap() = view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

    /** Device tap → send sheet; a vault beacon → its mount sheet. */
    fun startSendFlow(peer: Peer) {
        if (peer.endpoint == null) return
        tap()
        if (peer.vault != null) { vaultPeer = peer; return }
        pendingPeer = peer
        pickingForPeer = peer
    }

    /** "Send to anyone" → same sheet, but the result feeds the QR share. */
    fun startAnyoneFlow() {
        tap()
        pendingAnyone = true
        pickingAnyone = true
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permsGranted = grants.values.all { it } || hasAll(context)
        if (permsGranted) onPermissionsGranted()   // e.g. bring BLE layer up
    }

    LaunchedEffect(Unit) {
        if (!permsGranted) permLauncher.launch(requiredPermissions().toTypedArray())
    }

    val devices = peers.filter { it.vault == null }
    val drives = peers.filter { it.vault != null && it.endpoint != null }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- header
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(8.dp))
                Text("CrossDrop", style = displayStyle(16, tracking = -0.01f), modifier = Modifier.weight(1f))
                GlassChip(
                    buildString {
                        append(if (nsdEnabled) "Wi-Fi · local only" else "Wi-Fi discovery off")
                        if (BuildConfig.DEBUG && selfPort > 0) append(" · :$selfPort")
                    },
                    dot = if (nsdEnabled) Accent else TextDim,
                )
            }
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
                if (!permsGranted) {
                    Row(
                        Modifier.height(34.dp).clip(RoundedCornerShape(50))
                            .background(Amber.copy(alpha = 0.1f))
                            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .clickable(role = Role.Button) {
                                permLauncher.launch(requiredPermissions().toTypedArray())
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CdIcon(CdIcons.Bluetooth, Amber, size = 13.dp)
                        Text("Allow nearby", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---- sonar
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).heightIn(min = 220.dp)) {
            // ambient glow centred on the radar; not clipped, so it bleeds softly past the box
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
            )
            if (devices.isEmpty()) {
                Text(
                    "Open CrossDrop on another device on this Wi-Fi",
                    color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }
        }

        // ---- actions
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SendToAnyoneCard(::startAnyoneFlow)
            drives.take(2).forEach { d -> DriveRow(d) { startSendFlow(d) } }
        }
        Spacer(Modifier.height(LocalNavInset.current))
    }

    if (renaming) {
        DeviceNameDialog(current = selfName, onDone = { new ->
            renaming = false
            new?.let(onRename)
        })
    }

    // ---- send sheet: photo picker or generic files
    pickingForPeer?.let { peer ->
        val d = peer.estimatedDistanceMeters
        SendSheet(
            kicker = "Send to",
            title = peer.name,
            icon = deviceIconFor(peer.name),
            meta = {
                MetaLine(
                    lead = d?.let { if (it < 1.0) "< 1 m" else "≈ ${it.roundToInt()} m" },
                    rest = peer.sources.sortedBy { it.ordinal }.joinToString(" + ") {
                        when (it) { DiscoverySource.NSD -> "Wi-Fi"; DiscoverySource.BLE -> "Bluetooth" }
                    },
                )
            },
            note = "Straight to this device, end-to-end encrypted, never through a server. " +
                "You'll both see a 6-digit code to compare.",
            onDismiss = { pickingForPeer = null; pendingPeer = null },
            onPickMedia = {
                pickingForPeer = null
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = { pickingForPeer = null; filePicker.launch(arrayOf("*/*")) },
        )
    }
    if (pickingAnyone) {
        SendSheet(
            kicker = "Share with",
            title = "Anyone nearby",
            icon = CdIcons.Qr,
            meta = { MetaLine(lead = null, rest = "No app needed — they scan a QR code") },
            note = "They need to be on this Wi-Fi. The link is secret, and it stops working " +
                "the moment you close the share screen.",
            onDismiss = { pickingAnyone = false; pendingAnyone = false },
            onPickMedia = {
                pickingAnyone = false
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = { pickingAnyone = false; filePicker.launch(arrayOf("*/*")) },
        )
    }

    // ---- vault beacon tap → drive sheet
    vaultPeer?.let { peer ->
        VaultMountSheet(
            peer = peer,
            onDismiss = { vaultPeer = null },
            onMount = { vaultPeer = null; onMountVault(peer) },
        )
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(24.dp).border(2.dp, Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(8.dp).clip(CircleShape).background(Accent)) }
}

@Composable
private fun SendToAnyoneCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp)
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
private fun DriveRow(peer: Peer, onClick: () -> Unit) {
    val vault = peer.vault ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
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

@Composable
private fun MetaLine(lead: String?, rest: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            if (lead != null) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold))
                append(lead)
                pop()
                if (rest.isNotEmpty()) append(" · ")
            }
            append(rest)
        },
        color = TextSecondary, fontSize = 12.sp,
    )
}

// ------------------------------------------------------------- sheets --

/** The design's floating sheet: rounded on all corners, inset from the screen edges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CdSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = InkSheet,
        contentColor = TextPrimary,
        scrimColor = Color(0x9E040609),
        shape = RoundedCornerShape(36.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp).padding(bottom = 8.dp),
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
            content = content,
        )
    }
}

@Composable
private fun SheetHeader(kicker: String, title: String, icon: ImageVector, iconTint: Color = TextPrimary, meta: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(62.dp)
                .dropShadow(CircleShape, Shadow(18.dp, Accent.copy(alpha = 0.22f)))
                .clip(CircleShape).background(GlassRaised)
                .border(1.5.dp, Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { CdIcon(icon, iconTint, size = 26.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(kicker, color = TextDim, fontSize = 13.sp)
            Text(
                title, style = displayStyle(26).copy(lineHeight = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            meta()
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
private fun NoteCard(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GlassLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(CdIcons.Shield, Accent, Modifier.padding(top = 1.dp), size = 20.dp)
        Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun SendSheet(
    kicker: String,
    title: String,
    icon: ImageVector,
    meta: @Composable () -> Unit,
    note: String,
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
) {
    CdSheet(onDismiss) {
        SheetHeader(kicker, title, icon, meta = meta)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("Photos & videos", "Gallery, albums, Google Photos", CdIcons.Photo, true, onPickMedia, Modifier.weight(1f))
            BigTile("Files", "Any type, pick several", CdIcons.Doc, false, onPickFiles, Modifier.weight(1f))
        }
        NoteCard(note)
        PillButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}

/**
 * Drive beacon sheet: open it here (encrypted session, native explorer) or
 * hand the browser address to anyone nearby — copied, with a QR to scan.
 */
@Composable
private fun VaultMountSheet(peer: Peer, onDismiss: () -> Unit, onMount: () -> Unit) {
    val vault = peer.vault!!
    val clipboard = LocalClipboardManager.current
    val url = peer.endpoint?.let { "http://${it.host}:${it.port}/" } ?: ""
    var showQr by remember { mutableStateOf(false) }
    CdSheet(onDismiss) {
        SheetHeader(
            "Wireless drive", vault.vaultName, CdIcons.Drive, iconTint = Amber,
            meta = {
                MetaLine(
                    lead = if (vault.needsPin) "PIN protected" else null,
                    rest = if (vault.writable) "you can add & edit" else "view & download",
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("Open here", "Browse, stream and save — encrypted", CdIcons.Drive, true, onMount, Modifier.weight(1f))
            BigTile(
                "Open in browser", if (showQr) "Link copied — or scan below" else "Copy the link, show a QR",
                CdIcons.Globe, false,
                {
                    if (url.isNotEmpty()) clipboard.setText(AnnotatedString(url))
                    showQr = !showQr
                },
                Modifier.weight(1f),
            )
        }
        AnimatedVisibility(showQr && url.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCard(url, size = 200.dp)
                Spacer(Modifier.height(18.dp))
                Text(url, style = com.crossdrop.ui.theme.monoStyle(13, TextSecondary))
            }
        }
        NoteCard("Files come straight from this drive over your Wi-Fi. Nothing is uploaded anywhere.")
        PillButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}
