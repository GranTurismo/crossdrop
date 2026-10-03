package com.crossdrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn 24dp stroke icons — keeps the app vector-crisp without pulling
 * in the material-icons artifact. All render through [Canvas].
 */

@Composable
fun IconRadar(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        val w = 1.7.dp.toPx()
        for (k in listOf(0.34f, 0.67f, 1f)) {
            drawCircle(tint, radius = r * 0.92f * k, center = c, style = Stroke(w))
        }
        drawCircle(tint, radius = r * 0.10f, center = c)
        // sweep wedge at ~45°
        val a = -PI.toFloat() / 4f
        drawLine(tint, c, c + Offset(cos(a) * r * 0.92f, sin(a) * r * 0.92f), strokeWidth = w)
    }
}

@Composable
fun IconTransfers(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.8.dp.toPx()
        val r = this.size.minDimension
        // up arrow (left), down arrow (right)
        val lx = r * 0.32f; val rx = r * 0.68f
        val cap = StrokeCap.Round
        drawLine(tint, Offset(lx, r * 0.78f), Offset(lx, r * 0.22f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(lx - r * 0.12f, r * 0.34f), Offset(lx, r * 0.22f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(lx + r * 0.12f, r * 0.34f), Offset(lx, r * 0.22f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(rx, r * 0.22f), Offset(rx, r * 0.78f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(rx - r * 0.12f, r * 0.66f), Offset(rx, r * 0.78f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(rx + r * 0.12f, r * 0.66f), Offset(rx, r * 0.78f), strokeWidth = w, cap = cap)
    }
}

@Composable
fun IconInbox(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.7.dp.toPx()
        val r = this.size.minDimension
        val cap = StrokeCap.Round
        // tray outline: open-top box with a dip in the middle
        val y0 = r * 0.30f; val y1 = r * 0.80f
        val notch = r * 0.40f
        // left wall + bottom + right wall
        drawLine(tint, Offset(r * 0.14f, y0), Offset(r * 0.14f, y1), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.14f, y1), Offset(r * 0.86f, y1), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.86f, y0), Offset(r * 0.86f, y1), strokeWidth = w, cap = cap)
        // rim: angled in to the notch
        drawLine(tint, Offset(r * 0.14f, y0), Offset(r * 0.36f, y0 + notch * 0.5f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.36f, y0 + notch * 0.5f), Offset(r * 0.64f, y0 + notch * 0.5f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.64f, y0 + notch * 0.5f), Offset(r * 0.86f, y0), strokeWidth = w, cap = cap)
        // arrow dropping in
        drawLine(tint, Offset(r * 0.5f, r * 0.14f), Offset(r * 0.5f, r * 0.42f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.40f, r * 0.32f), Offset(r * 0.5f, r * 0.42f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(r * 0.60f, r * 0.32f), Offset(r * 0.5f, r * 0.42f), strokeWidth = w, cap = cap)
    }
}

@Composable
fun IconGear(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        val w = 1.7.dp.toPx()
        // 8 spokes
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f + PI.toFloat() / 8f
            drawLine(
                tint,
                c + Offset(cos(a) * r * 0.62f, sin(a) * r * 0.62f),
                c + Offset(cos(a) * r * 0.88f, sin(a) * r * 0.88f),
                strokeWidth = w, cap = StrokeCap.Round,
            )
        }
        drawCircle(tint, radius = r * 0.52f, center = c, style = Stroke(w))
        drawCircle(tint, radius = r * 0.18f, center = c, style = Stroke(w))
    }
}

/** Direction arrow used on history tiles — up = sent, down = received. */
@Composable
fun IconArrow(tint: Color, up: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.9.dp.toPx()
        val r = this.size.minDimension
        val cap = StrokeCap.Round
        if (up) {
            drawLine(tint, Offset(r * 0.5f, r * 0.80f), Offset(r * 0.5f, r * 0.20f), strokeWidth = w, cap = cap)
            drawLine(tint, Offset(r * 0.24f, r * 0.46f), Offset(r * 0.5f, r * 0.20f), strokeWidth = w, cap = cap)
            drawLine(tint, Offset(r * 0.76f, r * 0.46f), Offset(r * 0.5f, r * 0.20f), strokeWidth = w, cap = cap)
        } else {
            drawLine(tint, Offset(r * 0.5f, r * 0.20f), Offset(r * 0.5f, r * 0.80f), strokeWidth = w, cap = cap)
            drawLine(tint, Offset(r * 0.24f, r * 0.54f), Offset(r * 0.5f, r * 0.80f), strokeWidth = w, cap = cap)
            drawLine(tint, Offset(r * 0.76f, r * 0.54f), Offset(r * 0.5f, r * 0.80f), strokeWidth = w, cap = cap)
        }
    }
}

@Composable
fun IconClose(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.9.dp.toPx()
        val r = this.size.minDimension
        drawLine(tint, Offset(r * 0.25f, r * 0.25f), Offset(r * 0.75f, r * 0.75f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(tint, Offset(r * 0.75f, r * 0.25f), Offset(r * 0.25f, r * 0.75f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
fun IconCheck(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.9.dp.toPx()
        val r = this.size.minDimension
        drawLine(tint, Offset(r * 0.22f, r * 0.55f), Offset(r * 0.44f, r * 0.74f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(tint, Offset(r * 0.44f, r * 0.74f), Offset(r * 0.80f, r * 0.28f), strokeWidth = w, cap = StrokeCap.Round)
    }
}
