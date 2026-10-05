package com.crossdrop.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Neon Aurora palette: dark-only by design. A deep indigo night sky lit by
// luminous aurora — mint-green rays, electric cyan, ultraviolet, hot magenta
// curtain edges.
val DeepSpace = Color(0xFF030014)
val DeepSpaceSoft = Color(0xFF0A0620)
val DeepSpaceRaised = Color(0xFF141033)
val SurfaceHigh = Color(0xFF201851)
val RippleTeal = Color(0xFF3BFFB0)
val RippleTealDim = Color(0xFF1FBF8A)
val FluidCyan = Color(0xFF45E5FF)
val AuroraViolet = Color(0xFF9D6BFF)
val NebulaPink = Color(0xFFFF4FD8)
val Phosphor = Color(0xFFB8FFE9)
val TextPrimary = Color(0xFFEEF2FF)
val TextDim = Color(0xFF8B93BB)
val DangerSoft = Color(0xFFFF5470)
val GlassEdge = Color(0x3D8BFFDC)
val GlassFill = Color(0x1478E0FF)

/** Signature aurora sweep — mint → cyan → violet → magenta. */
val AuroraBrush: Brush = Brush.linearGradient(
    listOf(RippleTeal, FluidCyan, AuroraViolet, NebulaPink),
)

/**
 * Tonal card surface — a faint top-down light gradient on the raised indigo
 * tone, the modern alternative to a hard hairline border.
 */
val CardGradient: Brush = Brush.verticalGradient(
    listOf(Color(0xFF1C1550), Color(0xFF100A2E)),
)

/** Aurora-tinted tile background for icon chips. */
fun tintGradient(tint: Color): Brush = Brush.linearGradient(
    listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.08f)),
)

/**
 * Ambient aurora haze for full-screen overlays — three faint colored glows
 * (mint high-left, violet right, cyan low) over the night base. Cheap,
 * static, and sits behind content without competing for attention.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(RippleTeal.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.08f), radius = w * 0.95f),
        )
        drawRect(
            Brush.radialGradient(
                listOf(AuroraViolet.copy(alpha = 0.11f), Color.Transparent),
                center = Offset(w * 0.88f, h * 0.30f), radius = w * 0.85f),
        )
        drawRect(
            Brush.radialGradient(
                listOf(FluidCyan.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * 0.45f, h * 0.95f), radius = w * 1.1f),
        )
        drawRect(
            Brush.radialGradient(
                listOf(NebulaPink.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(w * 0.75f, h * 0.78f), radius = w * 0.7f),
        )
    }
}

private val scheme = darkColorScheme(
    primary = RippleTeal,
    onPrimary = DeepSpace,
    secondary = FluidCyan,
    onSecondary = DeepSpace,
    tertiary = AuroraViolet,
    onTertiary = DeepSpace,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = DeepSpaceSoft,
    onSurface = TextPrimary,
    surfaceVariant = DeepSpaceRaised,
    onSurfaceVariant = TextDim,
    outline = GlassEdge,
    surfaceTint = RippleTeal,
    error = DangerSoft,
)

@Composable
fun CrossDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
