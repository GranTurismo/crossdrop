package com.crossdrop.ui.connect

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.connect.DirectLinkFailure
import com.crossdrop.core.connect.DirectLinkState
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.DirectLink
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.CopyButton
import com.crossdrop.ui.Dot
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.QrCard
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle

/**
 * "Connect directly": one device makes a Wi-Fi network, the other joins it. Making one starts the CrossDrop Wi-Fi on
 * this phone ([DirectLink]); the other device joins it in one tap with CrossDrop ([onJoin] on their side), or by
 * typing the name and password shown here (a laptop), or by scanning the code with a camera. [onScan] is the older
 * code route, for a phone that could only make a network of its own naming. [nearbyNames] are the devices the radar
 * currently sees, used to notice who joined.
 */
@Composable
fun ConnectDirectScreen(
    link: DirectLink,
    nearbyNames: List<String>,
    onClose: () -> Unit,
    onGuide: () -> Unit,
    onScan: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
    /** Skip the make/join question and make the network right away. */
    showNow: Boolean = false,
    /** Runs before this phone makes a network: leave one we joined (a phone can't be both ends of a link). */
    onBeforeMake: () -> Unit = {},
) {
    val state by link.state.collectAsState()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { link.start() }
    fun begin(explainFirst: Boolean = false) {
        onBeforeMake()
        val missing = link.missingPermission()
        // Before Android 13 the permission is "Location", a scary prompt for a Wi-Fi feature: explain first.
        if (missing != null && !explainFirst) permission.launch(missing) else link.start()
    }
    // Until someone picks "Make a CrossDrop Wi-Fi", nothing is started: the device that joins must not make one too.
    var chosen by remember { mutableStateOf(state !is DirectLinkState.Off) }
    LaunchedEffect(Unit) {
        if (showNow && state is DirectLinkState.Off) { chosen = true; begin(explainFirst = Build.VERSION.SDK_INT < 33) }
    }
    // The network ended while this screen is open (Wi-Fi switched off, the 30-minute stop): back to the choice,
    // not a "Setting up…" spinner that never ends.
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is DirectLinkState.Off) wasActive = true
        else if (wasActive) { wasActive = false; chosen = false }
    }

    Box(modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.18f, centerX = 0.5f, centerY = 260.dp, radius = 280.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackHeader(tr("Connect directly"), onClose)
                when (val s = state) {
                    DirectLinkState.Off -> if (!chosen) Choice(
                        onMake = { chosen = true; begin(explainFirst = Build.VERSION.SDK_INT < 33) },
                        onJoin = onJoin,
                        onScan = onScan,
                    ) else Starting(onCancel = { link.stop(); onClose() })
                    DirectLinkState.Starting -> Starting(onCancel = { link.stop(); onClose() })
                    is DirectLinkState.On -> Ready(s, nearbyNames, onStop = { link.stop(); onClose() }, onDone = onClose)
                    is DirectLinkState.Failed -> Problem(s.reason, onRetry = ::begin, onGuide = onGuide, onJoin = onJoin, context = context)
                }
            }
        }
    }
}

/** Which side are you? One device makes the Wi-Fi, the other joins it. */
@Composable
private fun Choice(onMake: () -> Unit, onJoin: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            tr("One device makes the Wi-Fi, the other joins it"),
            style = displayStyle(22).copy(lineHeight = 27.sp), color = TextPrimary,
        )
        Text(
            tr("No router or internet needed. It ends when you stop it."),
            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
        )
        ChoiceCard(
            CdIcons.Wifi, tr("Make a CrossDrop Wi-Fi"),
            tr("This phone makes the network. The other device joins it in one tap, or a laptop types a short password."),
            onMake,
        )
        ChoiceCard(
            CdIcons.Scan, tr("Join a CrossDrop Wi-Fi"),
            tr("The other phone already made one. Join it in one tap, no code needed."),
            onJoin,
        )
        ChipButton(tr("Scan a code instead"), onScan)
        FootNote(
            tr("Nothing goes over the internet. The device that joins leaves its usual Wi-Fi while you're linked."),
            icon = CdIcons.Wifi,
        )
    }
}

