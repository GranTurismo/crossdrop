package com.crossdrop.ui.drive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary

/**
 * "Need help connecting?" — the foolproof, per-platform 2-step guide.
 * Three tabs: Mac (Finder), Windows (File Explorer), and Browser (any
 * device — which also covers iPhone/iPad via Safari). Every address/code is
 * one-tap copyable. Language stays physical: "address", "passcode".
 */
@Composable
fun DriveGuideScreen(
    state: VaultHostManager.State,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(GuideTab.MAC) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ back", color = TextDim) }
            Spacer(Modifier.weight(1f))
        }
        Text("Connect to your drive", color = TextPrimary, fontSize = 22.sp,
            fontWeight = FontWeight.Bold)
        Text("pick your device — two steps, no apps to install",
            color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        // tab strip
        Row(Modifier.fillMaxWidth()) {
            GuideTab.entries.forEach { t ->
                Surface(
                    onClick = { tab = t },
                    shape = RoundedCornerShape(10.dp),
                    color = if (tab == t) RippleTeal.copy(alpha = .18f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        t.label, color = if (tab == t) RippleTeal else TextDim,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        DriveCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                when (tab) {
                    GuideTab.MAC -> MacSteps(state)
                    GuideTab.WINDOWS -> WindowsSteps(state)
                    GuideTab.BROWSER -> BrowserSteps(state)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Troubleshoot()
        Spacer(Modifier.height(24.dp))
    }
}

private enum class GuideTab(val label: String) { MAC("Mac"), WINDOWS("Windows"), BROWSER("Browser") }

// ---------------------------------------------------------------- steps --

@Composable
private fun MacSteps(state: VaultHostManager.State) {
    Step(1, "Open Finder, then press ⌘ + K (\"Connect to Server\").")
    Step(2, "Paste this address and click Connect:") { CopyRow(state.url) }
    if (state.pin != null) {
        Step(3, "Choose \"Registered User\" and enter:") {
            CopyRow("drive", label = "name")
            Spacer(Modifier.height(4.dp))
            CopyRow(state.pin, label = "passcode")
        }
    }
    ResultLine("This Mac's shared folder appears as a disk on the other Mac's desktop. Drag files in or out, or scrub through 4K video right on the drive.")
}

@Composable
private fun WindowsSteps(state: VaultHostManager.State) {
    Step(1, "Open \"This PC\", click ••• (or right-click empty space) → \"Add a network location\".")
    Step(2, "Choose \"Custom network location\" and paste:") { CopyRow(state.url) }
    if (state.pin != null) {
        Step(3, "When asked for a password:") {
            CopyRow("drive", label = "name")
            Spacer(Modifier.height(4.dp))
            CopyRow(state.pin, label = "passcode")
        }
    }
    ResultLine("A new drive folder shows up under \"This PC\". It behaves like a plugged-in USB drive.")
}

@Composable
private fun BrowserSteps(state: VaultHostManager.State) {
    Step(1, "On any laptop, tablet or phone on this Wi-Fi, open any browser — Safari, Chrome, Edge.")
    Step(2, "Scan the QR on the previous screen, or type:") { CopyRow(state.url) }
    if (state.pin != null) {
        Step(3, "Enter the 4-digit passcode when asked:") { CopyRow(state.pin) }
    }
    ResultLine("Browse folders, stream video, or download any file — nothing to install. Works on iPhone and iPad too.")
}

// ----------------------------------------------------------------- bits --

@Composable
private fun Step(n: Int, text: String, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Surface(shape = RoundedCornerShape(50), color = RippleTeal.copy(alpha = .2f)) {
            Text("$n", color = RippleTeal, fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = TextPrimary, fontSize = 14.sp)
            extra?.let { Spacer(Modifier.height(6.dp)); it() }
        }
    }
}

@Composable
private fun CopyRow(text: String, label: String? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text("$label  ", color = TextDim, fontSize = 12.sp)
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = androidx.compose.ui.graphics.Color(0xFF0A0620),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassEdge),
        ) {
            Text(text, color = Phosphor, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(text)); copied = true
        }) {
            Text(if (copied) "copied ✓" else "copy",
                color = if (copied) RippleTeal else FluidCyan, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ResultLine(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = RippleTeal.copy(alpha = .1f),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Text(text, color = Phosphor, fontSize = 12.sp,
            modifier = Modifier.padding(10.dp))
    }
}

// -------------------------------------------------------- troubleshooting --

@Composable
private fun Troubleshoot() {
    Text("T R O U B L E S H O O T I N G", color = TextDim, fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    DriveCard {
        TroubleItem(
            "Devices can't see the drive?",
            "Check both devices are on the exact same Wi-Fi — not one on Guest and one on Main. " +
                "Turn off any VPN apps on both devices.")
        RowDivider()
        TroubleItem(
            "On hotel, school or public Wi-Fi?",
            "Public networks often block devices from talking to each other. " +
                "Fix: put both devices on one network you control — e.g. a phone's Personal Hotspot — and the drive works instantly at full speed.")
        RowDivider()
        TroubleItem(
            "Video buffers or previews are slow?",
            "Move the devices closer to each other or to the router — the drive runs at the speed of your Wi-Fi.")
    }
}

@Composable
private fun TroubleItem(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Surface(onClick = { open = !open }, color = androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                Text(if (open) "−" else "+", color = TextDim, fontSize = 18.sp)
            }
        }
        if (open) {
            Text(body, color = TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
}
