package com.crossdrop.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Sonar palette — dark-only by design. Near-black ink, one luminous mint
// accent, translucent "glass" surfaces (white at 4–10 %) and three quiet
// category hues (amber, violet, sky) for file types and status.
//
// Type: Sora for display (titles, big numbers), the platform sans for body,
// JetBrains Mono for figures (sizes, speeds, codes, addresses). The two
// bundled families are declared per platform in Fonts.kt.

val Ink = Color(0xFF06080C)
val InkSheet = Color(0xFF13171D)
val InkNav = Color(0xFF161A20)

val Accent = Color(0xFF5CF2C0)
val OnAccent = Color(0xFF04130D)
val OnAccentMuted = Color(0xFF0C3A2B)
val AccentText = Color(0xFF7CF5CD)

val TextPrimary = Color(0xFFF4F7F9)
val TextSecondary = Color(0xFFC8D0D8)
val TextDim = Color(0xFF9AA6B2)
val TextFaint = Color(0xFF8B97A3)
val NavIdle = Color(0xFFAEB8C2)

val Amber = Color(0xFFFFC163)
val Violet = Color(0xFFA48BFF)
val VioletText = Color(0xFFC3B5FF)
val Sky = Color(0xFF4CC9FF)
val SkyText = Color(0xFF7FD8FF)
val Danger = Color(0xFFFF6B6B)
val DangerText = Color(0xFFFF9B9B)

/** Glass fills — white at low alpha over [Ink]. */
val GlassLow = Color.White.copy(alpha = 0.04f)
val Glass = Color.White.copy(alpha = 0.05f)
val GlassMid = Color.White.copy(alpha = 0.06f)
val GlassRaised = Color.White.copy(alpha = 0.07f)
val GlassHigh = Color.White.copy(alpha = 0.08f)

/** Hairline borders. */
val HairlineSoft = Color.White.copy(alpha = 0.06f)
val Hairline = Color.White.copy(alpha = 0.08f)
val HairlineStrong = Color.White.copy(alpha = 0.10f)

/**
 * One soft radial glow behind a screen's content — the design's ambient
 * light. [centerX] is a fraction of the width; [centerY] and [radius] are
 * absolute so the glow sits at the same spot on every phone.
 */
@Composable
fun ScreenGlow(
    modifier: Modifier = Modifier,
    color: Color = Accent,
    alpha: Float = 0.18f,
    centerX: Float = 0.5f,
    centerY: Dp = 300.dp,
    radius: Dp = 250.dp,
) {
    Canvas(modifier.fillMaxSize()) {
        val c = Offset(size.width * centerX, centerY.toPx())
        val r = radius.toPx()
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = r),
            radius = r, center = c,
        )
    }
}

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Sky,
    onSecondary = OnAccent,
    tertiary = Violet,
    onTertiary = OnAccent,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkSheet,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF12161B),
    onSurfaceVariant = TextDim,
    surfaceContainerHigh = InkSheet,
    surfaceContainerHighest = Color(0xFF1B2128),
    outline = HairlineStrong,
    outlineVariant = Hairline,
    surfaceTint = Color.Transparent,
    error = DangerText,
    scrim = Color(0xB3040609),
)

private val typography = Typography().let { t ->
    // Body/label styles: no extra tracking, proportional leading — the design
    // sets explicit sizes everywhere, so the line height has to scale with them.
    fun TextStyle.tight() = copy(letterSpacing = 0.sp, lineHeight = 1.35.em)
    t.copy(
        bodyLarge = t.bodyLarge.tight(),
        bodyMedium = t.bodyMedium.tight(),
        bodySmall = t.bodySmall.tight(),
        labelLarge = t.labelLarge.tight(),
        labelMedium = t.labelMedium.tight(),
        labelSmall = t.labelSmall.tight(),
        headlineLarge = t.headlineLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Letter-spacing in em, dropped for scripts whose letters must stay joined or shaped (Arabic, Persian, Hindi,
 * Georgian): tracking there detaches the letters. Use this for every `letterSpacing` on text that gets translated.
 */
fun trackingEm(em: Float): androidx.compose.ui.unit.TextUnit =
    if (com.crossdrop.core.i18n.I18n.isCursiveOrCaseless()) 0.em else em.em

/** Capitals for small labels, only where the script has them (Turkish gets its dotted İ). */
fun String.caps(): String =
    if (com.crossdrop.core.i18n.I18n.isCursiveOrCaseless()) this
    else uppercase(java.util.Locale.forLanguageTag(com.crossdrop.core.i18n.I18n.language()))

/** Display text (Sora) — titles and big figures. Sora is Latin-only; other scripts fall back to the system font. */
fun displayStyle(size: Int, tracking: Float = -0.03f, color: Color = TextPrimary) = TextStyle(
    fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
    fontSize = size.sp, letterSpacing = trackingEm(tracking), color = color,
    // Devanagari, Arabic and Georgian glyphs are taller than Latin ones and clip at a tight line height.
    lineHeight = (size * if (com.crossdrop.core.i18n.I18n.isCursiveOrCaseless()) 1.4f else 1.1f).sp,
)

/** Figures (JetBrains Mono) — sizes, speeds, codes, addresses. */
fun monoStyle(size: Int, color: Color = TextPrimary) = TextStyle(
    fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = size.sp, color = color,
    // No code ligatures: "://" and "//" must read as typed in addresses.
    fontFeatureSettings = "liga 0, calt 0",
)

@Composable
fun CrossDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
