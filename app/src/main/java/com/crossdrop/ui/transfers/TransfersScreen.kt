package com.crossdrop.ui.transfers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionState
import com.crossdrop.net.TransferGroup
import com.crossdrop.net.isTerminal
import com.crossdrop.storage.HistoryStore
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.ConfirmDialog
import com.crossdrop.ui.Dot
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.LocalNavInset
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.ScreenTitle
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.StatusPill
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.AccentText
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Live session (if any) + the persisted log of finished transfers, grouped
 * into Today / Earlier. Tapping the live card reopens the transfer view.
 */
@Composable
fun TransfersScreen(
    groups: List<TransferGroup>,
    history: HistoryStore,
    onClearHistory: () -> Unit,
    refreshSignal: Any?,
    onOpenActive: ((groupId: String) -> Unit)? = null,
) {
    val entries = remember(refreshSignal) { history.list() }
    val active = groups.filter { !it.isTerminal }
    val todayStart = remember(refreshSignal) { startOfDay(0) }
    val (today, earlier) = entries.partition { it.ts >= todayStart }
    var confirmClear by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.13f, centerX = 0.85f, centerY = 50.dp, radius = 210.dp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 28.dp, bottom = LocalNavInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScreenTitle("Transfers", Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        ChipButton(
                            "Clear", { confirmClear = true }, height = 40.dp,
                            bg = GlassMid, border = HairlineStrong,
                        )
                    }
                }
            }
            active.forEach { g ->
                item(key = g.id) {
                    ActiveCard(g, onOpenActive?.let { open -> { open(g.id) } }, Modifier.widthIn(max = 640.dp))
                }
            }
            if (entries.isEmpty() && active.isEmpty()) {
                item { EmptyState(Modifier.widthIn(max = 640.dp)) }
            }
            if (today.isNotEmpty()) {
                item { SectionLabel("Today", Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 6.dp)) }
                item { HistoryGroup(today, Modifier.widthIn(max = 640.dp)) }
            }
            if (earlier.isNotEmpty()) {
                item { SectionLabel("Earlier", Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 6.dp)) }
                item { HistoryGroup(earlier, Modifier.widthIn(max = 640.dp)) }
            }
            if (entries.isNotEmpty()) {
                item {
                    FootNote(
                        "Stored on this device only. Keeps your last ${HistoryStore.MAX_ENTRIES} sessions.",
                        Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear transfer history?",
            text = "This forgets the log of past transfers. Received files stay in your inbox.",
            confirmLabel = "Clear",
            onConfirm = { confirmClear = false; onClearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ActiveCard(g: TransferGroup, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val s = g.primary
    val pct = (g.fraction * 100).toInt()
    GlassCard(
        modifier.fillMaxWidth(),
        radius = 26.dp, fill = GlassMid, border = Accent.copy(alpha = 0.4f),
        glow = Accent.copy(alpha = 0.13f), glowRadius = 40.dp,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        onClick = onOpen, onClickLabel = "Open transfer",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(Accent, size = 8.dp)
            Text(
                when {
                    g.multi -> "Sending to ${g.sessions.size} devices"
                    s.direction == Direction.SENDING -> "Sending to ${s.peerName}"
                    else -> "Receiving from ${s.peerName}"
                },
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text("$pct%", style = displayStyle(20, color = Accent))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(GlassHigh)) {
            Box(
                Modifier.fillMaxWidth(g.fraction).height(6.dp)
                    .dropShadow(RoundedCornerShape(3.dp), Shadow(12.dp, Accent.copy(alpha = 0.6f)))
                    .clip(RoundedCornerShape(3.dp)).background(Accent),
            )
        }
        Text(
            (if (g.multi) listOfNotNull(
                "${g.completed} of ${g.sessions.size} delivered",
                g.bytesPerSec.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
            ) else listOfNotNull(
                s.currentFileName ?: "Establishing a secure channel…",
                s.fileProgressLabel(),
                s.bytesPerSec.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
            )).joinToString(" · "),
            color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "file 2 of 3" from the offer manifest, when the session has more than one file. */
fun SessionState.fileProgressLabel(): String? {
    val files = offer?.files ?: return null
    if (files.size < 2) return null
    val idx = files.indexOfFirst { it.name == currentFileName }
    return if (idx >= 0) "file ${idx + 1} of ${files.size}" else "${files.size} files"
}

@Composable
private fun HistoryGroup(entries: List<HistoryStore.Entry>, modifier: Modifier = Modifier) {
    GroupCard(modifier) {
        entries.forEachIndexed { i, e ->
            if (i > 0) RowSeparator()
            HistoryRow(e)
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryStore.Entry) {
    val browser = e.peerName == "browser share"
    val received = !e.isSend
    val (pillFg, pillBg) = when (e.status) {
        "COMPLETED" -> AccentText to Accent.copy(alpha = 0.14f)
        "FAILED" -> DangerText to Color(0x24FF6B6B)
        else -> TextSecondary to GlassHigh
    }
    Row(
        Modifier.fillMaxWidth().padding(10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (received) Accent.copy(alpha = 0.14f) else GlassHigh)
                .semantics { contentDescription = if (browser) "Browser share" else if (received) "Received" else "Sent" },
            contentAlignment = Alignment.Center,
        ) {
            CdIcon(
                when {
                    browser -> CdIcons.Qr
                    received -> CdIcons.ArrowDown
                    else -> CdIcons.ArrowUp
                },
                if (received) Accent else TextPrimary, size = 18.dp,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (browser) "Browser share" else e.peerName,
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (e.files > 1) "${e.files} files" else e.summary) + " · " + formatBytes(e.bytes),
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val (time, mono) = formatWhen(e.ts)
            Text(time, style = if (mono) monoStyle(11, TextDim) else monoStyle(11, TextDim).copy(fontFamily = null))
            StatusPill(statusLabel(e.status), pillFg, pillBg)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(), fill = GlassMid.copy(alpha = 0.04f),
        contentPadding = PaddingValues(vertical = 36.dp, horizontal = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(GlassHigh),
                contentAlignment = Alignment.Center,
            ) { CdIcon(CdIcons.Transfers, TextSecondary, size = 26.dp) }
            Spacer(Modifier.height(14.dp))
            Text("No transfers yet", style = displayStyle(17))
            Spacer(Modifier.height(4.dp))
            Text("Tap a device on the radar to send something.", color = TextDim, fontSize = 13.sp)
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    "COMPLETED" -> "Completed"
    "FAILED" -> "Failed"
    "DECLINED" -> "Declined"
    "CANCELLED" -> "Cancelled"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -daysAgo)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Clock time today (monospaced), else "Yesterday" / weekday / date. */
private fun formatWhen(ts: Long): Pair<String, Boolean> = when {
    ts >= startOfDay(0) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) to true
    ts >= startOfDay(1) -> "Yesterday" to false
    ts >= startOfDay(6) -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts)) to false
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts)) to false
}
