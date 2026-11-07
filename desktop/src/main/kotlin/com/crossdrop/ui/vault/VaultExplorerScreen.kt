package com.crossdrop.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.vault.VaultDirEntry
import com.crossdrop.platform.SystemActions
import com.crossdrop.net.VaultExplorerManager
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.IconArrow
import com.crossdrop.ui.IconCheck
import com.crossdrop.ui.theme.AuroraViolet
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native vault explorer: breadcrumbs, directory listing, batch download queue
 * into the inbox, uploads into `rw` vaults, and stream-in-place playback of
 * media through the loopback bridge.
 */
@Composable
fun VaultExplorerScreen(
    state: VaultExplorerManager.Ui,
    onBrowse: (String) -> Unit,
    onUp: () -> Unit,
    onToggleSelect: (VaultDirEntry) -> Unit,
    onDownload: (List<String>) -> Unit,
    onUpload: (List<java.io.File>) -> Unit,
    onStream: (String) -> String?,
    isMedia: (VaultDirEntry) -> Boolean,
    onClose: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        // ---- header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.peerName, color = TextPrimary, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(if (state.writable) "RW" else "RO",
                        if (state.writable) Phosphor else AuroraViolet)
                    Text("  encrypted vault session", color = TextDim, fontSize = 11.sp)
                }
            }
            if (state.writable && !state.pinRequired) {
                Surface(
                    shape = RoundedCornerShape(50), color = DeepSpaceSoft,
                    border = BorderStroke(1.dp, RippleTeal.copy(alpha = 0.4f)),
                    onClick = {
                        SystemActions.pickFiles(title = "Upload to ${state.peerName}")
                            .takeIf { it.isNotEmpty() }?.let(onUpload)
                    },
                ) {
                    Text("  ↑ upload  ", color = RippleTeal, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 7.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "✕", color = TextDim, fontSize = 18.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        }

        // ---- breadcrumb
        if (!state.pinRequired) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crumb("⌂") { onBrowse("") }
                val parts = state.path.split('/').filter { it.isNotEmpty() }
                var acc = ""
                parts.forEachIndexed { i, seg ->
                    acc += "/$seg"
                    Text(" › ", color = GlassEdge, fontSize = 13.sp)
                    val p = acc.trimStart('/')
                    if (i == parts.lastIndex) {
                        Text(seg, color = TextPrimary, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                    } else {
                        Crumb(seg) { onBrowse(p) }
                    }
                }
            }
        }

        // ---- content
        Box(Modifier.weight(1f)) {
            when {
                state.pinRequired -> PinGate(
                    failed = state.pinFailed,
                    pin = pin, onPin = { pin = it },
                    onUnlock = { onUnlock(pin) })
                state.loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = RippleTeal)
                    Spacer(Modifier.height(10.dp))
                    Text("reading drive…", color = TextDim, fontSize = 12.sp)
                }
                state.error != null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = DangerSoft, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { onBrowse(state.path) }) {
                        Text("retry", color = RippleTeal)
                    }
                }
                state.entries.isEmpty() -> Text(
                    "Empty folder", color = TextDim, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn {
                    items(state.entries, key = { it.name }) { e ->
                        EntryRow(
                            e = e,
                            dir = state.path,
                            selected = joinKey(state.path, e.name) in state.selected,
                            onClick = {
                                if (e.isDir) onBrowse(joinKey(state.path, e.name))
                                else if (isMedia(e)) {
                                    onStream(joinKey(state.path, e.name))
                                        ?.let(SystemActions::openUrl)
                                } else onDownload(listOf(joinKey(state.path, e.name)))
                            },
                            onToggle = { onToggleSelect(e) },
                        )
                    }
                }
            }
        }

        // ---- transfer queue strip
        val jobs = state.jobs.filter { !it.done || it.failed != null }
        if (jobs.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp), color = DeepSpaceRaised,
                border = BorderStroke(1.dp, GlassEdge),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    jobs.take(3).forEach { j ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconArrow(
                                if (j.failed != null) DangerSoft else FluidCyan,
                                up = j.uploading, size = 12.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(j.name, color = TextPrimary, fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                j.failed ?: if (j.done) "done"
                                else "${formatBytes(j.received)} / ${formatBytes(j.total)}",
                                color = j.failed?.let { DangerSoft } ?: TextDim,
                                fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ---- selection bar
        if (state.selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${state.selected.size} selected", color = TextDim, fontSize = 13.sp)
                Button(
                    onClick = { onDownload(state.selected.toList()) },
                    colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
                ) { Text("Download", color = DeepSpace) }
            }
        }
    }
}

private fun joinKey(dir: String, name: String) =
    if (dir.isEmpty()) name else "$dir/$name"

@Composable
private fun Crumb(text: String, onClick: () -> Unit) {
    Text(text, color = RippleTeal, fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(2.dp))
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(50), color = DeepSpace,
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun EntryRow(
    e: VaultDirEntry,
    dir: String,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeTile(if (e.isDir) "dir" else e.name, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                if (e.isDir) "folder"
                else "${formatBytes(e.size)} · " +
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        .format(Date(e.mtime * 1000)),
                color = TextDim, fontSize = 11.sp)
        }
        Surface(
            shape = CircleShape,
            color = if (selected) RippleTeal else DeepSpaceSoft,
            border = BorderStroke(1.dp,
                if (selected) RippleTeal else GlassEdge),
            onClick = onToggle,
            modifier = Modifier.size(24.dp),
        ) {
            if (selected) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconCheck(DeepSpace, size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun PinGate(
    failed: Boolean, pin: String, onPin: (String) -> Unit, onUnlock: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 30.sp)
        Spacer(Modifier.height(10.dp))
        Text("This drive is PIN-protected", color = TextPrimary,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("the host shows a 4-digit code", color = TextDim, fontSize = 12.sp)
        if (failed) {
            Text("wrong PIN — try again", color = DangerSoft, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 4) onPin(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(160.dp),
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onUnlock, enabled = pin.length == 4,
            colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
        ) { Text("Unlock", color = DeepSpace) }
    }
}
