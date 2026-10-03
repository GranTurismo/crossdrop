package com.crossdrop.ui.transfer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crossdrop.sensor.TiltSource
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Dynamic Reactive Canvas: a liquid-glass orb replacing the progress bar.
 *
 * - `progress` sets the fill level of the glowing fluid.
 * - `turbulence` (normalized throughput) drives wave amplitude, bubble rate and
 *   the inner phosphorescent glow.
 * - `tilt` (filtered accelerometer) sloshes the liquid plane against gravity.
 * - `completed` crystallizes the surface and flares the rim.
 */
@Composable
fun LiquidOrb(
    progress: Float,
    turbulence: Float,
    tilt: TiltSource.Tilt,
    completed: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
) {
    val level by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 90f),
        label = "level",
    )
    val turb by animateFloatAsState(
        targetValue = if (completed) 0f else turbulence.coerceIn(0f, 1f),
        animationSpec = tween(600), label = "turb",
    )

    // Two incommensurate loops keep sine phases seamless and bubbles cycling.
    val transition = rememberInfiniteTransition(label = "fluid")
    val tA by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "tA",
    )
    val tB by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(4700, easing = LinearEasing)), label = "tB",
    )
    val bubbleT by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "bubbles",
    )
    val flare by animateFloatAsState(
        if (completed) 1f else 0f, tween(900), label = "flare",
    )

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val r = min(this.size.width, this.size.height) / 2 * 0.94f

        // -- inner phosphorescent glow (throughput energy)
        if (turb > 0.02f) {
            drawCircle(
                Brush.radialGradient(
                    colors = listOf(FluidCyan.copy(alpha = 0.28f * turb), Color.Transparent),
                    center = center, radius = r,
                ), radius = r, center = center,
            )
        }

        // -- glass shell
        drawCircle(Color(0xFF0D1428), radius = r, center = center)
        drawCircle(
            Brush.linearGradient(
                colors = listOf(Color(0x66EAF9FF), Color(0x1AEAF9FF), Color(0x44EAF9FF)),
                start = center - Offset(r, r), end = center + Offset(r, r),
            ),
            radius = r, center = center, style = Stroke(2.4f),
        )
        // specular crescent
        rotate(-38f, center) {
            drawArc(
                Brush.horizontalGradient(listOf(Color(0x99FFFFFF), Color.Transparent)),
                startAngle = 180f, sweepAngle = 64f, useCenter = false,
                topLeft = center - Offset(r * 0.72f, r * 0.86f),
                size = Size(r * 1.44f, r * 0.66f),
                style = Stroke(width = r * 0.075f),
            )
        }

        // -- liquid (counter-rotates against tilt → slosh)
        val orbPath = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(center, r * 0.965f))
        }
        val sloshDeg = atan2(-tilt.x, tilt.y.coerceIn(0.15f, 1f)) * 57.3f * 0.85f
        val amp = (r * 0.045f) * (0.4f + turb * 1.8f + tilt.energy * 1.2f)
        val surfaceY = center.y + r - level * 2 * r

        clipPath(orbPath) {
            if (level > 0.005f) {
                rotate(sloshDeg, center) {
                    drawWave(center, r, surfaceY + r * 0.03f, amp * 0.7f, tA + 1.7f, tB,
                        color = RippleTeal.copy(alpha = 0.55f))
                    drawWave(center, r, surfaceY, amp, tA, tB + 0.9f,
                        color = Brush.verticalGradient(
                            colors = listOf(
                                FluidCyan.copy(alpha = 0.95f),
                                RippleTeal.copy(alpha = 0.95f),
                                Color(0xFF0B7C6E),
                            ),
                            startY = surfaceY - amp * 2, endY = center.y + r,
                        ))
                }
            }
            if (turb > 0.22f) drawBubbles(center, r, surfaceY, bubbleT, turb)
        }

        // -- completion: crystalline rim flare
        if (flare > 0f) {
            drawCircle(
                Phosphor.copy(alpha = 0.9f * flare),
                radius = r, center = center, style = Stroke(3f + 8f * flare),
            )
            drawCircle(
                Phosphor.copy(alpha = 0.25f * flare),
                radius = r * (1f + 0.12f * flare), center = center, style = Stroke(1.5f),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(
    center: Offset, r: Float, surfaceY: Float, amp: Float,
    tA: Float, tB: Float, color: Color,
) = drawWave(center, r, surfaceY, amp, tA, tB, Brush.verticalGradient(listOf(color, color)))

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(
    center: Offset, r: Float, surfaceY: Float, amp: Float,
    tA: Float, tB: Float, color: Brush,
) {
    val path = Path()
    val left = center.x - r * 1.15f
    val right = center.x + r * 1.15f
    val steps = 64
    path.moveTo(left, surfaceY + waveAt(left, center.x, r, amp, tA, tB))
    for (i in 1..steps) {
        val x = left + (right - left) * i / steps
        path.lineTo(x, surfaceY + waveAt(x, center.x, r, amp, tA, tB))
    }
    path.lineTo(right, center.y + r * 1.3f)
    path.lineTo(left, center.y + r * 1.3f)
    path.close()
    drawPath(path, color)
}

private fun waveAt(x: Float, cx: Float, r: Float, amp: Float, tA: Float, tB: Float): Float {
    val u = (x - cx) / r
    return (sin(u * 2.1f + tA) * 0.6f + sin(u * 3.7f - tB * 1.3f) * 0.4f) * amp
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBubbles(
    center: Offset, r: Float, surfaceY: Float, t: Float, turb: Float,
) {
    val count = (6 + turb * 14).toInt()
    for (i in 0 until count) {
        val seed = i * 137.5f
        val x = center.x + cos(seed) * r * 0.72f * ((seed % 97f) / 97f)
        val span = (center.y + r * 0.95f) - surfaceY
        if (span <= 0f) continue
        val cycle = (t * (1f + (i % 5) * 0.35f) + (i * 0.618f)) % 1f
        val y = center.y + r * 0.95f - cycle * span
        val wobble = sin((cycle * 6.28f) + i) * r * 0.02f
        drawCircle(
            Phosphor.copy(alpha = (0.35f + 0.3f * turb) * sin(cycle * PI.toFloat())),
            radius = (1.5f + (i % 4)) * (size.minDimension / 520f),
            center = Offset(x + wobble, y),
        )
    }
}
