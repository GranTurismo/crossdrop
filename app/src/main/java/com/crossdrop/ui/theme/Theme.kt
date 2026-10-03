package com.crossdrop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Deep-space palette: the app is dark-only by design (ambient sonar aesthetic).
val DeepSpace = Color(0xFF05070F)
val DeepSpaceSoft = Color(0xFF0B1020)
val DeepSpaceRaised = Color(0xFF111A30)
val SurfaceHigh = Color(0xFF182242)
val RippleTeal = Color(0xFF2EE6C8)
val RippleTealDim = Color(0xFF1AA08C)
val FluidCyan = Color(0xFF4FD8FF)
val AuroraViolet = Color(0xFF8B7CFF)
val NebulaPink = Color(0xFFFF7CB8)
val Phosphor = Color(0xFF9FFFF0)
val TextPrimary = Color(0xFFE8F0F2)
val TextDim = Color(0xFF8A96A3)
val DangerSoft = Color(0xFFFF6B6B)
val GlassEdge = Color(0x33B8FFF3)
val GlassFill = Color(0x14B8EFFF)

/** Signature gradient — teal → cyan → violet, used for accents and the wordmark. */
val AuroraBrush: Brush = Brush.linearGradient(
    listOf(RippleTeal, FluidCyan, AuroraViolet),
)

/**
 * Tonal card surface — a faint top-down light gradient on the raised tone,
 * the modern alternative to a hard hairline border.
 */
val CardGradient: Brush = Brush.verticalGradient(
    listOf(Color(0xFF1A2647), Color(0xFF131C38)),
)

/** Aurora-tinted tile background for icon chips. */
fun tintGradient(tint: Color): Brush = Brush.linearGradient(
    listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.07f)),
)

private val scheme = darkColorScheme(
    primary = RippleTeal,
    onPrimary = DeepSpace,
    secondary = FluidCyan,
    tertiary = AuroraViolet,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = DeepSpaceSoft,
    onSurface = TextPrimary,
    surfaceVariant = DeepSpaceRaised,
    onSurfaceVariant = TextDim,
    error = DangerSoft,
)

@Composable
fun CrossDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
