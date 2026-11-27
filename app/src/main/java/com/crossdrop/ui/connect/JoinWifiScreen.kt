package com.crossdrop.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.WifiJoin
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.Dot
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle

/**
 * "Join a CrossDrop Wi-Fi": one tap, no code. Joins the network another phone made with "Make a CrossDrop Wi-Fi"
 * ([DirectWifi]: the same name and password everywhere) and connects to that phone. Android asks the person once
 * ("Connect to device"). Closes itself once the other phone answered; [onScan] is the way out when the other phone
 * could only make a network of its own naming (it shows a code then).
 */
@Composable
fun JoinWifiScreen(join: WifiJoin, onClose: () -> Unit, onJoined: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val state by join.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { join.joinCrossDropWifi() }
    fun begin() {
        val missing = join.missingPermission()
        if (missing != null) permission.launch(missing) else join.joinCrossDropWifi()
    }
    // Opening the screen is the tap: start right away (unless we're already on it).
    LaunchedEffect(Unit) {
        val s = join.state.value
        val already = (s is WifiJoin.State.Joined || s is WifiJoin.State.Connecting || s is WifiJoin.State.Joining) &&
            DirectWifi.isCrossDropWifi(ssidOf(s))
        if (!already) begin()
    }
    // The other phone answered our join: back to the radar, where it now shows. Only a Joined that follows this
    // screen's own joining counts (an older link to another network must not close it at once).
    var sawJoining by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val s = state
        if ((s is WifiJoin.State.Joining || s is WifiJoin.State.Connecting) && DirectWifi.isCrossDropWifi(ssidOf(s))) sawJoining = true
        if (sawJoining && s is WifiJoin.State.Joined && s.hostName != null && DirectWifi.isCrossDropWifi(s.ssid)) onJoined()
    }

    Box(modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.18f, centerX = 0.5f, centerY = 220.dp, radius = 260.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackHeader(tr("Join a CrossDrop Wi-Fi"), { join.dismiss(); onClose() })
                Text(
                    tr("Joining the Wi-Fi the other phone made"),
                    style = displayStyle(22).copy(lineHeight = 27.sp), color = TextPrimary,
                )
                GlassCard(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (val s = state) {
                        is WifiJoin.State.Joining -> JoinStatus(
                            busy = true,
                            title = tr("Looking for {0}…", DirectWifi.SSID),
                            body = tr("Android asks you to confirm. Pick {0} and tap Connect.", DirectWifi.SSID),
                        )
                        is WifiJoin.State.Connecting -> JoinStatus(
                            busy = true,
                            title = tr("Connected to {0}", s.ssid),
                            body = tr("Reaching the other phone…"),
                        )
                        is WifiJoin.State.Joined -> JoinStatus(
                            busy = s.hostName == null,
                            title = tr("Connected to {0}", s.ssid),
                            body = if (s.hostName == null) tr("Waiting for the other phone to show up on the radar…") else "",
                        )
                        is WifiJoin.State.Failed -> Failure(
                            s, onRetry = ::begin, onScan = onScan, onAllow = ::begin,
                            onSettings = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", context.packageName, null),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                        WifiJoin.State.Idle -> JoinStatus(busy = true, title = tr("Looking for {0}…", DirectWifi.SSID), body = "")
                    }
                    if (state is WifiJoin.State.Joining || state is WifiJoin.State.Connecting) {
                        PillButton(tr("Cancel"), { join.leave(); onClose() }, Modifier.fillMaxWidth(), style = PillStyle.Glass)
                    }
                    if (state is WifiJoin.State.Joined) {
                        PillButton(tr("Go to the radar"), onJoined, Modifier.fillMaxWidth(), style = PillStyle.Glass)
                    }
                }
                FootNote(
                    tr("The other phone needs CrossDrop open on Connect directly, with its CrossDrop Wi-Fi on. Keep the two close together."),
                    icon = CdIcons.Wifi,
                )
            }
        }
    }
}

@Composable
private fun Failure(s: WifiJoin.State.Failed, onRetry: () -> Unit, onScan: () -> Unit, onAllow: () -> Unit, onSettings: () -> Unit) {
    val (title, body) = if (s.reason == WifiJoin.Failure.NOT_JOINED) {
        tr("Couldn't find a CrossDrop Wi-Fi") to
            tr("On the other phone, tap Connect directly, then Make a CrossDrop Wi-Fi. If its screen shows a different network name, scan its code instead.")
    } else joinFailureText(s)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(Amber)
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    Text(body, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    if (s.reason == WifiJoin.Failure.PERMISSION) {
        PillButton(tr("Allow"), onAllow, Modifier.fillMaxWidth())
        // Refused for good, Android no longer asks: the switch is in the app's settings.
        PillButton(tr("Open app settings"), onSettings, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    } else {
        PillButton(tr("Try again"), onRetry, Modifier.fillMaxWidth())
    }
    PillButton(tr("Scan a code instead"), onScan, Modifier.fillMaxWidth(), style = PillStyle.Glass)
}

private fun ssidOf(s: WifiJoin.State): String? = when (s) {
    is WifiJoin.State.Joining -> s.ssid
    is WifiJoin.State.Connecting -> s.ssid
    is WifiJoin.State.Joined -> s.ssid
    is WifiJoin.State.Failed -> s.ssid
    WifiJoin.State.Idle -> null
}
