package com.crossdrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crossdrop.core.share.QrCode
import com.crossdrop.ui.theme.AuroraBrush
import com.crossdrop.ui.theme.DeepSpace

/** QR drawn into a light card — scanners need a light quiet zone, so the dark theme inverts here. */
@Composable
fun QrCard(url: String, size: Dp = 252.dp) {
    val qr = remember(url) { runCatching { QrCode.encode(url) }.getOrNull() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF2FBFF),
        modifier = Modifier.size(size)
            .border(2.dp, AuroraBrush, RoundedCornerShape(24.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            if (qr == null) return@Canvas
            val dim = this.size   // DrawScope.size — the outer `size` is the Dp param
            val module = minOf(dim.width, dim.height) / qr.size
            val ox = (dim.width - module * qr.size) / 2f
            val oy = (dim.height - module * qr.size) / 2f
            drawRoundRect(
                Color(0xFFF2FBFF),
                topLeft = Offset.Zero, size = dim,
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    drawRect(
                        DeepSpace,
                        topLeft = Offset(ox + x * module, oy + y * module),
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}
