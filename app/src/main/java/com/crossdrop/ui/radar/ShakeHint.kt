package com.crossdrop.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Shake to connect" taught by showing it: two little phones shake, then a mint pulse links them. Shown
 * when several devices are on the radar and the person hasn't used shaking yet; a tap dismisses it.
 */
@Composable
fun ShakeHintCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = tr("{0}, {1}", tr("Shake to connect"), tr("Several devices? Shake both phones to find each other."))
        },
        radius = 24.dp, fill = Accent.copy(alpha = 0.07f), border = Accent.copy(alpha = 0.32f),
        glow = Accent.copy(alpha = 0.12f), glowRadius = 30.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), onClick = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShakingPhones(Modifier.size(width = 78.dp, height = 46.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Shake to connect"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Several devices? Shake both phones to find each other."), color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            CdIcon(CdIcons.Close, TextDim, size = 16.dp)
        }
    }
}

/**
 * One 2.6 s loop: both phones shake hard and settle (0–40 %), a mint ring spreads from between them and a dot
 * lights up (40–80 %), then a short rest. Drawn, not animated views, so it costs one canvas.
 */
@Composable
private fun ShakingPhones(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "shake-hint").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(modifier) {
        val shake = (t / 0.4f).coerceIn(0f, 1f)
        val shaking = t < 0.4f
        val decay = if (shaking) 1f - shake * shake else 0f
        val angle = if (shaking) (sin(shake * 2 * PI * 4).toFloat() * 16f * decay) else 0f
        val pulse = ((t - 0.4f) / 0.4f).coerceIn(0f, 1f)
        val linked = t >= 0.4f

        val phoneW = size.height * 0.46f
        val phoneH = size.height * 0.8f
        val leftC = Offset(size.width * 0.2f, size.height / 2)
        val rightC = Offset(size.width * 0.8f, size.height / 2)
        val mid = Offset(size.width / 2, size.height / 2)

        // the link: a ring that spreads and fades, and a dot that stays lit until the loop rests
        if (linked) {
            val r = size.height * (0.12f + 0.38f * pulse)
            drawCircle(Accent.copy(alpha = 0.55f * (1f - pulse)), r, mid, style = Stroke(1.6.dp.toPx()))
            val dot = (pulse * 3f).coerceAtMost(1f) * (if (t > 0.9f) (1f - (t - 0.9f) / 0.1f) else 1f)
            drawCircle(Accent.copy(alpha = 0.25f * dot), size.height * 0.16f, mid)
            drawCircle(Accent.copy(alpha = dot), size.height * 0.07f, mid)
        }
        phone(leftC, phoneW, phoneH, angle, if (linked) Accent else TextPrimary, motion = decay, side = -1f)
        phone(rightC, phoneW, phoneH, -angle, if (linked) Accent else TextPrimary, motion = decay, side = 1f)
    }
}

/** A phone outline, tilted by [angle], with motion arcs on its outer side while it shakes. */
private fun DrawScope.phone(c: Offset, w: Float, h: Float, angle: Float, color: Color, motion: Float, side: Float) {
    val stroke = 1.8.dp.toPx()
    rotate(angle, c) {
        drawRoundRect(
            color, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.24f), style = Stroke(stroke),
        )
        drawLine(color, Offset(c.x - w * 0.12f, c.y + h * 0.36f), Offset(c.x + w * 0.12f, c.y + h * 0.36f), stroke, StrokeCap.Round)
    }
    if (motion > 0.05f) {
        val a = Accent.copy(alpha = 0.8f * motion)
        for (i in 0..1) {
            val dx = side * (w / 2 + 4.dp.toPx() + i * 4.dp.toPx())
            val half = h * (0.22f + 0.1f * i)
            drawLine(a, Offset(c.x + dx, c.y - half), Offset(c.x + dx, c.y + half), stroke * 0.9f, StrokeCap.Round)
        }
    }
}
