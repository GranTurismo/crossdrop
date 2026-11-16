package com.crossdrop.ui.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.platform.Platform
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CopyButton
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.SegmentedTabs
import com.crossdrop.ui.StepNumber
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.MonoFont
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.monoStyle

internal val GUIDE_TABS = listOf("Mac", "Windows", "Browser")

/**
 * "Can't connect?" — the full per-platform guide with copyable address and
 * passcode, plus troubleshooting for guest Wi-Fi, VPNs and public networks.
 * Language stays physical: "address", "passcode", never "server".
 * Desktop copy of the Android screen (opens on the tab for this OS).
 */
@Composable
fun DriveGuideScreen(
    state: VaultHostManager.State,
    onClose: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(if (Platform.isWindows) 1 else 0) }
    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.13f, centerX = 0.36f, centerY = 160.dp, radius = 260.dp)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BackHeader("Connect to your drive", onClose)
                Text(
                    "Pick the device you're connecting from — no apps to install.",
                    color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
                )
                SegmentedTabs(GUIDE_TABS, tab, { tab = it })
                GlassCard(Modifier.fillMaxWidth(), radius = 28.dp, fill = GlassLow, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConnectSteps(state, tab, withCopy = true)
                }
                SectionLabel("Troubleshooting", Modifier.padding(top = 6.dp))
                GroupCard(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    TroubleItem(
                        "Devices can't see the drive?",
                        "Check both devices are on the exact same Wi-Fi — not one on Guest and one on Main. " +
                            "Turn off any VPN apps on both devices.",
                    )
                    RowSeparator()
                    TroubleItem(
                        "On hotel, school or public Wi-Fi?",
                        "Public networks often block devices from talking to each other. Turn on your phone's " +
                            "Personal Hotspot and connect both devices to it — the drive works instantly at full speed.",
                    )
                    RowSeparator()
                    TroubleItem(
                        "Video buffers or previews are slow?",
                        "Move the devices closer to each other or to the router — the drive runs at the speed of your Wi-Fi.",
                    )
                }
            }
        }
    }
}

/**
 * Numbered steps for one platform. [withCopy] adds copy buttons for the
 * address, name and passcode (the full guide); the live drive card uses the
 * compact form.
 */
@Composable
internal fun ConnectSteps(state: VaultHostManager.State, tab: Int, withCopy: Boolean) {
    val pin = state.pin
    when (tab) {
        0 -> {
            Step(1) { Text(rich("In Finder, press ", key = "⌘K"), color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
            Step(2, "Paste the address and press Connect", copy = state.url.takeIf { withCopy })
            if (pin != null) {
                Step(3, "Choose Registered User and sign in with name “drive” and the passcode. It mounts like a disk.")
                if (withCopy) CredentialRows(pin)
            } else {
                Step(3, "Choose Guest. It mounts like a disk — drag files in or out, or scrub video right on the drive.")
            }
        }
        1 -> {
            Step(1, "Open This PC, then ••• (or right-click empty space) → Add a network location")
            Step(2, "Choose a custom network location and paste the address", copy = state.url.takeIf { withCopy })
            if (pin != null) {
                Step(3, "When asked, sign in with name “drive” and the passcode. A drive folder appears under This PC.")
                if (withCopy) CredentialRows(pin)
            } else {
                Step(3, "A drive folder appears under This PC — it behaves like a plugged-in USB stick.")
            }
        }
        else -> {
            Step(1, "On any laptop, tablet or phone on this Wi-Fi, open a browser — Safari, Chrome, Edge")
            Step(2, "Scan the QR, or type the address", copy = state.url.takeIf { withCopy })
            Step(
                3,
                if (pin != null) "Enter the 4-digit passcode. Browse, stream or download — works on iPhone and iPad too."
                else "Browse folders, stream video or download any file — works on iPhone and iPad too.",
                copy = pin.takeIf { withCopy },
            )
        }
    }
}

private fun rich(text: String, key: String) = buildAnnotatedString {
    append(text)
    withStyle(SpanStyle(fontFamily = MonoFont, color = TextPrimary, background = GlassHigh)) { append(" $key ") }
}

@Composable
private fun Step(n: Int, text: String, copy: String? = null) {
    Step(n, copy) { Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
}

@Composable
private fun Step(n: Int, copy: String? = null, body: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        StepNumber(n)
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            body()
            if (copy != null) CopyField(copy)
        }
    }
}

@Composable
private fun CredentialRows(pin: String) {
    Column(Modifier.padding(start = 38.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CopyField("drive", label = "name")
        CopyField(pin, label = "passcode")
    }
}

@Composable
internal fun CopyField(value: String, label: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassLow)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) Text("$label  ", color = TextDim, fontSize = 12.sp)
        Text(value, style = monoStyle(13, Accent), modifier = Modifier.weight(1f), maxLines = 1)
        CopyButton(value)
    }
}

@Composable
private fun TroubleItem(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CdIcon(CdIcons.Help, Amber, size = 20.dp)
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            CdIcon(if (open) CdIcons.ArrowUp else CdIcons.ChevronRight, TextDim, size = 16.dp)
        }
        AnimatedVisibility(open) {
            Text(
                body, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(start = 42.dp, end = 12.dp, bottom = 14.dp),
            )
        }
    }
}