@Composable
private fun ChoiceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth(), onClick = onClick, radius = 24.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            com.crossdrop.ui.IconTile(icon, Accent, size = 42.dp, radius = 14.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun Starting(onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Text(
            tr("Setting up a private link…"), color = TextSecondary, fontSize = 15.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        ChipButton(tr("Cancel"), onCancel)
    }
}

@Composable
private fun Ready(s: DirectLinkState.On, nearbyNames: List<String>, onStop: () -> Unit, onDone: () -> Unit) {
    // Who was already on the radar when the link came up doesn't count as "joined".
    val before = remember(s.ssid) { nearbyNames.toSet() }
    val joined = nearbyNames.firstOrNull { it !in before }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            if (s.oneTap) tr("Your CrossDrop Wi-Fi is on") else tr("Your private Wi-Fi is on"),
            style = displayStyle(22).copy(lineHeight = 27.sp), color = TextPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (s.oneTap) tr("With CrossDrop on the other device: Connect directly, then Join a CrossDrop Wi-Fi. Without it: join this network and type the password.")
            else tr("On the other device, join this network and type the password, or scan the code."),
            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth(),
        )

        // The name and password, readable from across a table: a laptop can't scan, so it types them.
        GlassCard(
            Modifier.fillMaxWidth(), radius = 24.dp, fill = Accent.copy(alpha = 0.06f), border = Accent.copy(alpha = 0.28f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Detail(tr("Network"), s.ssid)
            val password = s.password
            if (!password.isNullOrEmpty()) {
                RowSeparator()
                Detail(tr("Password"), password)
            }
        }
        if (s.wifiWasOff) {
            FootNote(tr("Turn on Wi-Fi on this phone and start again, so others can join in one tap."), icon = CdIcons.Wifi)
        }

        // live status
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (joined != null) {
                Dot(Accent)
                Text(tr("{0} is connected", joined), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Dot(Amber)
                Text(tr("Waiting for the other device…"), color = TextSecondary, fontSize = 15.sp)
            }
        }
        if (joined != null) PillButton(tr("Choose what to send"), onDone, Modifier.fillMaxWidth(), glow = true)

        Text(tr("Or scan it with a camera"), color = TextDim, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        QrCard(s.qr, size = 200.dp, description = tr("Wi-Fi code for the private link. Scan it with a camera."))

        FootNote(
            tr("The other device may say “no internet”. That's normal: this network only links your devices."),
            icon = CdIcons.Wifi,
        )

        GlassCard(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("They don't have CrossDrop?"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Once they're connected, go back and tap Send to anyone. They scan a second code and download the files in their browser."),
                color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }

        PillButton(tr("Stop direct connection"), onStop, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = TextDim, fontSize = 12.sp)
            Text(value, style = monoStyle(20, TextPrimary))
        }
        CopyButton(value)
    }
}

@Composable
private fun Problem(reason: DirectLinkFailure, onRetry: () -> Unit, onGuide: () -> Unit, onJoin: () -> Unit, context: android.content.Context) {
    val (title, body) = when (reason) {
        DirectLinkFailure.PERMISSION ->
            if (Build.VERSION.SDK_INT >= 33) {
                tr("CrossDrop needs your permission") to
                    tr("Android asks before an app can create a Wi-Fi link. Tap Allow. If nothing appears, open the app settings and allow Nearby devices.")
            } else {
                tr("Android needs Location for this") to
                    tr("Android files creating a Wi-Fi link under “Location”. CrossDrop doesn't read or store where you are. Tap Allow. If nothing appears, open the app settings and allow Location.")
            }
        DirectLinkFailure.LOCATION_OFF ->
            tr("Turn on Location") to tr("Android only lets apps create a Wi-Fi link while Location is on. CrossDrop doesn't use your position.")
        DirectLinkFailure.BUSY ->
            tr("Your phone's hotspot is busy") to tr("Turn off the hotspot or Wi-Fi sharing in Settings, then try again.")
        DirectLinkFailure.TIMEOUT ->
            tr("This phone is taking too long") to tr("It didn't answer. Try again, or use the hotspot steps.")
        DirectLinkFailure.NO_CHANNEL ->
            tr("No free Wi-Fi channel right now") to tr("Try again in a moment, or move away from other Wi-Fi equipment.")
        DirectLinkFailure.NOT_ALLOWED ->
            tr("This phone doesn't allow it") to tr("Your phone's maker or your organisation blocks this. The hotspot steps still work.")
        DirectLinkFailure.OTHER ->
            tr("Couldn't create the link") to tr("This phone didn't let us. The hotspot steps still work.")
    }
    GlassCard(
        radius = 26.dp, fill = Amber.copy(alpha = 0.06f), border = Amber.copy(alpha = 0.28f),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(4.dp))
        if (reason == DirectLinkFailure.LOCATION_OFF) {
            PillButton(tr("Open Location settings"), {
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.fillMaxWidth())
        }
        val primary = reason != DirectLinkFailure.LOCATION_OFF
        if (reason != DirectLinkFailure.NOT_ALLOWED && reason != DirectLinkFailure.OTHER) {
            PillButton(
                if (reason == DirectLinkFailure.PERMISSION) tr("Allow") else tr("Try again"), onRetry, Modifier.fillMaxWidth(),
                style = if (primary) PillStyle.Accent else PillStyle.Glass,
            )
        }
        if (reason == DirectLinkFailure.PERMISSION) {
            PillButton(tr("Open app settings"), {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }, Modifier.fillMaxWidth(), style = PillStyle.Glass)
        }
        PillButton(tr("Join the other phone's Wi-Fi instead"), onJoin, Modifier.fillMaxWidth(), style = PillStyle.Glass)
        PillButton(tr("Use a hotspot instead"), onGuide, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}
