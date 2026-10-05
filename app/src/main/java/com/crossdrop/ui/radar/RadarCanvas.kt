package com.crossdrop.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.Peer
import com.crossdrop.ui.theme.AuroraViolet
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.NebulaPink
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.abs

/**
 * The ambient sonar screen: concentric ripples, a rotating sweep, and orbiting
 * peer nodes whose radial distance tracks BLE RSSI (Kalman-smoothed input
 * arrives via [Peer.estimatedDistanceMeters]). NSD-only peers orbit a default
 * ring. A peer's angle is a stable hash of its instance id so nodes don't
 * teleport between recompositions.
 */
@Composable
fun RadarCanvas(
    selfName: String,
    peers: List<Peer>,
    onPeerTap: (Peer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val transition = rememberInfiniteTransition(label = "sonar")
    val rippleT by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "ripple",
    )
    val sweepT by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "sweep",
    )
    val bobT by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "bob",
    )
    val tA by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "ambient",
    )

    // Stable geometry shared by drawing + hit-testing.
    val positions = remember(peers, canvasSize, bobT) {
        if (canvasSize == Size.Zero) emptyMap() else {
            val center = Offset(canvasSize.width / 2, canvasSize.height / 2)
            val maxR = min(canvasSize.width, canvasSize.height) / 2 * 0.86f
            peers.associate { peer ->
                val angle = angleFor(peer.instanceId)
                val norm = normalizedDistance(peer)
                val bob = sin(bobT + (abs(peer.instanceId.hashCode()) % 7)).toFloat() * 6f
                val r = maxR * norm + bob
                peer.instanceId to Offset(
                    center.x + r * cos(angle), center.y + r * sin(angle)
                )
            }
        }
    }

    // pointerInput must NOT key on `positions` — it changes every frame (bobT),
    // which would restart detectTapGestures mid-gesture and eat every tap.
    val latestPositions by rememberUpdatedState(positions)
    val latestPeers by rememberUpdatedState(peers)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    latestPositions.entries
                        .minByOrNull { (_, pos) -> (pos - tap).getDistance() }
                        ?.takeIf { (_, pos) -> (pos - tap).getDistance() < 140f }
                        ?.let { (id, _) -> latestPeers.firstOrNull { it.instanceId == id }?.let(onPeerTap) }
                }
            },
    ) {
        canvasSize = size
        val center = Offset(size.width / 2, size.height / 2)
        val maxR = min(size.width, size.height) / 2 * 0.86f

        drawBackground()
        drawAmbient(center, maxR, tA)
        drawGrid(center, maxR)
        drawSweep(center, maxR, sweepT)
        drawRipples(center, maxR, rippleT)

        positions.forEach { (id, pos) ->
            peers.firstOrNull { it.instanceId == id }?.let { peer ->
                if (peer.vault != null) drawVaultBeacon(pos, peer, textMeasurer, bobT, tA)
                else drawPeerNode(pos, peer, textMeasurer, bobT)
            }
        }
        drawSelfNode(center, selfName, textMeasurer, rippleT)
    }
}

// ------------------------------------------------------------------ layers --

private fun DrawScope.drawBackground() {
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF0C0628), DeepSpace),
            center = center, radius = size.maxDimension / 1.6f,
        )
    )
}

/** Nebula washes + twinkling starfield — deterministic placement, ambient drift. */
private fun DrawScope.drawAmbient(center: Offset, maxR: Float, tA: Float) {
    val blobs = listOf(
        Triple(AuroraViolet, Offset(-0.9f, -0.5f), 1.1f),
        Triple(FluidCyan, Offset(0.8f, -0.9f), 0.9f),
        Triple(RippleTeal, Offset(0.4f, 0.95f), 1.0f),
        Triple(NebulaPink, Offset(-0.75f, 0.6f), 0.7f),
    )
    blobs.forEachIndexed { i, (color, off, scale) ->
        val drift = Offset(sin(tA * 0.4f + i * 2.1f) * 0.08f, cos(tA * 0.3f + i) * 0.06f)
        val pos = center + Offset(off.x * maxR + drift.x * maxR, off.y * maxR + drift.y * maxR)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.075f), Color.Transparent),
                center = pos, radius = maxR * scale),
            radius = maxR * scale, center = pos,
        )
    }
    // Stars: hash-seeded positions, gentle twinkle. Cheap ambience, no state.
    for (i in 0 until 90) {
        val h = (i.toLong() * 2654435761L) xor 0x9E3779B9L
        val px = ((h ushr 33) % 1000).toFloat() / 1000f
        val py = ((h ushr 11) % 1023).toFloat() / 1023f
        val tw = 0.5f + 0.5f * sin(tA * (0.8f + (i % 7) * 0.22f) + i * 1.7f)
        drawCircle(
            Color.White.copy(alpha = 0.05f + 0.10f * tw),
            radius = 0.8f + (i % 3) * 0.5f,
            center = Offset(px * size.width, py * size.height),
        )
    }
}

