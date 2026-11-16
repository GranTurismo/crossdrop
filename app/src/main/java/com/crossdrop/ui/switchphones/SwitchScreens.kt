package com.crossdrop.ui.switchphones

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.crossdrop.net.SessionState
import com.crossdrop.switchphones.SwitchImporter
import com.crossdrop.switchphones.SwitchSender
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CdSwitch
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes
import kotlinx.coroutines.launch

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun granted(context: Context, vararg p: String) =
    p.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * "Switch phones" on the old phone: pick what moves, then watch it go. The target device was
 * chosen on the radar.
 */
@Composable
fun SwitchSendScreen(
    peerName: String,
    sender: SwitchSender,
    state: SwitchSender.State?,
    onStart: (photos: Boolean, videos: Boolean, contacts: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    if (state != null) {
        SwitchSendProgress(state, onCancel = sender::cancel, onResume = { sender.resume() }, onClose = onClose)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<SwitchSender.Plan?>(null) }
    var photos by remember { mutableStateOf(true) }
    var videos by remember { mutableStateOf(true) }
    var contacts by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    LaunchedEffect(Unit) {
        val need = (mediaPermissions() + Manifest.permission.READ_CONTACTS).filter { !granted(context, it) }
        if (need.isNotEmpty()) launcher.launch(need.toTypedArray())
    }
    LaunchedEffect(tick) {
        plan = sender.plan(includeContacts = granted(context, Manifest.permission.READ_CONTACTS))
    }
    val p = plan
    val mediaOk = granted(context, *mediaPermissions()) || p?.partialMedia == true
    val contactsOk = granted(context, Manifest.permission.READ_CONTACTS)

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 140.dp, radius = 240.dp)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            BackHeader("Switch phones", onClose)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append("Move everything to ")
                        withStyle(SpanStyle(color = Accent)) { append(peerName) }
                    },
                    style = displayStyle(28, tracking = -0.03f).copy(lineHeight = 32.sp),
                )
                Text(
                    "Straight over Wi-Fi, encrypted, nothing uploaded. Works between Android and iPhone in both directions.",
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                )
                GroupCard {
                    PickRow(
                        CdIcons.Photo, "Photos",
                        if (!mediaOk) "Needs photo access" else p?.let { "%,d · %s".format(it.photos, formatBytes(it.photoBytes)) } ?: "Counting…",
                        photos && mediaOk, mediaOk,
                    ) { photos = it }
                    RowSeparator()
                    PickRow(
                        CdIcons.Video, "Videos",
                        if (!mediaOk) "Needs photo access" else p?.let { "%,d · %s".format(it.videos, formatBytes(it.videoBytes)) } ?: "Counting…",
                        videos && mediaOk, mediaOk,
                    ) { videos = it }
                    RowSeparator()
                    PickRow(
                        CdIcons.Contacts, "Contacts",
                        if (!contactsOk) "Needs contacts access" else p?.let { "%,d".format(it.contacts) } ?: "Counting…",
                        contacts && contactsOk, contactsOk,
                    ) { contacts = it }
                }
                if (!mediaOk || !contactsOk) {
                    PillButton(
                        "Allow access", {
                            launcher.launch(mediaPermissions() + Manifest.permission.READ_CONTACTS)
                        },
                        Modifier.fillMaxWidth(), style = PillStyle.Glass, icon = CdIcons.Lock,
                    )
                }
                if (p?.partialMedia == true) {
                    FootNote("Only the photos you selected are visible to CrossDrop. Allow all photos to move your whole gallery.", icon = CdIcons.Help)
                }
                FootNote(
                    "Messages, apps and app data can't be moved by any app. Keep both screens on and on the same Wi-Fi " +
                        "until it's done. If the connection drops, start again — it continues where it stopped.",
                    icon = CdIcons.Shield,
                )
            }
            val any = (photos && mediaOk && (p?.photos ?: 0) > 0) || (videos && mediaOk && (p?.videos ?: 0) > 0) ||
                (contacts && contactsOk && (p?.contacts ?: 0) > 0)
            PillButton(
                "Start moving", { scope.launch { onStart(photos && mediaOk, videos && mediaOk, contacts && contactsOk) } },
                Modifier.fillMaxWidth().padding(vertical = 18.dp), enabled = any && p != null, icon = CdIcons.Swap, glow = true,
            )
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            CdIcon(icon, Accent, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = if (enabled) TextDim else Amber, fontSize = 12.sp)
        }
        CdSwitch(checked, if (enabled) onChange else null)
    }
}

