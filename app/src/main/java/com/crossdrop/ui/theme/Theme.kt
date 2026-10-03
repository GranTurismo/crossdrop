package com.crossdrop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deep-space palette: the app is dark-only by design (ambient sonar aesthetic).
val DeepSpace = Color(0xFF05070F)
val DeepSpaceSoft = Color(0xFF0B1020)
val RippleTeal = Color(0xFF2EE6C8)
val RippleTealDim = Color(0xFF1AA08C)
val FluidCyan = Color(0xFF4FD8FF)
val Phosphor = Color(0xFF9FFFF0)
val TextPrimary = Color(0xFFE8F0F2)
val TextDim = Color(0xFF8A96A3)
val DangerSoft = Color(0xFFFF6B6B)
val GlassEdge = Color(0x33B8FFF3)

private val scheme = darkColorScheme(
    primary = RippleTeal,
    onPrimary = DeepSpace,
    secondary = FluidCyan,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = DeepSpaceSoft,
    onSurface = TextPrimary,
    surfaceVariant = DeepSpaceSoft,
    onSurfaceVariant = TextDim,
    error = DangerSoft,
)

@Composable
fun CrossDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
