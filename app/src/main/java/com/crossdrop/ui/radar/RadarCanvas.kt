package com.crossdrop.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.Peer
import com.crossdrop.core.UNNAMED_PEER
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.displayStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sonar: three quiet rings, a rotating sweep and two accent pulses behind
 * real, focusable device nodes. A node's radius tracks BLE distance
 * ([Peer.estimatedDistanceMeters]); Wi-Fi-only peers sit on an outer ring
 * with a dashed outline ("no distance"). The angle is a stable hash of the
 * instance id so nodes never jump between recompositions. The nearest
 * device glows. Vault beacons, if passed in, render as amber drive nodes.
 *
 * Animation state is read only in draw/layout lambdas, so the sweep and the
 * gentle float never recompose the node tree.
 */
@Composable
fun RadarCanvas(
    selfName: String,
    peers: List<Peer>,
    onPeerTap: (Peer) -> Unit,
    modifier: Modifier = Modifier,
    onSelfTap: (() -> Unit)? = null,
    selfIcon: ImageVector = CdIcons.Phone,
) {
    val transition = rememberInfiniteTransition(label = "sonar")
    val sweep = transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "sweep",
    )
    val pulse = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "pulse",
    )
    val drift = transition.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(4300, easing = LinearEasing)), label = "float",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val maxR = (minOf(w - 20.dp, h - 8.dp) / 2).coerceAtLeast(70.dp)
        val cx = w / 2
        val cy = h / 2

        SonarBackdrop(maxR, sweep, pulse)

        val nearest = peers
            .filter { it.endpoint != null && it.vault == null && it.estimatedDistanceMeters != null }
            .minByOrNull { it.estimatedDistanceMeters!! }?.instanceId

        peers.forEach { peer ->
            val angle = angleFor(peer.instanceId)
            val r = maxR * radiusFraction(peer)
            val px = cx + r * cos(angle)
            val py = cy + r * sin(angle)
            // Keep the whole node (circle + two label lines) inside the box.
            val left = (px - NODE_W / 2).coerceIn(0.dp, (w - NODE_W).coerceAtLeast(0.dp))
            val top = (py - NODE_D / 2).coerceIn(0.dp, (h - NODE_H).coerceAtLeast(0.dp))
            val phase = (abs(peer.instanceId.hashCode()) % 7).toFloat()
            PeerNode(
                peer = peer,
                nearest = peer.instanceId == nearest,
                onTap = { onPeerTap(peer) },
                modifier = Modifier.offset {
                    val bob = sin(drift.value + phase) * 4.dp.toPx()
                    IntOffset(left.roundToPx(), (top.toPx() + bob).roundToInt())
                },
            )
        }

        SelfNode(
            name = selfName,
            icon = selfIcon,
            onTap = onSelfTap,
            modifier = Modifier.offset(x = cx - SELF_W / 2, y = cy - SELF_D / 2),
        )
    }
}

private val NODE_W = 112.dp
private val NODE_D = 56.dp
private val NODE_H = 98.dp
private val SELF_W = 140.dp
private val SELF_D = 76.dp

// ------------------------------------------------------------- backdrop --

@Composable
private fun SonarBackdrop(maxR: Dp, sweep: State<Float>, pulse: State<Float>) {
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = maxR.toPx()

        // sweep — a soft wedge with a crisp leading edge, rotating clockwise
        rotate(sweep.value, c) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent,
                    (70f / 360f) to Accent.copy(alpha = 0.25f),
                    (70.5f / 360f) to Color.Transparent,
                    1f to Color.Transparent,
                    center = c,
                ),
                radius = r, center = c,
            )
        }

        // two expanding pulses, half a cycle apart, eased out
        for (k in 0..1) {
            val p = (pulse.value + k * 0.5f) % 1f
            val e = 1f - (1f - p).pow(2.4f)
            drawCircle(
                Accent.copy(alpha = 0.7f * (1f - p)),
                radius = r * (0.35f + 0.65f * e), center = c,
                style = Stroke(1.5.dp.toPx()),
            )
        }

        // static rings
        drawCircle(Color.White.copy(alpha = 0.12f), r * 0.35f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.08f), r * 0.676f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.05f), r, c, style = Stroke(1.dp.toPx()))
    }
}

// ---------------------------------------------------------------- nodes --

