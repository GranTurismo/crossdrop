package com.crossdrop.ui.switchphones

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.SessionState
import com.crossdrop.platform.SystemActions
import com.crossdrop.switchphones.DesktopSwitchReceiver
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/** A phone moving its library onto this computer: progress, counts and the folder it lands in. */
@Composable
fun SwitchReceiveScreen(
    state: DesktopSwitchReceiver.State,
    receiving: SessionState?,
    onStop: () -> Unit,
    onHide: () -> Unit,
    onDone: () -> Unit,
) {
    val done = state.phase == DesktopSwitchReceiver.Phase.DONE
    val failed = state.phase == DesktopSwitchReceiver.Phase.FAILED
    val total = (state.photosTotal + state.videosTotal).coerceAtLeast(1)
    val fraction = if (done) 1f else ((state.photos + state.videos).toFloat() / total).coerceIn(0f, 1f)
    val shown by animateFloatAsState(fraction, tween(400), label = "switchRing")

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 240.dp, radius = 260.dp, color = if (failed) DangerText else Accent)
        Column(
            Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when {
                    done -> "${state.peerName} is backed up here"
                    failed -> "Move paused"
                    else -> "Moving from ${state.peerName}"
                },
                style = displayStyle(24), textAlign = TextAlign.Center,
            )
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 12.dp.toPx()
                    val inset = sw / 2 + 2.dp.toPx()
                    val tl = Offset(inset, inset)
                    val arc = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, arc, style = Stroke(sw))
                    rotate(-90f) { drawArc(if (failed) DangerText else Accent, 0f, 360f * shown, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round)) }
                }
                if (done) Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                    CdIcon(CdIcons.Check, OnAccent, size = 32.dp)
                } else Text(
                    buildAnnotatedString {
                        append("${(shown * 100).toInt()}")
                        withStyle(SpanStyle(fontSize = 24.sp, color = TextDim)) { append("%") }
                    },
                    style = displayStyle(52, tracking = -0.05f).copy(lineHeight = 52.sp),
                )
            }
            receiving?.takeIf { !done }?.let {
                Text(
                    "Batch ${state.batch + 1} of ${state.batches} · ${(it.fraction * 100).toInt()}%" +
                        (it.bytesPerSec.takeIf { b -> b > 0 }?.let { b -> " · ${formatBytes(b)}/s" } ?: ""),
                    style = monoStyle(13, TextDim),
                )
            }
            GlassCard(Modifier.widthIn(max = 520.dp).fillMaxWidth(), fill = Glass, border = Hairline, contentPadding = PaddingValues(6.dp)) {
                Row(CdIcons.Photo, "Photos", "%,d / %,d".format(state.photos, state.photosTotal))
                RowSeparator()
                Row(CdIcons.Video, "Videos", "%,d / %,d".format(state.videos, state.videosTotal))
                if (state.contactsTotal > 0) { RowSeparator(); Row(CdIcons.Contacts, "Contacts", if (state.contacts > 0) "Contacts.vcf" else "%,d to come".format(state.contactsTotal)) }
                if (state.files > 0) { RowSeparator(); Row(CdIcons.Doc, "Files", "%,d".format(state.files)) }
            }
            state.error?.let { Text(it, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center) }
            FootNote("Saved to ${state.folder.path} with the original dates. Open Contacts.vcf to add the contacts to your address book.", Modifier.widthIn(max = 520.dp), icon = CdIcons.Folder)
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Show folder", { if (state.folder.exists()) SystemActions.open(state.folder) }, Modifier.weight(1f), style = PillStyle.Glass, icon = CdIcons.Folder)
                if (done || failed) PillButton("Done", onDone, Modifier.weight(1f))
                else {
                    PillButton("Stop", onStop, Modifier.weight(1f), style = PillStyle.Danger)
                    PillButton("Hide", onHide, Modifier.weight(1f), style = PillStyle.Glass)
                }
            }
        }
    }
}

@Composable
private fun Row(icon: ImageVector, label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(icon, Accent, size = 20.dp)
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, style = monoStyle(13, TextSecondary))
    }
}
