package com.crossdrop.ui.transfer

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionState
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.isTerminal
import com.crossdrop.sensor.TiltSource
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.FluidCyan
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary

/**
 * Full-screen transfer view built around [LiquidOrb]. Collects accelerometer
 * input while visible so the fluid reacts to the device physically moving.
 */
@Composable
fun TransferScreen(
    session: SessionState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val tiltSource = androidx.compose.runtime.remember { TiltSource(context) }
    DisposableEffect(Unit) {
        tiltSource.start()
        onDispose { tiltSource.stop() }
    }
    val tilt by tiltSource.tilt.collectAsState()

    // Throughput → turbulence: saturate around ~60 MB/s.
    val turbulence = (session.bytesPerSec / 60e6).toFloat().coerceIn(0f, 1f)
        .let { it * 0.8f + tilt.energy * 0.4f }.coerceIn(0f, 1f)
    val completed = session.status == SessionStatus.COMPLETED

    LaunchedEffect(session.status) {
        if (completed) {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.LONG_PRESS
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (session.direction) {
                    Direction.SENDING -> "Sending to ${session.peerName}"
                    Direction.RECEIVING -> "Receiving from ${session.peerName}"
                },
                color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            session.sas?.let {
                Spacer(Modifier.height(8.dp))
                SasChip(it)
            }
        }

        // orb
        LiquidOrb(
            progress = if (completed) 1f else session.fraction,
            turbulence = turbulence,
            tilt = tilt,
            completed = completed,
        )

        // stats + actions
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                statusLine(session),
                color = statusColor(session.status),
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            session.currentFileName?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = TextDim, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(session.bytesDone)} / ${formatBytes(session.bytesTotal)}" +
                    if (session.bytesPerSec > 0) "  ·  ${formatBytes(session.bytesPerSec)}/s" else "",
                color = TextDim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row {
                when {
                    session.status.isTerminal -> Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
                    ) { Text("Done", color = com.crossdrop.ui.theme.DeepSpace) }
                    else -> TextButton(onClick = onCancel) {
                        Text("Cancel transfer", color = com.crossdrop.ui.theme.DangerSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun SasChip(sas: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = DeepSpaceSoft,
    ) {
        Text(
            "verify  $sas",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = Phosphor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
    }
}

private fun statusLine(s: SessionState): String = when (s.status) {
    SessionStatus.CONNECTING -> "Establishing secure channel…"
    SessionStatus.AWAITING_ACCEPT -> "Waiting for ${s.peerName} to accept…"
    SessionStatus.TRANSFERRING -> "${(s.fraction * 100).toInt()}%"
    SessionStatus.COMPLETED -> "Transfer complete"
    SessionStatus.FAILED -> "Failed — ${s.error ?: "unknown error"}"
    SessionStatus.DECLINED -> "${s.peerName} declined"
    SessionStatus.CANCELLED -> "Cancelled"
}

private fun statusColor(status: SessionStatus) = when (status) {
    SessionStatus.COMPLETED -> Phosphor
    SessionStatus.FAILED, SessionStatus.DECLINED -> com.crossdrop.ui.theme.DangerSoft
    SessionStatus.TRANSFERRING -> FluidCyan
    else -> TextDim
}

internal fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}
