package com.crossdrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.share.QrCode
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Ink

/**
 * QR on a white card with an accent halo. Scanners need a light quiet zone,
 * so this is the one bright surface in the dark UI. [size] is the outer card.
 */
@Composable
fun QrCard(
    url: String,
    size: Dp = 236.dp,
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    description: String = tr("QR code for {0}", url),
) {
    val qr = remember(url) { runCatching { QrCode.encode(url) }.getOrNull() }
    val radius = if (size >= 160.dp) 32.dp else 18.dp
    val pad = if (size >= 160.dp) 20.dp else 10.dp
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.size(size)
            .then(
                if (halo) Modifier
                    .dropShadow(shape, Shadow(70.dp, Accent.copy(alpha = 0.25f), offset = DpOffset(0.dp, 20.dp)))
                    .dropShadow(shape, Shadow(0.dp, Accent.copy(alpha = 0.2f), spread = 8.dp))
                else Modifier,
            )
            .clip(shape)
            .background(Color.White)
            .padding(pad)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (qr == null) return@Canvas
            val dim = this.size
            val module = minOf(dim.width, dim.height) / qr.size
            val ox = (dim.width - module * qr.size) / 2f
            val oy = (dim.height - module * qr.size) / 2f
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    drawRect(
                        Ink,
                        topLeft = Offset(ox + x * module, oy + y * module),
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}
