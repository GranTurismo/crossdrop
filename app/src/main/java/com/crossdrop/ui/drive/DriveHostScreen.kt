package com.crossdrop.ui.drive

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/**
 * "Wireless Drive" host screen — the plain-language UX over the vault +
 * WebDAV engine. Two states: a start card (choose the built-in vault or a
 * picked folder, read-only/read-write, optional passcode) and the live
 * drive card (status light, shared content, QR + address, 4-digit code).
 *
 * All labels follow the physical metaphor — no "server", "mount", or
 * "WebDAV" anywhere the user can see them.
 */
@Composable
fun DriveHostScreen(
    state: VaultHostManager.State?,
    onStart: (writable: Boolean, usePin: Boolean, treeUri: Uri?) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    var showGuide by remember { mutableStateOf(false) }
    BackHandler {
        if (showGuide) showGuide = false else onClose()
    }
    if (showGuide && state != null) {
        DriveGuideScreen(state = state, onClose = { showGuide = false })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ back", color = TextDim) }
            Spacer(Modifier.weight(1f))
        }
        Text(
            "W I R E L E S S   D R I V E",
            color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(20.dp))

        if (state == null) {
            StartCard(onStart)
        } else {
            LiveCard(state, onStop, onShowGuide = { showGuide = true })
        }
        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------- start card --

@Composable
private fun StartCard(onStart: (Boolean, Boolean, Uri?) -> Unit) {
    var writable by remember { mutableStateOf(true) }
    var usePin by remember { mutableStateOf(true) }
    val ctx = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            onStart(writable, usePin, uri)
        }
    }

    Text(
        "Turn this phone into a drive",
        color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Other devices on this Wi-Fi can browse, stream\nand download — like plugging in a USB stick.",
        color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))

    DriveCard {
        ActionRow(
            title = "Share the CrossDrop vault",
            subtitle = "files saved in this app",
            accent = RippleTeal,
        ) { onStart(writable, usePin, null) }
        RowDivider()
        ActionRow(
            title = "Pick a folder to share",
            subtitle = "any folder — photos, movies, work files",
            accent = FluidCyan,
        ) { folderPicker.launch(null) }
        RowDivider()
        ToggleLine(
            title = "Let guests add & edit files",
            subtitle = "off = view and download only",
            checked = writable,
        ) { writable = it }
        RowDivider()
        ToggleLine(
            title = "4-digit passcode",
            subtitle = "recommended on shared Wi-Fi",
            checked = usePin,
        ) { usePin = it }
    }
}

// --------------------------------------------------------------- live card --

@Composable
private fun LiveCard(state: VaultHostManager.State,
                     onStop: () -> Unit,
                     onShowGuide: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // pulsing status light
    val pulse = rememberInfiniteTransition(label = "drive")
    val glow by pulse.animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = RippleTeal, modifier = Modifier
            .size(10.dp).alpha(glow)) {}
        Spacer(Modifier.width(8.dp))
        Text("Drive is on", color = RippleTeal, fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
    }
    Text("visible to devices on your Wi-Fi", color = TextDim, fontSize = 12.sp)
    Spacer(Modifier.height(18.dp))

    // shared content + mode
    DriveCard {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("📁", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.rootLabel, color = TextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (state.writable) "view · edit · upload"
                            else "view & download only")
                        if (state.pin != null) append("  ·  passcode on")
                    },
                    color = TextDim, fontSize = 12.sp,
                )
            }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val n = state.clients.size
            Text(
                if (n == 0) "no devices connected"
                else "$n device${if (n > 1) "s" else ""} connected",
                color = if (n == 0) TextDim else Phosphor, fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("↑ ${formatBytes(state.bytesOut)}  ↓ ${formatBytes(state.bytesIn)}",
                color = TextDim, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(18.dp))

    // option A — scan
    Text("F A S T E S T", color = TextDim, fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    com.crossdrop.ui.QrCard(state.url)
    Spacer(Modifier.height(6.dp))
    Text(
        "scan with any camera — files open instantly",
        color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    // option B — address + passcode
    DriveCard {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("O R   T Y P E   T H I S   A D D R E S S", color = TextDim,
                fontSize = 10.sp, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.url, color = Phosphor, fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                CopyChip(state.url)
            }
            if (state.pin != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("passcode", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.pin.toCharArray().joinToString(" "),
                        color = TextPrimary, fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    CopyChip(state.pin)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = onShowGuide) {
        Text("Need help connecting? See the 2-step guide →", color = FluidCyan,
            fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Everything stays on this Wi-Fi — nothing touches the internet.",
        color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(containerColor = DangerSoft),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Stop Sharing Drive", color = DeepSpace,
        fontWeight = FontWeight.Bold) }
}

// --------------------------------------------------------------- bits --

@Composable
fun DriveCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = DeepSpaceRaised,
        border = BorderStroke(1.dp, GlassEdge),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun ActionRow(title: String, subtitle: String,
                      accent: androidx.compose.ui.graphics.Color,
                      onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = accent, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextDim, fontSize = 12.sp)
            }
            Text("›", color = TextDim, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean,
                       onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = TextDim, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = RippleTeal.copy(alpha = .45f),
                checkedThumbColor = RippleTeal))
    }
}

@Composable
fun RowDivider() = androidx.compose.material3.HorizontalDivider(
    color = GlassEdge.copy(alpha = .5f))

@Composable
private fun CopyChip(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        copied = true
    }) {
        Text(if (copied) "copied ✓" else "copy",
            color = if (copied) RippleTeal else FluidCyan, fontSize = 13.sp)
    }
}