private fun DrawScope.drawGrid(center: Offset, maxR: Float) {
    val ringColor = RippleTeal.copy(alpha = 0.10f)
    for (i in 1..4) {
        drawCircle(ringColor, maxR * i / 4f, center, style = Stroke(1.2f))
    }
    val lineColor = RippleTeal.copy(alpha = 0.05f)
    drawLine(lineColor, center - Offset(maxR, 0f), center + Offset(maxR, 0f), 1f)
    drawLine(lineColor, center - Offset(0f, maxR), center + Offset(0f, maxR), 1f)
}

private fun DrawScope.drawSweep(center: Offset, maxR: Float, sweepDeg: Float) {
    val wedge = Path().apply {
        moveTo(center.x, center.y)
        arcTo(Rect(center = center, radius = maxR), 0f, 60f, false)
        close()
    }
    clipPath(wedge) {
        rotate(sweepDeg, center) {
            drawCircle(
                Brush.sweepGradient(
                    0f to RippleTeal.copy(alpha = 0.30f),
                    0.07f to FluidCyan.copy(alpha = 0.16f),
                    0.13f to AuroraViolet.copy(alpha = 0.07f),
                    0.18f to Color.Transparent,
                    1f to Color.Transparent,
                    center = center,
                ),
                radius = maxR, center = center,
            )
        }
    }
}

private fun DrawScope.drawRipples(center: Offset, maxR: Float, t: Float) {
    for (i in 0 until 4) {
        val phase = (t + i / 4f) % 1f
        drawCircle(
            RippleTeal.copy(alpha = (1f - phase) * 0.35f),
            radius = phase * maxR,
            center = center,
            style = Stroke(width = (3f - phase * 2f).coerceAtLeast(0.6f)),
        )
    }
}

private fun DrawScope.drawPeerNode(pos: Offset, peer: Peer, measurer: TextMeasurer, t: Float) {
    val reachable = peer.endpoint != null
    val alpha = if (reachable) 1f else 0.45f
    val nodeR = 30.dp.toPx()
    // breathing pulse on the glow — each peer phased by its id hash
    val pulse = 1f + 0.08f * sin(t + abs(peer.instanceId.hashCode()) % 11)

    // soft glow halo
    drawCircle(
        Brush.radialGradient(
            colors = listOf(
                (if (reachable) RippleTeal else FluidCyan).copy(alpha = 0.40f * alpha),
                Color.Transparent,
            ),
            center = pos, radius = nodeR * 2.0f * pulse,
        ),
        radius = nodeR * 2.0f * pulse, center = pos,
    )
    // body
    drawCircle(
        Brush.radialGradient(
            colors = listOf(Phosphor.copy(alpha = alpha), RippleTealDimSafe(alpha)),
            center = pos.copy(y = pos.y - nodeR * 0.3f), radius = nodeR,
        ),
        radius = nodeR * 0.62f, center = pos,
    )
    // orbit ring for reachable peers
    if (reachable) {
        drawCircle(
            RippleTeal.copy(alpha = 0.6f), radius = nodeR * 0.8f, center = pos,
            style = Stroke(1.4f),
        )
    }
    // glass-pill label
    val label = measurer.measure(
        peer.name,
        TextStyle(
            color = TextPrimary.copy(alpha = alpha), fontSize = 12.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        ),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = 120.dp.toPx().toInt()),
    )
    val padH = 10.dp.toPx(); val padV = 4.dp.toPx()
    val pillW = label.size.width + padH * 2
    val pillH = label.size.height + padV * 2
    val pillTop = pos + Offset(-pillW / 2f, nodeR * 0.85f)
    drawRoundRect(
        DeepSpaceSoft.copy(alpha = 0.85f),
        topLeft = pillTop, size = Size(pillW, pillH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f),
    )
    drawRoundRect(
        GlassEdge, topLeft = pillTop, size = Size(pillW, pillH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f),
        style = Stroke(1f),
    )
    drawText(label, topLeft = pillTop + Offset(padH, padV))
}

private fun RippleTealDimSafe(alpha: Float) = RippleTeal.copy(alpha = 0.85f * alpha)

/**
 * Orbital Beacon — the vault node: violet core, segmented orbit ring that
 * slowly rotates, and an [RO]/[RW] badge above the name pill.
 */