@Composable
private fun SwitchSendProgress(s: SwitchSender.State, onCancel: () -> Unit, onResume: () -> Unit, onClose: () -> Unit) {
    val failed = s.phase == SwitchSender.Phase.FAILED || s.phase == SwitchSender.Phase.DECLINED || s.phase == SwitchSender.Phase.CANCELLED
    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 260.dp, radius = 240.dp, color = if (failed) DangerText else Accent)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when (s.phase) {
                    SwitchSender.Phase.PREPARING -> "Getting your library ready…"
                    SwitchSender.Phase.WAITING -> "Waiting for ${s.peerName} to accept"
                    SwitchSender.Phase.MOVING -> "Moving to ${s.peerName}"
                    SwitchSender.Phase.DONE -> "All moved to ${s.peerName}"
                    SwitchSender.Phase.FAILED -> "Paused — connection lost"
                    SwitchSender.Phase.DECLINED -> "${s.peerName} declined"
                    SwitchSender.Phase.CANCELLED -> "Stopped"
                },
                style = displayStyle(22), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Ring(if (s.phase == SwitchSender.Phase.DONE) 1f else s.fraction, done = s.phase == SwitchSender.Phase.DONE, failed = failed)
            Text(
                listOfNotNull(
                    "%,d of %,d items".format(s.itemsDone, s.itemsTotal),
                    "${formatBytes(s.bytesDone)} / ${formatBytes(s.bytesTotal)}",
                    s.bytesPerSec.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
                ).joinToString(" · "),
                style = monoStyle(13, TextDim), textAlign = TextAlign.Center,
            )
            if (s.batches > 1 && !s.finished) Text("Batch ${s.batch + 1} of ${s.batches}", color = TextDim, fontSize = 12.sp)
            s.error?.let { Text(it, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s.phase) {
                    SwitchSender.Phase.DONE, SwitchSender.Phase.DECLINED, SwitchSender.Phase.CANCELLED ->
                        PillButton("Done", onClose, Modifier.weight(1f), style = if (s.phase == SwitchSender.Phase.DONE) PillStyle.Accent else PillStyle.Glass)
                    SwitchSender.Phase.FAILED -> {
                        PillButton("Close", onClose, Modifier.weight(1f), style = PillStyle.Glass)
                        PillButton("Resume", onResume, Modifier.weight(1f))
                    }
                    else -> PillButton("Stop", onCancel, Modifier.weight(1f), style = PillStyle.Danger)
                }
            }
        }
    }
}

