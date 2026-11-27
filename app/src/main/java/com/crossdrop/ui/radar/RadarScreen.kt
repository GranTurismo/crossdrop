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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.liveRegion
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
import com.crossdrop.core.connect.ConnectHint
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
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
    onSendTo: (List<Peer>, List<android.net.Uri>) -> Unit,
    /** [grantIsNew]: this send took the read grant itself (it was not held for something else), so it may be let go later. */
    onSendFolder: (List<Peer>, android.net.Uri, grantIsNew: Boolean) -> Unit = { _, _, _ -> },
    onCamera: (List<Peer>) -> Unit = {},
    onSendText: (List<Peer>, String) -> Unit = { _, _ -> },
    onContacts: (List<Peer>) -> Unit = {},
    onApps: (List<Peer>) -> Unit = {},
    onSwitchPhones: (Peer) -> Unit = {},
    onShareToAnyone: (List<android.net.Uri>) -> Unit = {},
    onMountVault: (Peer) -> Unit = {},
    onRename: (String) -> Unit,
    onPermissionsGranted: () -> Unit = {},
    selfPort: Int = 0,
    nsdEnabled: Boolean = true,
    /** Wi-Fi/Ethernet (or our own hotspot) is up; drives the "can't see the other device?" help. */
    hasNetwork: Boolean = true,
    directLinkOn: Boolean = false,
    /** The link that is on is the CrossDrop Wi-Fi (not the fallback hotspot with a name of its own). */
    directLinkOneTap: Boolean = true,
    onConnectDirectly: () -> Unit = {},
    onShowConnectGuide: () -> Unit = {},
    onOpenDirectLink: () -> Unit = {},
    /** "Show a code" straight from the not-on-your-Wi-Fi sheet (starts this phone's private link). */
    /** Paired devices that are not here right now, and what is waiting for them ("send when it's back"). */
    offlineDevices: List<com.crossdrop.core.pairing.PairedDevice> = emptyList(),
    queued: Map<String, Int> = emptyMap(),
    sendingIds: Set<String> = emptySet(),
    onQueueFor: (com.crossdrop.core.pairing.PairedDevice) -> Unit = {},
    onShowCode: () -> Unit = onConnectDirectly,
    onScanCode: () -> Unit = {},
    /** "Join a CrossDrop Wi-Fi" (one tap) from the not-on-your-Wi-Fi sheet. */
    onJoinWifi: () -> Unit = onScanCode,
    /** This phone joined another's link by scanning its code: the network, and the phone once it answered. */
    joinedSsid: String? = null,
    joinedHost: String? = null,
    onLeaveLink: () -> Unit = {},
    /** Shake to connect: on, when we were last shaken without a match yet, and the device we just shook with. */
    shakeEnabled: Boolean = false,
    /** Shaking has found a device before: no need to teach it with the animated card. */
    shakeLearned: Boolean = true,
    shakeWaitingSince: Long = 0L,
    shakeMatch: Peer? = null,
    onShakeHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    var pendingPeers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var textFor by remember { mutableStateOf<List<Peer>?>(null) }
    var pendingAnyone by remember { mutableStateOf(false) }
    var pickingForPeer by remember { mutableStateOf<Peer?>(null) }
    var pickingAnyone by remember { mutableStateOf(false) }
    var vaultPeer by remember { mutableStateOf<Peer?>(null) }
    var notOnWifi by remember { mutableStateOf<Peer?>(null) }
    /** The send sheet open because of a shake: it says so instead of "Send to". */
    var shakenId by remember { mutableStateOf<String?>(null) }
    // Teach shaking where it helps: once per launch, when several reachable devices show up, until it has
    // worked once. A tap or 12 s hides it.
    val reachableCount = peers.count { it.endpoint != null && it.vault == null }
    var shakeHintUsed by rememberSaveable { mutableStateOf(false) }
    var shakeHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(reachableCount >= 2, shakeEnabled, shakeLearned) {
        if (reachableCount >= 2 && shakeEnabled && !shakeLearned && !shakeHintUsed) {
            kotlinx.coroutines.delay(800)          // let the new nodes land on the radar first
            shakeHintUsed = true
            shakeHintVisible = true
        }
    }
    LaunchedEffect(shakeHintVisible) {
        if (shakeHintVisible) { kotlinx.coroutines.delay(12_000); shakeHintVisible = false }
    }
    LaunchedEffect(shakeWaitingSince) { if (shakeWaitingSince > 0) shakeHintVisible = false }
    LaunchedEffect(shakeLearned) { if (shakeLearned) shakeHintVisible = false }
    var renaming by remember { mutableStateOf(false) }
    var permsGranted by remember { mutableStateOf(hasAll(context)) }

    fun dispatch(uris: List<android.net.Uri>?) {
        val targets = pendingPeers
        val anyone = pendingAnyone
        pendingPeers = emptyList()
        pendingAnyone = false
        if (uris.isNullOrEmpty()) return
        if (anyone) onShareToAnyone(uris) else if (targets.isNotEmpty()) onSendTo(targets, uris)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { dispatch(it) }
    // A whole folder. Keep read access past this screen: the send keeps reading while the app is in the background.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val targets = pendingPeers
        pendingPeers = emptyList()
        if (uri != null && targets.isNotEmpty()) {
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onSendFolder(targets, uri, !held)
        }
    }
    // Default maxItems = the system picker's own ceiling (100+ on Android 13+).
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { dispatch(it) }

    fun tap() = view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

    /** Device tap → send sheet; a vault beacon → its mount sheet; heard over Bluetooth only → how to link. */
    fun startSendFlow(peer: Peer) {
        if (peer.endpoint == null) {
            if (peer.vault == null) { tap(); notOnWifi = peer }
            return
        }
        tap()
        if (peer.vault != null) { vaultPeer = peer; return }
        pendingPeers = listOf(peer)
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

    var pickingLanguage by remember { mutableStateOf(false) }
    if (pickingLanguage) com.crossdrop.ui.language.LanguageDialog { pickingLanguage = false }
    val devices = peers.filter { it.vault == null }
    val connectHint = com.crossdrop.ui.connect.rememberConnectHint(hasNetwork, devices.size, directLinkOn)
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
                        append(
                            when {
                                directLinkOn -> tr("Direct link · local only")
                                !hasNetwork -> tr("No Wi-Fi")
                                nsdEnabled -> tr("Wi-Fi · local only")
                                else -> tr("Wi-Fi discovery off")
                            },
                        )
                        if (BuildConfig.DEBUG && selfPort > 0) append(" · :$selfPort")
                    },
                    dot = when { directLinkOn -> Accent; !hasNetwork -> Amber; nsdEnabled -> Accent; else -> TextDim },
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("Radar"), style = displayStyle(36, tracking = -0.04f).copy(lineHeight = 36.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    val deviceCount = devices.size
                    Text(
                        if (deviceCount == 0) tr("Looking for devices…")
                        else trPlural(deviceCount, "1 device nearby", "{n} devices nearby"),
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
                        Text(tr("Allow nearby"), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                com.crossdrop.ui.language.LanguageButton({ pickingLanguage = true })
            }
        }

        // ---- sonar
        // The help card needs room; the sonar gives some back while it is showing.
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).heightIn(min = if (connectHint == ConnectHint.NONE) 220.dp else 120.dp)) {
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
            // The help card says this (and more) once it appears.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(shakeWaitingSince) {
                now = System.currentTimeMillis()
                while (now - shakeWaitingSince <= com.crossdrop.core.shake.ShakeMatcher.WINDOW_MS) {
                    kotlinx.coroutines.delay(250); now = System.currentTimeMillis()
                }
            }
            val shakeWaiting = shakeEnabled && now - shakeWaitingSince <= com.crossdrop.core.shake.ShakeMatcher.WINDOW_MS
            when {
                shakeWaiting -> Text(
                    tr("Now shake the other phone at the same time"),
                    color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
                devices.isEmpty() && connectHint == ConnectHint.NONE -> Text(
                    tr("Open CrossDrop on another device on this Wi-Fi"),
                    color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
                shakeEnabled && !shakeHintVisible && devices.count { it.endpoint != null } >= 2 -> Text(
                    tr("Several devices? Shake both phones to find each other."),
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
            androidx.compose.animation.AnimatedVisibility(
                visible = shakeHintVisible && reachableCount >= 2,
                enter = androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) { ShakeHintCard(onDismiss = { shakeHintVisible = false }) }
            com.crossdrop.ui.connect.ConnectHelpCard(connectHint, onGuide = onShowConnectGuide, onPrimary = onConnectDirectly)
            if (directLinkOn) DirectLinkRow(directLinkOneTap, onOpenDirectLink)
            if (joinedSsid != null) JoinedLinkRow(joinedSsid, joinedHost, onLeaveLink)
            com.crossdrop.ui.pairing.OfflineDevicesStrip(offlineDevices, queued, sendingIds, onQueueFor)
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

    // ---- shake to connect: the device we just shook phones with gets its send sheet
    LaunchedEffect(shakeMatch) {
        val peer = shakeMatch ?: return@LaunchedEffect
        onShakeHandled()
        if (peer.endpoint == null) return@LaunchedEffect
        notOnWifi = null; vaultPeer = null
        shakenId = peer.instanceId
        pendingPeers = listOf(peer)
        pickingForPeer = peer
    }

    // ---- send sheet: photo picker or generic files, to this device and any others picked
    pickingForPeer?.let { peer ->
        val d = peer.estimatedDistanceMeters
        val others = devices.filter { it.instanceId != peer.instanceId && it.endpoint != null }
        var also by remember(peer.instanceId) { mutableStateOf(emptySet<String>()) }
        fun targets() = listOf(peer) + others.filter { it.instanceId in also }
        SendSheet(
            kicker = if (peer.instanceId == shakenId) tr("You shook phones with") else tr("Send to"),
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
            note = tr("Straight to this device, end-to-end encrypted, never through a server. You'll both see a 6-digit code to compare."),
            onDismiss = { pickingForPeer = null; pendingPeers = emptyList() },
            onPickMedia = {
                pendingPeers = targets()
                pickingForPeer = null
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = { pendingPeers = targets(); pickingForPeer = null; filePicker.launch(arrayOf("*/*")) },
            extras = {
                if (others.isNotEmpty()) AlsoSendTo(others, also) { id ->
                    also = if (id in also) also - id else also + id
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallTile(tr("Camera"), tr("Live to them"), CdIcons.Camera, Modifier.weight(1f)) {
                        val t = targets(); pickingForPeer = null; pendingPeers = emptyList(); onCamera(t)
                    }
                    SmallTile(tr("Text"), tr("Note or link"), CdIcons.Note, Modifier.weight(1f)) {
                        textFor = targets(); pickingForPeer = null; pendingPeers = emptyList()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallTile(tr("Contact"), tr("Share a card"), CdIcons.Contacts, Modifier.weight(1f)) {
                        val t = targets(); pickingForPeer = null; pendingPeers = emptyList(); onContacts(t)
                    }
                    SmallTile(tr("Switch"), tr("Move phone"), CdIcons.Swap, Modifier.weight(1f)) {
                        pickingForPeer = null; pendingPeers = emptyList(); onSwitchPhones(peer)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallTile(tr("Folder"), tr("With everything in it"), CdIcons.Folder, Modifier.weight(1f)) {
                        pendingPeers = targets(); pickingForPeer = null; folderPicker.launch(null)
                    }
                    // Apps only make sense on another Android phone: it is the one that can install them.
                    if (peer.platform == com.crossdrop.core.Platforms.ANDROID) {
                        SmallTile(tr("Apps"), tr("Installed apps"), CdIcons.Apk, Modifier.weight(1f)) {
                            val t = targets().filter { it.platform == com.crossdrop.core.Platforms.ANDROID }
                            pickingForPeer = null; pendingPeers = emptyList(); onApps(t)
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            },
        )
    }

    textFor?.let { targets ->
        val clip = LocalClipboardManager.current.getText()?.text.orEmpty().take(4000)
        com.crossdrop.ui.SendTextDialog(
            initial = clip,
            target = if (targets.size == 1) targets.first().name else trPlural(targets.size, "1 device", "{n} devices"),
        ) { text ->
            textFor = null
            if (!text.isNullOrBlank()) onSendText(targets, text)
        }
    }
    if (pickingAnyone) {
        SendSheet(
            kicker = tr("Share with"),
            title = tr("Anyone nearby"),
            icon = CdIcons.Qr,
            meta = { MetaLine(lead = null, rest = tr("No app needed — they scan a QR code")) },
            note = tr("They need to be on this Wi-Fi. The link is secret, and it stops working the moment you close the share screen."),
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

    // ---- a phone heard over Bluetooth that isn't on this Wi-Fi
    notOnWifi?.let { peer ->
        NotOnWifiSheet(
            peer = peer,
            onDismiss = { notOnWifi = null },
            onMake = { notOnWifi = null; onShowCode() },
            onJoin = { notOnWifi = null; onJoinWifi() },
        )
    }
}

/**
 * The radar hears this phone over Bluetooth, but it isn't on the same Wi-Fi, so nothing can be sent yet.
 * One of the two makes a CrossDrop Wi-Fi, the other joins it in one tap, and they link directly.
 */
@Composable
private fun NotOnWifiSheet(peer: Peer, onDismiss: () -> Unit, onMake: () -> Unit, onJoin: () -> Unit) {
    CdSheet(onDismiss) {
        SheetHeader(
            tr("Nearby, but not on your Wi-Fi"), peer.name, CdIcons.Bluetooth, iconTint = Amber,
            meta = { Text(tr("Link the two phones directly. No Wi-Fi or internet needed."), color = TextDim, fontSize = 13.sp) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile(tr("Make a Wi-Fi"), tr("They join it in one tap"), CdIcons.Wifi, true, onMake, Modifier.weight(1f))
            BigTile(tr("Join theirs"), tr("They tap Connect directly, then Make a CrossDrop Wi-Fi"), CdIcons.Scan, false, onJoin, Modifier.weight(1f))
        }
        NoteCard(tr("Only one of you makes the Wi-Fi. It ends when you stop it."))
        PillButton(tr("Cancel"), onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}

/** This phone is on another phone's private link (it scanned the code); Leave goes back to the usual Wi-Fi. */
@Composable
private fun JoinedLinkRow(ssid: String, host: String?, onLeave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Accent.copy(alpha = 0.08f))
            .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CdIcon(CdIcons.Wifi, Accent, size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                if (host != null) tr("Linked with {0}", host) else tr("On {0}", ssid),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(tr("Private link · no internet"), color = TextDim, fontSize = 12.sp)
        }
        com.crossdrop.ui.ChipButton(tr("Leave"), onLeave, height = 34.dp, fontSize = 13)
    }
}

/** Shown while this phone's private link is up, so it never runs without being visible. */
@Composable
private fun DirectLinkRow(oneTap: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Accent.copy(alpha = 0.08f))
            .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = tr("Show the network name and password"), onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CdIcon(CdIcons.Wifi, Accent, size = 18.dp)
        Text(if (oneTap) tr("Your CrossDrop Wi-Fi is on") else tr("Your private Wi-Fi is on"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(tr("Show"), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            Text(tr("Send to anyone"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("No app needed. They scan a QR code."), color = TextDim, fontSize = 12.sp)
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
            .clickable(role = Role.Button, onClickLabel = tr("Open drive"), onClick = onClick)
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
                Text(tr("PIN protected"), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            vault.writable -> Text(tr("Editable"), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            else -> Text(tr("Read-only"), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    extras: @Composable ColumnScope.() -> Unit = {},
) {
    CdSheet(onDismiss) {
        SheetHeader(kicker, title, icon, meta = meta)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile(tr("Photos & videos"), tr("Gallery, albums, Google Photos"), CdIcons.Photo, true, onPickMedia, Modifier.weight(1f))
            BigTile(tr("Files"), tr("Any type, pick several"), CdIcons.Doc, false, onPickFiles, Modifier.weight(1f))
        }
        extras()
        NoteCard(note)
        PillButton(tr("Cancel"), onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
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
            tr("Wireless drive"), vault.vaultName, CdIcons.Drive, iconTint = Amber,
            meta = {
                MetaLine(
                    lead = if (vault.needsPin) tr("PIN protected") else null,
                    rest = if (vault.writable) tr("you can add & edit") else tr("view & download"),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile(tr("Open here"), tr("Browse, stream and save — encrypted"), CdIcons.Drive, true, onMount, Modifier.weight(1f))
            BigTile(
                tr("Open in browser"), if (showQr) tr("Link copied — or scan below") else tr("Copy the link, show a QR"),
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
        NoteCard(tr("Files come straight from this drive over your Wi-Fi. Nothing is uploaded anywhere."))
        PillButton(tr("Cancel"), onDismiss, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}

/** Compact action tile under the two big ones, two to a row. */
@Composable
private fun SmallTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.heightIn(min = 64.dp).clip(shape).background(GlassMid)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(icon, Accent, size = 22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Other nearby devices as toggle chips: the same send goes to every one that's on. */
@Composable
private fun AlsoSendTo(others: List<Peer>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Also send to"), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            others.forEach { p ->
                val on = p.instanceId in selected
                Row(
                    Modifier.height(38.dp).clip(RoundedCornerShape(50))
                        .background(if (on) Accent else GlassMid)
                        .border(1.dp, if (on) Accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                        .clickable(role = Role.Checkbox, onClickLabel = if (on) tr("Remove") else tr("Add")) { onToggle(p.instanceId) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CdIcon(if (on) CdIcons.Check else deviceIconFor(p.name), if (on) OnAccent else TextPrimary, size = 15.dp)
                    Text(p.name, color = if (on) OnAccent else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}