private fun DrawScope.drawVaultBeacon(
    pos: Offset, peer: Peer, measurer: TextMeasurer, t: Float, orbit: Float,
) {
    val vault = peer.vault ?: return
    val nodeR = 30.dp.toPx()
    val pulse = 1f + 0.10f * sin(t + abs(peer.instanceId.hashCode()) % 11)
    val tint = AuroraViolet

    // halo
    drawCircle(
        Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.45f), Color.Transparent),
            center = pos, radius = nodeR * 2.2f * pulse,
        ),
        radius = nodeR * 2.2f * pulse, center = pos,
    )
    // core
    drawCircle(
        Brush.radialGradient(
            colors = listOf(Phosphor, tint.copy(alpha = 0.85f)),
            center = pos.copy(y = pos.y - nodeR * 0.3f), radius = nodeR,
        ),
        radius = nodeR * 0.58f, center = pos,
    )
    // segmented orbit ring — dashes rotating around the node
    val ringR = nodeR * 1.02f
    rotate(orbit * 180f / PI.toFloat(), pivot = pos) {
        for (i in 0 until 12) {
            // alternating violet/magenta dashes — the aurora curtain edge
            val c = when (i % 3) { 0 -> NebulaPink; else -> tint }
            drawArc(
                color = c.copy(alpha = if (i % 2 == 0) 0.9f else 0.42f),
                startAngle = i * 30f, sweepAngle = 16f, useCenter = false,
                topLeft = pos - Offset(ringR, ringR), size = Size(ringR * 2, ringR * 2),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
    // [RW]/[RO] badge above the node
    val badgeText = if (vault.writable) "RW" else "RO"
    val badge = measurer.measure(
        badgeText,
        TextStyle(color = if (vault.writable) Phosphor else tint,
            fontSize = 9.sp, fontWeight = FontWeight.Bold),
    )
    val bPadH = 6.dp.toPx(); val bPadV = 2.dp.toPx()
    val bw = badge.size.width + bPadH * 2
    val bh = badge.size.height + bPadV * 2
    val bTop = pos - Offset(bw / 2f, nodeR * 1.35f + bh)
    drawRoundRect(
        DeepSpaceSoft.copy(alpha = 0.9f), topLeft = bTop, size = Size(bw, bh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bh / 2f),
    )
    drawRoundRect(
        tint.copy(alpha = 0.7f), topLeft = bTop, size = Size(bw, bh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bh / 2f),
        style = Stroke(1f),
    )
    drawText(badge, topLeft = bTop + Offset(bPadH, bPadV))
    // name pill (same chrome as peers)
    val label = measurer.measure(
        vault.vaultName,
        TextStyle(color = TextPrimary, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = 120.dp.toPx().toInt()),
    )
    val padH = 10.dp.toPx(); val padV = 4.dp.toPx()
    val pillW = label.size.width + padH * 2
    val pillH = label.size.height + padV * 2
    val pillTop = pos + Offset(-pillW / 2f, nodeR * 0.85f)
    drawRoundRect(
        DeepSpaceSoft.copy(alpha = 0.85f),
        topLeft = pillTop, size = Size(pillW, pillH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f),
    )
    drawRoundRect(
        tint.copy(alpha = 0.6f), topLeft = pillTop, size = Size(pillW, pillH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f),
        style = Stroke(1f),
    )
    drawText(label, topLeft = pillTop + Offset(padH, padV))
}

private fun DrawScope.drawSelfNode(center: Offset, name: String, measurer: TextMeasurer, t: Float) {
    // expanding heartbeat ring
    val phase = t % 1f
    drawCircle(
        RippleTeal.copy(alpha = (1f - phase) * 0.30f),
        radius = 14.dp.toPx() + phase * 34.dp.toPx(),
        center = center, style = Stroke(1.2f + (1f - phase) * 1.4f),
    )
    drawCircle(
        Brush.radialGradient(
            colors = listOf(RippleTeal.copy(alpha = 0.5f), Color.Transparent),
            center = center, radius = 44.dp.toPx(),
        ),
        radius = 44.dp.toPx(), center = center,
    )
    drawCircle(Phosphor, radius = 9.dp.toPx(), center = center)
    drawCircle(RippleTeal, radius = 14.dp.toPx(), center = center, style = Stroke(1.6f))
    val label = measurer.measure(
        name, TextStyle(color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center),
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = 120.dp.toPx().toInt()),
    )
    drawText(label, topLeft = center + Offset(-label.size.width / 2f, 18.dp.toPx()))
}

// ------------------------------------------------------------------- math --

private fun angleFor(instanceId: String): Float {
    val h = instanceId.fold(7) { acc, c -> acc * 31 + c.code }
    return ((h and 0x7fffffff) % 360) * PI.toFloat() / 180f
}

/** RSSI → normalized radar radius [0.14, 1.0]; no signal → default ring. */
private fun normalizedDistance(peer: Peer): Float {
    val meters = peer.estimatedDistanceMeters ?: return 0.55f
    // 0.3 m … ~12 m compressed logarithmically.
    val n = (kotlin.math.ln(meters.coerceIn(0.3, 12.0) / 0.3) / kotlin.math.ln(12.0 / 0.3))
    return (0.14f + n.toFloat() * 0.86f)
}
