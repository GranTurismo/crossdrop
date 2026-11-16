package com.crossdrop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.crossdrop.net.IncomingOffer
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.HairlineSoft
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.MonoFont
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/**
 * Full-screen inbound OFFER. The ring around the sender counts down the
 * coordinator's auto-decline window; the human answers only with the two
 * buttons (the shell maps system Back to Decline).
 */
@Composable
fun IncomingOfferSheet(
    offer: IncomingOffer,
    onRespond: (accept: Boolean) -> Unit,
) {
    android.util.Log.i("IncomingOfferSheet", "composed for ${offer.offer.transferId}")
    val files = offer.offer.files
    val total = files.sumOf { it.size }
    val timeout = TransferCoordinator.OFFER_TIMEOUT_MS
    val startedAt = remember(offer) { System.currentTimeMillis() }
    val elapsed = remember(offer) { mutableLongStateOf(0L) }
    LaunchedEffect(offer) {
        while (elapsed.longValue < timeout) {
            withFrameMillis { elapsed.longValue = System.currentTimeMillis() - startedAt }
        }
    }
    val secondsLeft by remember(offer) {
        derivedStateOf { ((timeout - elapsed.longValue + 999) / 1000).coerceAtLeast(0) }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Box(Modifier.fillMaxSize()) {
            ScreenGlow(centerY = 215.dp, radius = 275.dp)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.weight(1f).widthIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(GlassMid)
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Dot(Accent)
                        Text(
                            "INCOMING FILES", color = TextSecondary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em,
                        )
                    }

                    CountdownAvatar(
                        peerName = offer.peerName,
                        fraction = { 1f - elapsed.longValue.toFloat() / timeout },
                        modifier = Modifier.padding(top = 10.dp),
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${offer.peerName} wants to send you " +
                                if (files.size == 1) "a file" else "${files.size} files",
                            style = displayStyle(28, tracking = -0.035f).copy(lineHeight = 31.sp),
                            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildAnnotatedString {
                                append("${formatBytes(total)} · auto-declines in ")
                                withStyle(SpanStyle(fontFamily = MonoFont, color = Accent)) { append("$secondsLeft") }
                                withStyle(SpanStyle(color = Accent)) { append(" s") }
                            },
                            color = TextDim, fontSize = 14.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    GlassCard(
                        Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                    ) {
                        files.take(4).forEachIndexed { i, f ->
                            if (i > 0) RowSeparator()
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FileTypeTile(f.name, size = 42.dp)
                                Text(
                                    f.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(formatBytes(f.size), style = monoStyle(12, TextDim))
                            }
                        }
                        if (files.size > 4) {
                            RowSeparator(color = HairlineSoft)
                            Text(
                                "+ ${files.size - 4} more",
                                color = TextDim, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton(
                        "Decline", { onRespond(false) }, Modifier.weight(1f),
                        style = PillStyle.Outline, height = 62.dp, fontSize = 16,
                    )
                    PillButton(
                        "Accept", { onRespond(true) }, Modifier.weight(1.5f),
                        style = PillStyle.Accent, height = 62.dp, fontSize = 16,
                        icon = CdIcons.Check, glow = true,
                    )
                }
            }
        }
    }
}

/** Sender glyph in a glass disc, ringed by the auto-decline countdown and a soft pulse. */
@Composable
private fun CountdownAvatar(peerName: String, fraction: () -> Float, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "offer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p",
    )
    Box(modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val s = 0.6f + 0.65f * pulse
                    scaleX = s; scaleY = s; alpha = 0.6f * (1f - pulse)
                }
                .border(1.5.dp, Accent, CircleShape),
        )
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            val inset = sw / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, tl, arcSize, style = Stroke(sw))
            rotate(-90f) {
                drawArc(
                    Accent, 0f, 360f * fraction().coerceIn(0f, 1f), false, tl, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(112.dp)
                .dropShadow(CircleShape, Shadow(50.dp, Accent.copy(alpha = 0.18f)))
                .clip(CircleShape).background(GlassRaised)
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { CdIcon(deviceIconFor(peerName), TextPrimary, size = 44.dp) }
    }
}
