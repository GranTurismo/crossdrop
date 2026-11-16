package com.crossdrop.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.crossdrop.R

// Platform half of the type system (the desktop module has its own Fonts.kt
// loading the same files from resources). Both are Latin subsets, SemiBold
// only — see assets/licenses/FONTS-OFL.txt.

/** Sora — display face for titles and big numbers. */
val DisplayFont = FontFamily(
    Font(R.font.sora_semibold, FontWeight.Medium),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_semibold, FontWeight.Bold),
)

/** JetBrains Mono — figures: sizes, speeds, codes, addresses. */
val MonoFont = FontFamily(
    Font(R.font.jetbrains_mono_semibold, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)
