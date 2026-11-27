package com.crossdrop.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.connect.ConnectAdvice
import com.crossdrop.core.connect.ConnectHint
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.IconTile
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.StepNumber
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.monoStyle
import kotlinx.coroutines.delay

/**
 * Decides, from what the radar can measure, whether to offer help (see [ConnectAdvice]). The clock
 * restarts whenever a device appears or disappears or the network changes, so a brief Wi-Fi blip
 * never flashes the card.
 */
@Composable
fun rememberConnectHint(hasNetwork: Boolean, deviceCount: Int, directLinkOn: Boolean = false): ConnectHint {
    var emptyFor by remember { mutableLongStateOf(0L) }
    LaunchedEffect(deviceCount, hasNetwork) {
        emptyFor = 0L
        if (deviceCount == 0) while (true) {
            delay(500)
            emptyFor += 500
        }
    }
    return ConnectAdvice.hint(hasNetwork, deviceCount, emptyFor, directLinkOn)
}

/**
 * "Can't see the other device?" — shown on the radar when [hint] says so. [onPrimary] is the one-tap
 * fix where the platform has one (Android: "Connect directly"); [onGuide] opens the plain how-to.
 */
@Composable
fun ConnectHelpCard(
    hint: ConnectHint,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String = tr("Connect directly"),
    onPrimary: (() -> Unit)? = null,
    /** What to say when there is no network at all, where the default (phones linking) doesn't fit (a computer). */
    noNetworkBody: String? = null,
) {
    if (hint == ConnectHint.NONE) return
    val direct = onPrimary != null
    val title = if (hint == ConnectHint.NO_NETWORK) tr("You're not on Wi-Fi") else tr("Can't see the other device?")
    // Short on purpose: the card shares a small phone's screen with the radar.
    val body = when (hint) {
        ConnectHint.NO_NETWORK -> noNetworkBody ?: if (direct) tr("Connect your phones directly. No Wi-Fi needed.")
            else tr("Join the same Wi-Fi, or turn on a hotspot on one device.")
        else -> tr("Is CrossDrop open there, on the same Wi-Fi? Guest and hotel Wi-Fi can hide devices.")
    }
    GlassCard(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        radius = 26.dp, fill = Amber.copy(alpha = 0.06f), border = Amber.copy(alpha = 0.28f),
        contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(CdIcons.Wifi, Amber, size = 36.dp, radius = 12.dp, iconSize = 18.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onPrimary != null) ChipButton(primaryLabel, onPrimary, fg = OnAccent, bg = Accent, height = 38.dp, fontSize = 14)
            ChipButton(tr("Show me how"), onGuide, height = 38.dp, fontSize = 14)
        }
    }
}

/**
 * Plain-words guide to getting two devices on one network. [onConnectDirectly] adds the option to make a network
 * on devices that can (Android); [onJoinWifi] the one-tap join of a phone's CrossDrop Wi-Fi where CrossDrop can join
 * networks itself (Android, the desktop app). The network's name and password are always shown, for typing.
 * Platform-free: shared with the desktop app.
 */
@Composable
fun ConnectGuideScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onConnectDirectly: (() -> Unit)? = null,
    onJoinWifi: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.16f, centerX = 0.3f, centerY = 120.dp, radius = 240.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BackHeader(tr("Connect two devices"), onClose)
                Text(
                    tr("CrossDrop sends straight between devices on the same network, without the internet. Pick what fits:"),
                    color = TextDim, fontSize = 14.sp, lineHeight = 20.sp,
                )

                if (onConnectDirectly != null) {
                    GlassCard(
                        radius = 26.dp, fill = Accent.copy(alpha = 0.07f), border = Accent.copy(alpha = 0.3f),
                        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(tr("Easiest: connect directly"), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("One phone makes a CrossDrop Wi-Fi. The other device joins it in one tap with CrossDrop, or a laptop types a short password. No router or internet needed."),
                            color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                        )
                        PillButton(tr("Connect directly"), onConnectDirectly, Modifier.fillMaxWidth(), glow = true)
                    }
                }

                SectionLabel(tr("Join a phone's CrossDrop Wi-Fi"), Modifier.padding(top = 4.dp))
                CrossDropWifiCard(onJoinWifi)

                SectionLabel(tr("Or use a hotspot"), Modifier.padding(top = 4.dp))
                GroupCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    GuideStep(1, tr("Turn on a hotspot on one phone"), tr("Settings → Hotspot (Personal Hotspot on iPhone)."))
                    RowSeparator()
                    GuideStep(2, tr("Join it from the other device"), tr("Pick that hotspot's name in the other device's Wi-Fi list."))
                    RowSeparator()
                    GuideStep(3, tr("Open CrossDrop on both"), tr("They find each other in a few seconds. Transfers don't use mobile data."))
                }

                SectionLabel(tr("The other person has no app?"), Modifier.padding(top = 4.dp))
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    Text(
                        tr("Once you're on the same network, tap Send to anyone on the radar. They scan the code and download the files in their browser. Nothing to install."),
                        color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }

                SectionLabel(tr("Still stuck?"), Modifier.padding(top = 4.dp))
                GroupCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Tip(tr("Turn Wi-Fi off and on, on both devices."))
                    RowSeparator()
                    Tip(tr("Turn off any VPN on both devices."))
                    RowSeparator()
                    Tip(tr("Close CrossDrop and open it again on both."))
                }
            }
        }
    }
}

/**
 * The CrossDrop Wi-Fi an Android phone makes (Connect directly, then Make a CrossDrop Wi-Fi): one tap where this
 * device can join it ([onJoin]), and its name and password for typing anywhere else.
 */
@Composable
fun CrossDropWifiCard(onJoin: (() -> Unit)?, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("An Android phone makes it: Connect directly, then Make a CrossDrop Wi-Fi."),
            color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
        )
        if (onJoin != null) PillButton(tr("Join a CrossDrop Wi-Fi"), onJoin, Modifier.fillMaxWidth())
        Text(
            if (onJoin != null) tr("Any other laptop or phone can join it from its Wi-Fi list with this name and password:")
            else tr("Join it from this device's Wi-Fi list with this name and password:"),
            color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
        )
        WifiDetail(tr("Network"), DirectWifi.SSID)
        RowSeparator()
        WifiDetail(tr("Password"), DirectWifi.PASSWORD)
    }
}

@Composable
private fun WifiDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.widthIn(min = 80.dp))
        Text(value, style = monoStyle(17, TextPrimary))
    }
}

@Composable
private fun GuideStep(n: Int, title: String, body: String) {
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepNumber(n)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Tip(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
}
