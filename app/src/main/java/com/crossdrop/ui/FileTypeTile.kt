package com.crossdrop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.ui.theme.AuroraViolet
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.NebulaPink
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim

/** Small tinted tile showing a file's extension — the app's file-type glyph. */
@Composable
fun FileTypeTile(fileName: String, size: Dp = 42.dp) {
    val ext = fileName.substringAfterLast('.', "").take(4).uppercase().ifBlank { "···" }
    val tint = tintFor(ext.lowercase())
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = tint.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                ext, color = tint, fontSize = if (ext.length > 3) 8.sp else 9.5.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
            )
        }
    }
}

private fun tintFor(ext: String): Color = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "svg" -> FluidCyan
    "mp4", "mkv", "mov", "webm", "avi" -> AuroraViolet
    "mp3", "m4a", "flac", "ogg", "wav", "opus" -> Phosphor
    "pdf", "doc", "docx", "txt", "md", "epub" -> NebulaPink
    "apk" -> RippleTeal
    "zip", "rar", "7z", "tar", "gz" -> AuroraViolet
    else -> TextDim
}
