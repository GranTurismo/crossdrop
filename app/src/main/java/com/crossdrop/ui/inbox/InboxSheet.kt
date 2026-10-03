package com.crossdrop.ui.inbox

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.crossdrop.storage.InboxStore
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.DeepSpaceSoft
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpaceSoft) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Received files", color = TextPrimary, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { inbox.shredAll(); onChanged() }) {
                    Text("Wipe all", color = DangerSoft, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val files = inbox.list()
            if (files.isEmpty()) {
                Text("Nothing received yet.", color = TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn {
                items(files) { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = TextPrimary, fontSize = 14.sp, maxLines = 1)
                            Text("${formatBytes(item.size)} · ${item.receivedAt}",
                                color = TextDim, fontSize = 12.sp)
                        }
                        TextButton(onClick = { openFile(context, inbox, item) }) {
                            Text("Open", color = RippleTeal)
                        }
                        TextButton(onClick = { inbox.shred(item.file); onChanged() }) {
                            Text("Shred", color = DangerSoft)
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
