package com.crossdrop.ui.radar

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.crossdrop.ui.theme.AuroraBrush
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DeepSpaceSoft
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

@Composable
fun RadarScreen(
    selfName: String,
    peers: List<Peer>,
    onSendTo: (Peer, List<android.net.Uri>) -> Unit,
    onRename: (String) -> Unit,
    onPermissionsGranted: () -> Unit = {},
    selfPort: Int = 0,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var pendingPeer by remember { mutableStateOf<Peer?>(null) }
    var permsGranted by remember { mutableStateOf(hasAll(context)) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val peer = pendingPeer
        pendingPeer = null
        if (!uris.isNullOrEmpty() && peer != null) onSendTo(peer, uris)
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
        }

        if (!permsGranted) {
            PermissionBanner { permLauncher.launch(requiredPermissions().toTypedArray()) }
        }

        // ---- radar
        Box(Modifier.weight(1f)) {
            RadarCanvas(
                selfName = selfName,
                peers = peers,
                onPeerTap = { peer ->
                    if (peer.endpoint != null) {
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        pendingPeer = peer
                        filePicker.launch(arrayOf("*/*"))
                    }
                },
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
                        onClick = {
                            if (reachable) {
                                view.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                pendingPeer = peer
                                filePicker.launch(arrayOf("*/*"))
                            }
                        },
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
                                Text("send", color = RippleTeal, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
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
