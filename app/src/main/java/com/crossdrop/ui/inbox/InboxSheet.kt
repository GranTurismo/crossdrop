package com.crossdrop.ui.inbox

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/** Received files: open via FileProvider, or shred (zero-fill + delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxSheet(
    inbox: InboxStore,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val files = inbox.list()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpaceSoft) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Inbox", color = TextPrimary, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${files.size} file${if (files.size == 1) "" else "s"} received",
                        color = TextDim, fontSize = 12.sp,
                    )
                }
                if (files.isNotEmpty()) {
                    TextButton(onClick = { inbox.shredAll(); onChanged() }) {
                        Text("Wipe all", color = DangerSoft, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (files.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("◌", color = TextDim.copy(alpha = 0.5f), fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Nothing received yet — files you accept land here.",
                        color = TextDim, fontSize = 13.sp,
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = DeepSpaceRaised,
                        border = BorderStroke(1.dp, GlassEdge),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
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
                            TextButton(onClick = { inbox.shred(item.file); onChanged() }) {
                                Text("✕", color = DangerSoft, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
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
