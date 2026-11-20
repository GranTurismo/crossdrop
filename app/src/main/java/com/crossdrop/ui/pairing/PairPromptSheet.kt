package com.crossdrop.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.PairPrompt
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.IconTile
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle

/**
 * A device asks to become one of "My devices" (so far: a phone that wants to back up here). Both screens
 * show the same 6-digit code; agreeing only makes sense when they match. Platform-free: shared with desktop.
 */
@Composable
fun PairPromptSheet(prompt: PairPrompt, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ScreenGlow(alpha = 0.22f, centerX = 0.5f, centerY = 220.dp, radius = 280.dp)
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconTile(CdIcons.Link, Accent, size = 64.dp, radius = 22.dp, iconSize = 30.dp)
            Text(
                tr("Pair with {0}?", prompt.peerName),
                style = displayStyle(26).copy(lineHeight = 31.sp), color = TextPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                tr("{0} will back up its photos and videos to this computer, without asking each time.", prompt.peerName),
                color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
            GlassCard(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                fill = Ink.copy(alpha = 0.6f), contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("Same code on both screens?"), color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(
                    prompt.sas.chunked(3).joinToString(" "), style = monoStyle(38, Accent),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
            }
            FootNote(tr("Pair only with your own devices. You can unpair at any time in Settings."), icon = CdIcons.Shield)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(tr("Don't pair"), { onAnswer(false) }, Modifier.weight(1f), style = PillStyle.Glass)
                PillButton(tr("Pair"), { onAnswer(true) }, Modifier.weight(1f), glow = true)
            }
        }
    }
}
