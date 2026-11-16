package com.crossdrop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// Desktop half of the type system (the Android app declares the same
// families from res/font). Latin subsets, SemiBold only — see
// resources/fonts/FONTS-OFL.txt.

/** Sora — display face for titles and big numbers. */
val DisplayFont = FontFamily(
    Font("fonts/sora_semibold.ttf", FontWeight.Medium),
    Font("fonts/sora_semibold.ttf", FontWeight.SemiBold),
    Font("fonts/sora_semibold.ttf", FontWeight.Bold),
)

/** JetBrains Mono — figures: sizes, speeds, codes, addresses. */
val MonoFont = FontFamily(
    Font("fonts/jetbrains_mono_semibold.ttf", FontWeight.Medium),
    Font("fonts/jetbrains_mono_semibold.ttf", FontWeight.SemiBold),
)