/** "Switch phones" on the new phone: what's arriving and where it went. */
@Composable
fun SwitchReceiveScreen(
    importer: SwitchImporter,
    state: SwitchImporter.State,
    receiving: SessionState?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { importer.importPendingContacts() }
    }
    // Ask up front when contacts are on the way, so they go straight into the address book.
    LaunchedEffect(Unit) {
        if (state.contactsTotal > 0 && !granted(context, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS))
            contactsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
    }
    val done = state.phase == SwitchImporter.Phase.DONE
    val failed = state.phase == SwitchImporter.Phase.FAILED
    val moved = state.photos + state.videos
    val mediaTotal = (state.photosTotal + state.videosTotal).coerceAtLeast(1)
    val fraction = if (done) 1f else (moved.toFloat() / mediaTotal).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 240.dp, radius = 240.dp, color = if (failed) DangerText else Accent)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when {
                    done -> "Welcome to your new device"
                    failed -> "Move paused"
                    state.phase == SwitchImporter.Phase.IMPORTING -> "Adding to your library…"
                    else -> "Moving from ${state.peerName}"
                },
                style = displayStyle(22), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp),
            )
            Ring(fraction, done, failed)
            receiving?.takeIf { !done }?.let {
                Text(
                    "Batch ${state.batch + 1} of ${state.batches} · ${(it.fraction * 100).toInt()}%" +
                        (it.bytesPerSec.takeIf { b -> b > 0 }?.let { b -> " · ${formatBytes(b)}/s" } ?: ""),
                    style = monoStyle(13, TextDim),
                )
            }
            GlassCard(Modifier.fillMaxWidth(), fill = Glass, border = Hairline, contentPadding = PaddingValues(6.dp)) {
                if (state.photosTotal > 0 || state.photos > 0) {
                    CountRow(CdIcons.Photo, "Photos", "%,d / %,d".format(state.photos, state.photosTotal))
                }
                if (state.videosTotal > 0 || state.videos > 0) {
                    if (state.photosTotal > 0 || state.photos > 0) RowSeparator()
                    CountRow(CdIcons.Video, "Videos", "%,d / %,d".format(state.videos, state.videosTotal))
                }
                if (state.contactsTotal > 0) {
                    if (state.photosTotal + state.photos + state.videosTotal + state.videos > 0) RowSeparator()
                    CountRow(
                        CdIcons.Contacts, "Contacts",
                        if (state.pendingContacts != null) "waiting for access"
                        else "%,d added".format(state.contactsAdded) + if (state.contactsSkipped > 0) " · %,d already here".format(state.contactsSkipped) else "",
                    )
                }
                if (state.files > 0) { RowSeparator(); CountRow(CdIcons.Doc, "Files", "%,d in inbox".format(state.files)) }
            }
            if (state.pendingContacts != null) {
                PillButton(
                    "Allow contacts to add them", {
                        contactsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                    }, Modifier.fillMaxWidth(), icon = CdIcons.Contacts,
                )
            }
            state.error?.let { Text(it, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center) }
            if (state.failedItems > 0) {
                Text("${state.failedItems} item(s) couldn't be added to the gallery and were put in your inbox.", color = Amber, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            FootNote("Photos and videos keep their original dates. They're in your gallery under “${state.peerName}”.", icon = CdIcons.Shield)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (done || failed) PillButton("Done", { importer.close(); onClose() }, Modifier.weight(1f))
                else {
                    PillButton("Stop", { importer.stop(); onClose() }, Modifier.weight(1f), style = PillStyle.Danger)
                    PillButton("Hide", onClose, Modifier.weight(1f), style = PillStyle.Glass)
                }
            }
        }
    }
}

@Composable
private fun CountRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(icon, Accent, size = 20.dp)
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, style = monoStyle(13, TextSecondary))
    }
}

@Composable
private fun Ring(fraction: Float, done: Boolean, failed: Boolean) {
    val shown by animateFloatAsState(fraction, tween(400), label = "switchRing")
    val color = if (failed) DangerText else Accent
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 12.dp.toPx()
            val inset = sw / 2 + 2.dp.toPx()
            val tl = Offset(inset, inset)
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, arc, style = Stroke(sw))
            rotate(-90f) { drawArc(color, 0f, 360f * shown, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round)) }
        }
        if (done) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                CdIcon(CdIcons.Check, OnAccent, size = 32.dp)
            }
        } else {
            Text(
                buildAnnotatedString {
                    append("${(shown * 100).toInt()}")
                    withStyle(SpanStyle(fontSize = 24.sp, color = TextDim)) { append("%") }
                },
                style = displayStyle(52, tracking = -0.05f).copy(lineHeight = 52.sp),
            )
        }
    }
}

