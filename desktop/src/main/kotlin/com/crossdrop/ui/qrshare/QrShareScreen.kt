package com.crossdrop.ui.qrshare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.share.QrCode
import com.crossdrop.net.QrShareManager
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/**
 * "Send to anyone" overlay: the receiver scans the QR with any camera app and
 * gets a browser-hosted file list — no CrossDrop install needed on their end.
 * Screen is kept awake while showing so the QR stays scannable.
 */
@Composable
fun QrShareScreen(
    state: QrShareManager.State,
    onStop: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "S H A R E   T O   A N Y O N E",
            color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Scan with any camera — no app needed",
            color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(22.dp))

        com.crossdrop.ui.QrCard(state.url)
        Spacer(Modifier.height(14.dp))

        SelectionContainer {
            Text(
                state.url,
                color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(state)
        Spacer(Modifier.height(18.dp))

        // shared files
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DeepSpaceRaised,
            border = BorderStroke(1.dp, GlassEdge),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                state.files.take(6).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileTypeTile(f.name, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            f.name, color = TextPrimary, fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(formatBytes(f.size), color = TextDim, fontSize = 12.sp)
                    }
                }
                if (state.files.size > 6) {
                    Text(
                        "+ ${state.files.size - 6} more",
                        color = TextDim, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            "Both devices must stay on this Wi-Fi. The link stops working when you close this screen.",
            color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (state.delivered) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
            ) { Text("Done", color = DeepSpace) }
        } else {
            TextButton(onClick = onStop) { Text("Stop sharing", color = DangerSoft) }
        }
    }
}

@Composable
private fun StatusLine(state: QrShareManager.State) {
    val (text, color) = when {
        state.delivered -> "Delivered — all files downloaded" to Phosphor
        state.downloadsStarted > 0 ->
            "${state.downloadsDone} of ${state.downloadsStarted} downloads done · ${formatBytes(state.bytesServed)}" to FluidCyan
        state.connected -> "Receiver connected — waiting for downloads" to RippleTeal
        else -> "Waiting for a scan…" to TextDim
    }
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}


