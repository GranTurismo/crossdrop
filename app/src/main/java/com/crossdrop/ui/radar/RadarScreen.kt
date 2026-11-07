package com.crossdrop.ui.radar

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.Peer
import com.crossdrop.ui.DeviceNameDialog
import com.crossdrop.ui.IconDoc
import com.crossdrop.ui.IconPhoto
import com.crossdrop.ui.IconQr
import com.crossdrop.ui.IconVault
import com.crossdrop.ui.theme.AuroraBrush
import com.crossdrop.ui.theme.AuroraViolet
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary

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

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val context = LocalContext.current
    val view = LocalView.current
    var pendingPeer by remember { mutableStateOf<Peer?>(null) }
    var pendingAnyone by remember { mutableStateOf(false) }
    var pickingForPeer by remember { mutableStateOf<Peer?>(null) }
    var pickingAnyone by remember { mutableStateOf(false) }
    var vaultPeer by remember { mutableStateOf<Peer?>(null) }
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

    /** Peer tap → send chooser, or the vault mount sheet for beacons. */
    fun startSendFlow(peer: Peer) {
        if (peer.endpoint == null) return
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (peer.vault != null) { vaultPeer = peer; return }
        pendingPeer = peer
        pickingForPeer = peer
    }

    /** "Send to anyone" → same chooser, but the result feeds the QR share. */
    fun startAnyoneFlow() {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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

    Column(Modifier.fillMaxSize()) {
        // ---- top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "CrossDrop",
                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    style = androidx.compose.ui.text.TextStyle(brush = AuroraBrush),
                    letterSpacing = 0.5.sp,
                )
                var renaming by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DeepSpaceSoft,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassEdge),
                    ) {
                        Row(
                            Modifier.clickable { renaming = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("●", color = RippleTeal, fontSize = 7.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                selfName + if (selfPort > 0) "  :$selfPort" else "",
                                color = TextDim, fontSize = 11.sp,
                            )
                        }
                    }
                }
                if (renaming) {
                    DeviceNameDialog(current = selfName, onDone = { new ->
                        renaming = false
                        new?.let(onRename)
                    })
                }
            }

            // ---- "send to anyone" — zero-install receiver via QR
            Surface(
                shape = RoundedCornerShape(50),
                color = DeepSpaceSoft,
                border = androidx.compose.foundation.BorderStroke(1.dp, RippleTeal.copy(alpha = 0.4f)),
                onClick = ::startAnyoneFlow,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconQr(RippleTeal, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("to anyone", color = TextPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }

        if (!permsGranted) {
            PermissionBanner { permLauncher.launch(requiredPermissions().toTypedArray()) }
        }

        // ---- radar
        Box(Modifier.weight(1f)) {
            RadarCanvas(
                selfName = selfName,
                peers = peers,
                onPeerTap = ::startSendFlow,
            )
            if (peers.isEmpty()) {
                Text(
                    "Listening for nearby devices…",
                    color = TextDim, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                )
            } else {
                Text(
                    "${peers.size} device${if (peers.size == 1) "" else "s"} nearby — tap to send",
                    color = Phosphor, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                )
            }
        }

        // ---- peer strip / hint
        if (peers.isEmpty()) {
            Text(
                "Tap a device to send files",
                color = TextDim, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp),
            )
        } else {
            Row(
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                peers.take(3).forEach { peer ->
                    val reachable = peer.endpoint != null
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DeepSpaceRaised,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (reachable) RippleTeal.copy(alpha = 0.5f) else GlassEdge),
                        onClick = { if (reachable) startSendFlow(peer) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "●", color = if (reachable) RippleTeal else TextDim,
                                fontSize = 8.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                peer.name, color = TextPrimary, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                            )
                            if (reachable) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (peer.vault != null) "vault" else "send",
                                    color = if (peer.vault != null) AuroraViolet else RippleTeal,
                                    fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- send chooser: photo picker or generic files
    pickingForPeer?.let { peer ->
        SendChooserSheet(
            title = "Send to ${peer.name}",
            onDismiss = { pickingForPeer = null; pendingPeer = null },
            onPickMedia = {
                pickingForPeer = null
                mediaPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = { pickingForPeer = null; filePicker.launch(arrayOf("*/*")) },
        )
    }
    if (pickingAnyone) {
        SendChooserSheet(
            title = "Send to anyone",
            subtitle = "they'll scan a QR — no app needed",
            onDismiss = { pickingAnyone = false; pendingAnyone = false },
            onPickMedia = {
                pickingAnyone = false
                mediaPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = { pickingAnyone = false; filePicker.launch(arrayOf("*/*")) },
        )
    }

    // ---- vault beacon tap → mount sheet
    vaultPeer?.let { peer ->
        VaultMountSheet(
            peer = peer,
            onDismiss = { vaultPeer = null },
            onMount = { vaultPeer = null; onMountVault(peer) },
        )
    }
}

/**
 * Orbital-beacon action sheet: mount in the native explorer, or hand the
 * browser URL to anyone nearby (QR + copyable link).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultMountSheet(
    peer: Peer,
    onDismiss: () -> Unit,
    onMount: () -> Unit,
) {
    val context = LocalContext.current
    val vault = peer.vault!!
    val url = peer.endpoint?.let { "http://${it.host}:${it.port}/" } ?: ""
    var showQr by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpaceSoft) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(vault.vaultName, color = TextPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vault.writable) "read · write" else "read-only",
                    color = if (vault.writable) Phosphor else TextDim, fontSize = 12.sp)
                if (vault.needsPin) {
                    Text("  ·  🔒 PIN protected", color = TextDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            PickerOption(
                title = "Mount vault",
                subtitle = "browse and pull files in-app (encrypted)",
                onClick = onMount,
            ) { IconVault(RippleTeal, size = 26.dp) }
            Spacer(Modifier.height(8.dp))
            PickerOption(
                title = "Open in browser",
                subtitle = "share the link — no app needed on their end",
                onClick = {
                    url.let {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("vault", it))
                    }
                    showQr = !showQr
                },
            ) { IconQr(FluidCyan, size = 24.dp) }
            if (showQr && url.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                com.crossdrop.ui.QrCard(url, size = 190.dp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(url, color = TextDim, fontSize = 11.sp)
                }
                Text("link copied — or let them scan", color = TextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendChooserSheet(
    title: String,
    subtitle: String = "pick what to share",
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpaceSoft) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            PickerOption(
                title = "Photos & videos",
                subtitle = "gallery, camera roll, Google Photos",
                onClick = onPickMedia,
            ) { IconPhoto(RippleTeal, size = 26.dp) }
            Spacer(Modifier.height(8.dp))
            PickerOption(
                title = "Files",
                subtitle = "documents, archives, everything else",
                onClick = onPickFiles,
            ) { IconDoc(FluidCyan, size = 24.dp) }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DeepSpaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassEdge),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextDim, fontSize = 12.sp)
            }
            Text("›", color = TextDim, fontSize = 18.sp)
        }
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        color = DeepSpaceSoft,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Bluetooth & notifications are off — nearby devices may be hidden",
                color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGrant) { Text("Grant", color = RippleTeal) }
        }
    }
}
