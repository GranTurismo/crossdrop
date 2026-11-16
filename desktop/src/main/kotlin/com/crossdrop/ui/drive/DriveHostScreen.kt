package com.crossdrop.ui.drive

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SystemActions
import java.io.File
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CopyButton
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.QrCard
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.SegmentedTabs
import com.crossdrop.ui.ToggleRow
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.OnAccentMuted
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/**
 * "Wireless Drive" — the plain-language face of the vault + WebDAV engine.
 * Off: choose guest access, then a folder (or the built-in vault) to share.
 * On: live status, QR + address + passcode, per-platform connect steps and
 * the stop button. No "server", "mount" or "WebDAV" anywhere a user looks.
 * Desktop copy of the Android screen (native folder picker, Esc = back).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DriveHostScreen(
    state: VaultHostManager.State?,
    onStart: (writable: Boolean, usePin: Boolean, folder: File?) -> Boolean,
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

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.13f, centerX = 0.36f, centerY = 160.dp, radius = 260.dp)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BackHeader("Wireless Drive", onClose, Modifier.padding(bottom = 4.dp))
                if (state == null) StartContent(onStart) else LiveContent(state, onStop) { showGuide = true }
            }
        }
    }
}

// ----------------------------------------------------------------- off --

@Composable
private fun ColumnScope.StartContent(onStart: (Boolean, Boolean, File?) -> Boolean) {
    var writable by remember { mutableStateOf(false) }
    var usePin by remember { mutableStateOf(true) }
    var startFailed by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), radius = 30.dp, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { CdIcon(CdIcons.Drive, Accent, size = 26.dp) }
        Text("Turn this ${Platform.current.computerNoun} into a drive", style = displayStyle(24, tracking = -0.035f).copy(lineHeight = 27.sp))
        Text(
            "Other computers, tablets and phones on this network can browse, stream and download — like plugging in a USB drive.",
            color = TextDim, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }

    SectionLabel("Guest access", Modifier.padding(top = 6.dp))
    GlassCard(
        Modifier.fillMaxWidth(), radius = 26.dp, fill = GlassLow,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ToggleRow("Let guests add & edit files", "Off: guests can only view and download", writable, { writable = it })
        RowSeparator()
        ToggleRow("4-digit passcode", "Recommended on shared Wi-Fi", usePin, { usePin = it })
    }

    SectionLabel("What to share", Modifier.padding(top = 6.dp))
    ShareChoice(
        CdIcons.Folder, "Pick a folder", "Photos, movies, work files — any folder you choose",
        primary = true,
    ) { SystemActions.pickFolder()?.let { startFailed = !onStart(writable, usePin, it) } }
    ShareChoice(
        CdIcons.Drive, "Share the CrossDrop vault", "Files saved inside this app",
        primary = false,
    ) { startFailed = !onStart(writable, usePin, null) }

    if (startFailed) {
        Text(
            "Couldn't turn on the drive — that folder can't be shared (try Documents, Movies or a project folder).",
            color = DangerText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    FootNote("Everything stays on this network — nothing touches the internet.", Modifier.padding(top = 2.dp), icon = CdIcons.Shield)
}

@Composable
private fun ShareChoice(icon: ImageVector, title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth(), radius = 26.dp,
        fill = if (primary) Accent else Glass, border = if (primary) null else com.crossdrop.ui.theme.Hairline,
        glow = if (primary) Accent.copy(alpha = 0.18f) else null, glowOffsetY = 12.dp,
        contentPadding = PaddingValues(18.dp), onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (primary) OnAccent.copy(alpha = 0.12f) else Amber.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { CdIcon(icon, if (primary) OnAccent else Amber, size = 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = displayStyle(17, tracking = -0.02f, color = if (primary) OnAccent else TextPrimary))
                Text(subtitle, color = if (primary) OnAccentMuted else TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            CdIcon(CdIcons.ChevronRight, if (primary) OnAccent else TextDim, size = 20.dp)
        }
    }
}

// ------------------------------------------------------------------ on --

@Composable
private fun ColumnScope.LiveContent(state: VaultHostManager.State, onStop: () -> Unit, onShowGuide: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    // status
    GlassCard(
        Modifier.fillMaxWidth(), radius = 30.dp, border = Accent.copy(alpha = 0.35f),
        glow = Accent.copy(alpha = 0.13f), glowRadius = 50.dp,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PulseDot()
            Text("Drive is on", style = displayStyle(28, tracking = -0.04f), modifier = Modifier.weight(1f))
            val n = state.clients.size
            Text(
                if (n == 0) "No one connected" else "$n connected",
                color = if (n == 0) TextDim else Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CdIcon(CdIcons.Folder, Amber, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Sharing", color = TextDim, fontSize = 11.sp)
                Text(state.rootLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("↑ Uploaded by guests", formatBytes(state.bytesIn), Modifier.weight(1f))
            Stat("↓ Downloaded", formatBytes(state.bytesOut), Modifier.weight(1f))
        }
    }

    // QR + address + passcode
    GlassCard(
        Modifier.fillMaxWidth(), radius = 28.dp, fill = GlassLow,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCard(state.url, size = 128.dp, halo = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Address", color = TextDim, fontSize = 11.sp)
                    Text(state.url, style = monoStyle(13), maxLines = 2)
                }
                state.pin?.let { pin ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Passcode", color = TextDim, fontSize = 11.sp)
                        Text(pin, style = monoStyle(20, Accent).copy(letterSpacing = 0.3.em))
                    }
                }
                CopyButton(state.url, label = "Copy address")
            }
        }
    }

    // guest access (fixed while the drive is on)
    GlassCard(
        Modifier.fillMaxWidth(), radius = 26.dp, fill = GlassLow,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ModeRow("Guests can add & edit files", if (state.writable) "Guests can upload, rename and delete" else "Guests can only view and download", state.writable)
        RowSeparator()
        ModeRow("4-digit passcode", if (state.pin != null) "Needed to open the drive" else "Anyone on this Wi-Fi can open it", state.pin != null)
    }

    // how to connect
    GlassCard(
        Modifier.fillMaxWidth(), radius = 28.dp, fill = GlassLow,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connect from another device", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        SegmentedTabs(GUIDE_TABS, tab, { tab = it })
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { ConnectSteps(state, tab, withCopy = false) }
    }

    GlassCard(
        Modifier.fillMaxWidth().heightIn(min = 52.dp), radius = 22.dp, fill = GlassLow, border = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = onShowGuide,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CdIcon(CdIcons.Help, Amber, size = 20.dp)
            Text("Can't connect? Guest Wi-Fi, VPNs, hotels…", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
        }
    }

    FootNote(
        "Keeps serving while the window is closed — CrossDrop stays in the ${Platform.current.trayName}." +
            if (state.pin != null) " Five wrong passcodes lock a device out for 60 seconds." else "",
    )
    Spacer(Modifier.height(4.dp))
    PillButton("Stop sharing drive", onStop, Modifier.fillMaxWidth(), style = PillStyle.Danger, height = 56.dp)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = TextDim, fontSize = 11.sp)
        Text(value, style = monoStyle(16))
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, on: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        Text(
            if (on) "On" else "Off", color = if (on) OnAccent else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else GlassHigh).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PulseDot() {
    val p by rememberInfiniteTransition(label = "live").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800)), label = "p",
    )
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(12.dp).graphicsLayer {
                val s = 1f + 2f * p
                scaleX = s; scaleY = s; alpha = 0.8f * (1f - p)
            }.clip(CircleShape).background(Accent),
        )
        Box(Modifier.size(12.dp).clip(CircleShape).background(Accent))
    }
}
