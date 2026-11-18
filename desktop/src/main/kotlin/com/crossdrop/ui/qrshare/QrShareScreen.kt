package com.crossdrop.ui.qrshare

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.net.QrShareManager
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CopyButton
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.QrCard
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextFaint
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/**
 * "Send to anyone": the receiver scans the QR with any camera and gets a
 * browser page with the files — no CrossDrop needed on their end. A three
 * step strip tracks scan → connected → delivered. Leaving it stops the share.
 * Desktop copy of the Android screen (SleepGuard keeps the machine awake).
 */
@Composable
fun QrShareScreen(
    state: QrShareManager.State,
    onStop: () -> Unit,
) {
    val step = when {
        state.delivered -> 2
        state.connected || state.downloadsStarted > 0 -> 1
        else -> 0
    }

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.14f, centerY = 310.dp, radius = 240.dp)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BackHeader(tr("Send to anyone"), onStop, backLabel = tr("Stop sharing and go back"))
                StepStrip(step, state)

                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    QrCard(state.url, size = minOf(236.dp, maxWidth - 40.dp))
                }
                Text(
                    tr("Scan with any camera — no app needed"), color = TextSecondary, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                // link + copy
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Glass)
                        .border(1.dp, Hairline, RoundedCornerShape(26.dp))
                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.url.removePrefix("http://"), style = monoStyle(13, TextSecondary),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(10.dp))
                    CopyButton(state.url, label = tr("Copy link"), accent = true, height = 44.dp)
                }

                FilesSummary(state)

                FootNote(
                    tr("They need to be on this network. Without the secret link the page doesn't exist, and it stops working the moment you leave this screen."),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.delivered) {
                PillButton(tr("Done"), onStop, Modifier.widthIn(max = 560.dp).fillMaxWidth(), height = 56.dp)
            } else {
                PillButton(tr("Stop sharing"), onStop, Modifier.widthIn(max = 560.dp).fillMaxWidth(), style = PillStyle.Danger, height = 56.dp)
            }
        }
    }
}

@Composable
private fun StepStrip(step: Int, state: QrShareManager.State) {
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b",
    )
    val labels = listOf(
        tr("Waiting for a scan"),
        if (state.downloadsStarted > 0 && !state.delivered) tr("{0} of {1} downloaded", state.downloadsDone, state.files.size) else tr("Receiver connected"),
        tr("Delivered"),
    )
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val done = i < step || (i == 2 && step == 2)
            val current = i == step
            val lit = done || current
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .then(if (current) Modifier.dropShadow(RoundedCornerShape(2.dp), Shadow(10.dp, Accent)) else Modifier)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (lit) Accent else Color.White.copy(alpha = 0.1f)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current && step < 2) {
                        Box(Modifier.size(6.dp).alpha(blink).clip(CircleShape).background(Accent))
                        Spacer(Modifier.size(5.dp))
                    }
                    Text(
                        label, color = if (lit) Accent else TextFaint, fontSize = 11.sp,
                        fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesSummary(state: QrShareManager.State) {
    val files = state.files
    val fileCount = files.size
    GlassCard(
        Modifier.fillMaxWidth(), radius = 22.dp, fill = GlassLow, border = null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                files.take(2).forEachIndexed { i, f ->
                    Box(
                        Modifier.offset(x = (i * 26).dp).size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(InkSheet).padding(2.dp),
                    ) { FileTypeTile(f.name, size = 36.dp) }
                }
            }
            Spacer(Modifier.size(if (files.size > 1) 38.dp else 12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${trPlural(fileCount, "1 file", "{n} files")} · ${formatBytes(files.sumOf { it.size })}",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    files.take(3).joinToString(", ") { it.name } +
                        (if (files.size > 3) " +${files.size - 3}" else "") +
                        (if (files.size > 1) " · " + tr("or all as one zip") else ""),
                    color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
