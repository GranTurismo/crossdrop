package com.crossdrop.ui.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.SessionState
import com.crossdrop.net.isTerminal
import com.crossdrop.storage.HistoryStore
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Live session (if any) + the persisted log of finished transfers. */
@Composable
fun TransfersScreen(
    session: SessionState?,
    history: HistoryStore,
    onClearHistory: () -> Unit,
    refreshSignal: Any?,
) {
    val entries = remember(refreshSignal) { history.list() }
    val active = session?.takeIf { !it.status.isTerminal }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Transfers", color = TextPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold)
                Text(
                    if (active != null) "1 in progress" else "${entries.size} finished",
                    color = TextDim, fontSize = 12.sp,
                )
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Text("Clear", color = DangerSoft, fontSize = 13.sp)
                }
            }
        }

        active?.let { s ->
            Spacer(Modifier.height(8.dp))
            ActiveCard(s)
        }

        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⇅", color = TextDim.copy(alpha = 0.5f), fontSize = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text("No transfers yet.", color = TextDim, fontSize = 13.sp)
                Text("Tap a device on the radar to send something.",
                    color = TextDim.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { e -> HistoryRow(e) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ActiveCard(s: SessionState) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DeepSpaceRaised,
        border = BorderStroke(1.dp, RippleTeal.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.direction.name == "SENDING") "↑" else "↓",
                    color = RippleTeal, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.peerName, color = TextPrimary, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        s.currentFileName ?: "establishing channel…",
                        color = TextDim, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${(s.fraction * 100).toInt()}%",
                    color = FluidCyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            // thin fluid bar
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(DeepSpaceSoft),
            ) {
                Box(
                    Modifier.fillMaxWidth(s.fraction).height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(RippleTeal),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(s.bytesDone)} / ${formatBytes(s.bytesTotal)}" +
                    if (s.bytesPerSec > 0) "  ·  ${formatBytes(s.bytesPerSec)}/s" else "",
                color = TextDim, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryStore.Entry) {
    val tint = when (e.status) {
        "COMPLETED" -> if (e.isSend) RippleTeal else FluidCyan
        "FAILED" -> DangerSoft
        "DECLINED" -> Phosphor
        else -> TextDim
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DeepSpaceRaised,
        border = BorderStroke(1.dp, GlassEdge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = tint.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (e.isSend) "↑" else "↓",
                        color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.summary, color = TextPrimary, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (e.isSend) "to" else "from"} ${e.peerName}" +
                        if (e.files > 1) " · ${e.files} files" else "",
                    color = TextDim, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(statusLabel(e.status), color = tint, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
                Text(
                    "${formatBytes(e.bytes)} · ${formatTime(e.ts)}",
                    color = TextDim, fontSize = 11.sp,
                )
            }
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    "COMPLETED" -> "done"
    "FAILED" -> "failed"
    "DECLINED" -> "declined"
    "CANCELLED" -> "cancelled"
    else -> status.lowercase()
}

private fun formatTime(ts: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return SimpleDateFormat(
        if (ts >= today) "HH:mm" else "MMM d, HH:mm",
        Locale.getDefault(),
    ).format(Date(ts))
}