@Composable
private fun SelfNode(name: String, icon: ImageVector, onTap: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier.width(SELF_W)
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = null, indication = ripple(bounded = false, radius = 64.dp),
                    role = Role.Button, onClickLabel = tr("Rename"), onClick = onTap,
                ) else Modifier,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (onTap != null) tr("Your device, {0}. Tap to rename", name) else tr("Your device, {0}", name)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(SELF_D), contentAlignment = Alignment.Center) {
            // 8dp halo ring (a spread shadow on a circle renders as a rounded square)
            Box(Modifier.requiredSize(SELF_D + 16.dp).clip(CircleShape).background(Accent.copy(alpha = 0.15f)))
            Box(
                Modifier.fillMaxSize()
                    .dropShadow(CircleShape, Shadow(48.dp, Accent.copy(alpha = 0.5f)))
                    .clip(CircleShape).background(Accent),
                contentAlignment = Alignment.Center,
            ) { CdIcon(icon, OnAccent, size = 28.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name, style = displayStyle(14, tracking = 0f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (onTap != null) tr("you · tap to rename") else tr("you"),
            color = TextDim, fontSize = 11.sp, lineHeight = 12.sp,
        )
    }
}

@Composable
private fun PeerNode(peer: Peer, nearest: Boolean, onTap: () -> Unit, modifier: Modifier) {
    val vault = peer.vault
    val reachable = peer.endpoint != null
    val distance = peer.estimatedDistanceMeters
    val name = vault?.vaultName ?: peer.name
    val icon: ImageVector = if (vault != null) CdIcons.Drive else deviceIconFor(peer.name)
    val dashed = vault == null && (distance == null || !reachable)

    val (sub, subColor) = when {
        vault != null -> (if (vault.needsPin) tr("Drive · PIN") else tr("Drive")) to Amber
        !reachable -> tr("Bluetooth only") to TextDim
        distance != null -> distanceLabel(distance) to (if (nearest) Accent else TextDim)
        else -> "Wi-Fi" to TextDim
    }
    val ringColor = when {
        vault != null -> Amber.copy(alpha = 0.5f)
        nearest -> Accent.copy(alpha = 0.5f)
        dashed -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val spokenBase = if (vault != null) tr("Open drive {0}", name) else tr("Send to {0}", name)
    val spokenDetail = when {
        vault != null && vault.needsPin -> tr("PIN protected")
        !reachable -> tr("seen over Bluetooth only, not reachable yet")
        distance != null -> tr("about {0} metres away", distance.roundToInt().coerceAtLeast(1))
        else -> tr("seen over Wi-Fi")
    }
    val spoken = tr("{0}, {1}", spokenBase, spokenDetail)

    Column(
        modifier.width(NODE_W)
            // Unbounded ripple: a clip here would also cut the node's glow.
            .clickable(
                interactionSource = null, indication = ripple(bounded = false, radius = 52.dp),
                enabled = reachable, role = Role.Button, onClick = onTap,
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .alpha(if (reachable) 1f else 0.6f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(NODE_D)
                .then(
                    if (nearest || vault != null)
                        Modifier.dropShadow(CircleShape, Shadow(24.dp, (if (vault != null) Amber else Accent).copy(alpha = 0.18f)))
                    else Modifier,
                )
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (dashed) 0.04f else 0.08f))
                .then(
                    if (dashed) Modifier.drawBehind {
                        val s = 1.dp.toPx()
                        drawCircle(
                            ringColor, radius = size.minDimension / 2 - s / 2,
                            style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    } else Modifier.border(1.dp, ringColor, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            CdIcon(
                icon,
                when {
                    vault != null -> Amber
                    icon == CdIcons.Wifi -> TextDim
                    else -> TextPrimary
                },
                size = 24.dp,
            )
        }
        Text(
            name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Text(sub, color = subColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------- math --

/** Best-guess device glyph from the advertised name. */
internal fun deviceIconFor(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n == UNNAMED_PEER.lowercase() || n.isBlank() -> CdIcons.Wifi
        "ipad" in n || "tablet" in n || n.startsWith("galaxy tab") || " tab " in " $n " -> CdIcons.Tablet
        "macbook" in n || "laptop" in n || "notebook" in n || "thinkpad" in n || "chromebook" in n -> CdIcons.Laptop
        "imac" in n || "desktop" in n || "windows" in n || "mac mini" in n || "mac studio" in n ||
            Regex("\\bpc\\b").containsMatchIn(n) || n == "mac" -> CdIcons.Monitor
        else -> CdIcons.Phone
    }
}

private fun distanceLabel(m: Double): String = if (m < 1.0) "< 1 m" else "≈ ${m.roundToInt()} m"

private fun angleFor(instanceId: String): Float {
    val h = instanceId.fold(7) { acc, c -> acc * 31 + c.code }
    return ((h and 0x7fffffff) % 360) * PI.toFloat() / 180f
}

/**
 * Fraction of the radar radius for a peer. BLE distance (0.3 m … 12 m,
 * log-compressed) maps to 0.48…0.92 so even the closest device clears the
 * centre node; no signal → the outer ring.
 */
private fun radiusFraction(peer: Peer): Float {
    val meters = peer.estimatedDistanceMeters ?: return 0.80f
    val n = ln(meters.coerceIn(0.3, 12.0) / 0.3) / ln(12.0 / 0.3)
    return 0.48f + n.toFloat() * 0.44f
}
