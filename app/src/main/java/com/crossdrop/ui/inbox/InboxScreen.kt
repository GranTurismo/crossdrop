package com.crossdrop.ui.inbox

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.crossdrop.storage.InboxStore
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/**
 * Full-screen inbox: received files staged in the app sandbox.
 * Open via FileProvider, or shred (zero-fill + unlink).
 */
@Composable
fun InboxScreen(
    inbox: InboxStore,
    onChanged: () -> Unit,
    refreshSignal: Any?,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val files = remember(refreshSignal, tick) { inbox.list() }
    val totalBytes = files.sumOf { it.size }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Inbox", color = TextPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold)
                Text(
                    if (files.isEmpty()) "files you accept land here"
                    else "${files.size} file${if (files.size == 1) "" else "s"} · ${formatBytes(totalBytes)}",
                    color = TextDim, fontSize = 12.sp,
                )
            }
            if (files.isNotEmpty()) {
                TextButton(onClick = { inbox.shredAll(); tick++; onChanged() }) {
                    Text("Wipe all", color = DangerSoft, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("◌", color = TextDim.copy(alpha = 0.5f), fontSize = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text("Nothing received yet.", color = TextDim, fontSize = 13.sp)
                Text(
                    "Files stay in the app sandbox until you open or delete them.",
                    color = TextDim.copy(alpha = 0.7f), fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = DeepSpaceRaised,
                        border = BorderStroke(1.dp, GlassEdge),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileTypeTile(item.name)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name, color = TextPrimary, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatBytes(item.size)} · ${item.receivedAt}",
                                    color = TextDim, fontSize = 12.sp,
                                )
                            }
                            TextButton(onClick = { openFile(context, inbox, item) }) {
                                Text("Open", color = RippleTeal, fontSize = 13.sp)
                            }
                            TextButton(onClick = { inbox.shred(item.file); tick++; onChanged() }) {
                                Text("✕", color = DangerSoft, fontSize = 14.sp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun openFile(context: android.content.Context, inbox: InboxStore, item: InboxStore.ReceivedFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", item.file,
        )
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
