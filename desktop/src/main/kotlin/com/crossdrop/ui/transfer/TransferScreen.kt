package com.crossdrop.ui.transfer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionState
import com.crossdrop.net.SessionStatus
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
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfers.fileProgressLabel

/**
 * Full-screen live transfer: sender → receiver with flowing packets, a ring
 * of progress, speed and delta-savings tiles, and the 6-digit verification
 * code both people compare. [onHide] minimises it to the Transfers tab.
 *
 * Desktop copy of the Android screen (no haptics; this computer's glyph for "you").
 */
@Composable
fun TransferScreen(
    session: SessionState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    selfName: String = tr("This device"),
    onHide: (() -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
) {
    val status = session.status
    val completed = status == SessionStatus.COMPLETED
    val failed = status == SessionStatus.FAILED || status == SessionStatus.DECLINED || status == SessionStatus.CANCELLED
    val sending = session.direction == Direction.SENDING

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 315.dp, radius = 235.dp, color = if (failed) DangerText else Accent)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // header
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onHide != null && !status.isTerminal) {
                    CircleButton(CdIcons.ChevronLeft, tr("Back to transfers"), onHide)
                } else {
                    Spacer(Modifier.size(44.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StatusChip(session) }
                Spacer(Modifier.size(44.dp))
            }

            Column(
                Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // who → who
                val selfIcon = com.crossdrop.ui.radar.selfDeviceIcon
                val peerIcon = deviceIconFor(session.peerName)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Endpoint(if (sending) selfName else session.peerName, if (sending) selfIcon else peerIcon, highlighted = sending)
                    PacketFlow(active = status == SessionStatus.TRANSFERRING, Modifier.weight(1f).padding(bottom = 22.dp))
                    Endpoint(if (sending) session.peerName else selfName, if (sending) peerIcon else selfIcon, highlighted = !sending)
                }

                ProgressRing(session, completed, failed)

                // stats
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fileCount = session.offer?.files?.size
                    if (session.bytesPerSec > 0 || fileCount == null) {
                        StatTile(
                            tr("Speed"),
                            if (session.bytesPerSec > 0) "${formatBytes(session.bytesPerSec)}/s" else "—",
                            Modifier.weight(1f),
                        )
                    } else {
                        StatTile(tr("Files"), "$fileCount", Modifier.weight(1f))
                    }
                    if (session.bytesSkipped > 0) {
                        StatTile(tr("Already local"), "Δ ${formatBytes(session.bytesSkipped)}", Modifier.weight(1f), Accent)
                    } else {
                        StatTile(
                            if (sending) tr("Sent") else tr("Received"),
                            "${formatBytes(session.bytesDone)} / ${formatBytes(session.bytesTotal)}",
                            Modifier.weight(1f),
                        )
                    }
                }

                session.sas?.let { VerificationCode(it, session.peerName) }

                if (status == SessionStatus.FAILED && session.error != null) {
                    Text(
                        session.error, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // actions
            Spacer(Modifier.height(16.dp))
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    status.isTerminal && completed && !sending && onOpenInbox != null -> {
                        PillButton(tr("Done"), onClose, Modifier.weight(1f), style = PillStyle.Glass)
                        PillButton(tr("Open inbox"), onOpenInbox, Modifier.weight(1f), icon = CdIcons.Inbox)
                    }
                    status.isTerminal -> PillButton(tr("Done"), onClose, Modifier.weight(1f), style = if (completed) PillStyle.Accent else PillStyle.Glass)
                    else -> {
                        PillButton(tr("Cancel transfer"), onCancel, Modifier.weight(1f), style = PillStyle.Danger)
                        if (onHide != null) PillButton(tr("Hide"), onHide, Modifier.weight(1f), style = PillStyle.Glass)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(s: SessionState) {
    val (label, color) = when (s.status) {
        SessionStatus.CONNECTING -> tr("Connecting") to Accent
        SessionStatus.AWAITING_ACCEPT -> tr("Waiting for {0}", s.peerName) to Accent
        SessionStatus.TRANSFERRING -> (if (s.direction == Direction.SENDING) tr("Sending") else tr("Receiving")) to Accent
        SessionStatus.COMPLETED -> tr("Complete") to Accent
        SessionStatus.FAILED -> tr("Failed") to DangerText
        SessionStatus.DECLINED -> tr("Declined") to DangerText
        SessionStatus.CANCELLED -> tr("Cancelled") to TextSecondary
    }
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Endpoint(name: String, icon: ImageVector, highlighted: Boolean) {
    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (highlighted) Accent else GlassRaised)
                .then(if (!highlighted) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { CdIcon(icon, if (highlighted) OnAccent else TextPrimary, size = 22.dp) }
        Text(name, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Dashed link with three glowing packets travelling sender → receiver. */
@Composable
private fun PacketFlow(active: Boolean, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "flow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.height(8.dp)) {
        val y = size.height / 2
        drawLine(
            Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
        )
        if (!active) return@Canvas
        for (k in 0..2) {
            val p = (t + k / 3f) % 1f
            val a = when {
                p < 0.15f -> p / 0.15f
                p > 0.85f -> (1f - p) / 0.15f
                else -> 1f
            }
            val c = Offset(p * size.width, y)
            drawCircle(Accent.copy(alpha = 0.35f * a), radius = 8.dp.toPx(), center = c)
            drawCircle(Accent.copy(alpha = a), radius = 4.dp.toPx(), center = c)
        }
    }
}

@Composable
private fun ProgressRing(s: SessionState, completed: Boolean, failed: Boolean) {
    val target = if (completed) 1f else s.fraction
    val shown by animateFloatAsState(target, tween(400), label = "ring")
    val pct = (shown * 100).toInt()
    val color = if (failed) DangerText else Accent
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val d = minOf(216.dp, maxWidth * 0.62f)
        Box(
            Modifier.size(d).semantics {
                contentDescription = tr("Transfer progress")
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
            },
            contentAlignment = Alignment.Center,
        ) {
            // Glow: the same arc, blurred, on an oversized layer so the blur isn't cut at the edges.
            Canvas(
                Modifier.requiredSize(d + GLOW_PAD * 2).graphicsLayer {
                    renderEffect = BlurEffect(14.dp.toPx(), 14.dp.toPx(), TileMode.Decal)
                    alpha = 0.55f
                },
            ) {
                val sw = 12.dp.toPx()
                val inset = GLOW_PAD.toPx() + sw / 2 + 2.dp.toPx()
                rotate(-90f) {
                    drawArc(
                        color, 0f, 360f * shown, false, Offset(inset, inset),
                        Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val sw = 12.dp.toPx()
                val inset = sw / 2 + 2.dp.toPx()
                val tl = Offset(inset, inset)
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, arc, style = Stroke(sw))
                rotate(-90f) {
                    drawArc(color, 0f, 360f * shown, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (completed) {
                    Box(
                        Modifier.size(64.dp).dropShadow(CircleShape, Shadow(30.dp, Accent.copy(alpha = 0.45f)))
                            .clip(CircleShape).background(Accent),
                        contentAlignment = Alignment.Center,
                    ) { CdIcon(CdIcons.Check, OnAccent, size = 32.dp) }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Delivered"), style = displayStyle(17))
                } else {
                    Text(
                        buildAnnotatedString {
                            append("$pct")
                            withStyle(SpanStyle(fontSize = 28.sp, color = TextDim)) { append("%") }
                        },
                        style = displayStyle(60, tracking = -0.05f).copy(lineHeight = 60.sp),
                    )
                    Text(
                        s.currentFileName ?: when (s.status) {
                            SessionStatus.CONNECTING -> tr("Securing the channel…")
                            SessionStatus.AWAITING_ACCEPT -> tr("Waiting for them to accept")
                            else -> ""
                        },
                        color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = d - 48.dp),
                    )
                    s.fileProgressLabel()?.let { Text(it, color = TextDim, fontSize = 12.sp) }
                }
            }
        }
    }
}

private val GLOW_PAD = 28.dp

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    GlassCard(
        modifier, radius = 20.dp, fill = Glass, border = Hairline,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = TextDim, fontSize = 11.sp)
        Text(value, style = monoStyle(17, valueColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VerificationCode(sas: String, peerName: String) {
    GlassCard(
        Modifier.fillMaxWidth(), radius = 26.dp, fill = Glass, border = Hairline,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CdIcon(CdIcons.Shield, Accent, size = 18.dp)
            Text(tr("Verification code"), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                tr("match it on {0}", peerName), color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = tr("Code {0}", sas.toList().joinToString(" "))
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sas.forEach { ch ->
                Box(
                    Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(GlassMid),
                    contentAlignment = Alignment.Center,
                ) { Text("$ch", style = monoStyle(26)) }
            }
        }
    }
}

internal fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 10).toDouble())
    else -> "$b B"
}
