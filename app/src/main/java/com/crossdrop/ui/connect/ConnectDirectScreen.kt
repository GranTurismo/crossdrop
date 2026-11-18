package com.crossdrop.ui.connect

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
 * "Connect directly": this phone makes a private Wi-Fi network and shows one QR code. The other person
 * scans it with their camera and is on the network; CrossDrop on their side then finds this phone like
 * on any Wi-Fi. [nearbyNames] are the devices the radar currently sees, used to notice who joined.
 */
@Composable
fun ConnectDirectScreen(
    link: DirectLink,
    nearbyNames: List<String>,
    onClose: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by link.state.collectAsState()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { link.start() }
    fun begin(explainFirst: Boolean = false) {
        val missing = link.missingPermission()
        // Before Android 13 the permission is "Location", a scary prompt for a Wi-Fi feature: explain first.
        if (missing != null && !explainFirst) permission.launch(missing) else link.start()
    }
    // Opening the screen is the request: start unless a link is already up.
    LaunchedEffect(Unit) {
        if (state is DirectLinkState.Off || state is DirectLinkState.Failed) begin(explainFirst = Build.VERSION.SDK_INT < 33)
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
                    DirectLinkState.Off, DirectLinkState.Starting -> Starting(onCancel = { link.stop(); onClose() })
                    is DirectLinkState.On -> Ready(s, nearbyNames, onStop = { link.stop(); onClose() }, onDone = onClose)
                    is DirectLinkState.Failed -> Problem(s.reason, onRetry = ::begin, onGuide = onGuide, context = context)
                }
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
    var showDetails by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            tr("Ask the other person to open their camera and scan this"),
            style = displayStyle(22).copy(lineHeight = 27.sp), color = TextPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        QrCard(s.qr, size = 252.dp, description = tr("Wi-Fi code for the private link. Scan it with a camera."))

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

        FootNote(
            tr("Their phone may say “no internet”. That's normal: this is a private link between your phones. Your phone may also leave its own Wi-Fi while this is on."),
            icon = CdIcons.Wifi,
        )

        GlassCard(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Can't scan the code?"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr("On a Mac, PC or a phone without a camera, join the network by name instead."),
                color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
            )
            ChipButton(if (showDetails) tr("Hide the name and password") else tr("Show the name and password"), { showDetails = !showDetails })
            AnimatedVisibility(showDetails) {
                Column {
                    Detail(tr("Network"), s.ssid)
                    val password = s.password
                    if (!password.isNullOrEmpty()) {
                        RowSeparator()
                        Detail(tr("Password"), password)
                    }
                }
            }
        }

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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextDim, fontSize = 11.sp)
            Text(value, style = monoStyle(15, TextPrimary))
        }
        CopyButton(value)
    }
}

@Composable
private fun Problem(reason: DirectLinkFailure, onRetry: () -> Unit, onGuide: () -> Unit, context: android.content.Context) {
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
        PillButton(tr("Use a hotspot instead"), onGuide, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}
