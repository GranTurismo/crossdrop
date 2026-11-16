package com.crossdrop.ui.transfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionState
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferGroup
import com.crossdrop.net.isTerminal
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
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

/**
 * One send to several devices: an overall ring plus a row per device with its own progress,
 * outcome and 6-digit code. Devices that fail or decline can be retried without resending to the
 * others. Shared by Android and desktop.
 */
@Composable
fun GroupTransferScreen(
    group: TransferGroup,
    onCancelAll: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onHide: (() -> Unit)? = null,
) {
    val done = group.isTerminal
    val allOk = done && group.completed == group.sessions.size
    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 260.dp, radius = 230.dp, color = if (done && !allOk && group.completed == 0) DangerText else Accent)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onHide != null && !done) CircleButton(CdIcons.ChevronLeft, "Back to transfers", onHide)
                else Spacer(Modifier.size(44.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !done -> "Sending to ${group.sessions.size} devices"
                            allOk -> "Delivered to all ${group.sessions.size}"
                            else -> "${group.completed} of ${group.sessions.size} delivered"
                        },
                        color = if (done && !allOk) TextSecondary else Accent,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(44.dp))
            }

            Column(
                Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GroupRing(group)
                Text(
                    listOfNotNull(
                        "${formatBytes(group.bytesDone)} / ${formatBytes(group.bytesTotal)}",
                        group.bytesPerSec.takeIf { it > 0 }?.let { "${formatBytes(it)}/s total" },
                    ).joinToString(" · "),
                    style = monoStyle(13, TextDim),
                )
                Spacer(Modifier.height(4.dp))
                group.sessions.forEach { DeviceRow(it) }
                Text(
                    "Each device gets its own encrypted connection. Compare the code on a device to be sure it's the one you meant.",
                    color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    done && group.problems > 0 && group.direction == Direction.SENDING -> {
                        PillButton("Done", onClose, Modifier.weight(1f), style = PillStyle.Glass)
                        PillButton("Retry ${group.problems}", onRetry, Modifier.weight(1f), icon = CdIcons.ArrowUp)
                    }
                    done -> PillButton("Done", onClose, Modifier.weight(1f), style = if (allOk) PillStyle.Accent else PillStyle.Glass)
                    else -> {
                        PillButton("Cancel all", onCancelAll, Modifier.weight(1f), style = PillStyle.Danger)
                        if (onHide != null) PillButton("Hide", onHide, Modifier.weight(1f), style = PillStyle.Glass)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRing(group: TransferGroup) {
    val shown by animateFloatAsState(if (group.isTerminal && group.problems == 0) 1f else group.fraction, tween(400), label = "groupRing")
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val d = minOf(176.dp, maxWidth * 0.5f)
        Box(
            Modifier.size(d).semantics {
                contentDescription = "Overall progress"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 10.dp.toPx()
                val inset = sw / 2 + 2.dp.toPx()
                val tl = Offset(inset, inset)
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, arc, style = Stroke(sw))
                rotate(-90f) { drawArc(Accent, 0f, 360f * shown, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round)) }
            }
            if (group.isTerminal && group.problems == 0) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                    CdIcon(CdIcons.Check, OnAccent, size = 28.dp)
                }
            } else {
                Text(
                    buildAnnotatedString {
                        append("${(shown * 100).toInt()}")
                        withStyle(SpanStyle(fontSize = 22.sp, color = TextDim)) { append("%") }
                    },
                    style = displayStyle(46, tracking = -0.05f).copy(lineHeight = 46.sp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(s: SessionState) {
    val (label, color) = sessionLabel(s)
    GlassCard(
        Modifier.fillMaxWidth(), radius = 22.dp, fill = Glass, border = Hairline,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (s.status == SessionStatus.COMPLETED) Accent else GlassHigh),
                contentAlignment = Alignment.Center,
            ) {
                CdIcon(
                    if (s.status == SessionStatus.COMPLETED) CdIcons.Check else deviceIconFor(s.peerName),
                    if (s.status == SessionStatus.COMPLETED) OnAccent else TextPrimary, size = 18.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.peerName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, color = color, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            s.sas?.let { Text(it.chunked(3).joinToString(" "), style = monoStyle(13, TextSecondary)) }
        }
        if (!s.status.isTerminal) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(GlassHigh)) {
                Box(Modifier.fillMaxWidth(s.fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
            }
        }
    }
}

private fun sessionLabel(s: SessionState): Pair<String, Color> = when (s.status) {
    SessionStatus.CONNECTING -> "Connecting…" to TextDim
    SessionStatus.AWAITING_ACCEPT -> "Waiting for them to accept" to TextDim
    SessionStatus.TRANSFERRING -> listOfNotNull(
        "${(s.fraction * 100).toInt()}%",
        s.bytesPerSec.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
    ).joinToString(" · ") to Accent
    SessionStatus.COMPLETED -> "Delivered" to Accent
    SessionStatus.DECLINED -> "Declined" to DangerText
    SessionStatus.CANCELLED -> "Cancelled" to TextSecondary
    SessionStatus.FAILED -> ("Failed" + (s.error?.let { ": $it" } ?: "")) to DangerText
}
