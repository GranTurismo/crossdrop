package com.crossdrop.ui.inbox

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.crossdrop.core.i18n.I18n
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.storage.InboxStore
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.ConfirmDialog
import com.crossdrop.ui.FileKind
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.FilterPill
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.LocalNavInset
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.ScreenTitle
import com.crossdrop.ui.theme.Danger
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.Violet
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.transfer.formatBytes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class InboxFilter(val kinds: Set<FileKind>?) {
    ALL(null),
    PHOTOS(setOf(FileKind.PHOTO)),
    VIDEOS(setOf(FileKind.VIDEO)),
    DOCS(setOf(FileKind.DOC)),
    OTHER(setOf(FileKind.AUDIO, FileKind.ARCHIVE, FileKind.APP, FileKind.OTHER));

    val label: String
        get() = when (this) {
            ALL -> tr("All")
            PHOTOS -> tr("Photos")
            VIDEOS -> tr("Videos")
            DOCS -> tr("Docs")
            OTHER -> tr("Other")
        }
}

/**
 * Received files in `Download/CrossDrop`: a storage breakdown, type filters
 * and the list. Open with the system app, or shred (zero-fill + delete).
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
    var filter by remember { mutableStateOf(InboxFilter.ALL) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<InboxStore.ReceivedFile?>(null) }
    val kinds = remember(files) { files.associateWith { FileKind.of(it.name) } }
    val shown = files.filter { f -> filter.kinds?.contains(kinds[f]) ?: true }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(color = Violet, alpha = 0.18f, centerX = 0.15f, centerY = 100.dp, radius = 220.dp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 28.dp, bottom = LocalNavInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ScreenTitle(tr("Inbox"), Modifier.weight(1f))
                    if (files.isNotEmpty()) {
                        ChipButton(
                            tr("Wipe all"), { confirmWipe = true }, icon = CdIcons.Trash, height = 40.dp,
                            fg = DangerText, bg = Danger.copy(alpha = 0.1f), border = Danger.copy(alpha = 0.3f),
                        )
                    }
                }
            }

            if (files.isEmpty()) {
                item { EmptyInbox(Modifier.widthIn(max = 640.dp)) }
            } else {
                item { StorageCard(files, kinds, Modifier.widthIn(max = 640.dp)) }
                item {
                    Row(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InboxFilter.entries.forEach { f ->
                            val n = if (f.kinds == null) files.size else files.count { f.kinds.contains(kinds[it]) }
                            if (f == InboxFilter.ALL || n > 0) {
                                FilterPill(f.label, filter == f, { filter = f })
                            }
                        }
                    }
                }
                item {
                    if (shown.isEmpty()) {
                        Text(
                            tr("Nothing here in {0}.", filter.label.lowercase(Locale.forLanguageTag(I18n.language()))), color = TextDim, fontSize = 13.sp,
                            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(8.dp),
                        )
                    } else {
                        GroupCard(Modifier.widthIn(max = 640.dp)) {
                            shown.forEachIndexed { i, item ->
                                if (i > 0) RowSeparator()
                                FileRow(
                                    item,
                                    onOpen = { openFile(context, item) },
                                    onDelete = { confirmDelete = item },
                                )
                            }
                        }
                    }
                }
            }
            item {
                FootNote(
                    tr("Saved in the CrossDrop folder inside Downloads. Wiping overwrites each file with zeros first."),
                    Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    icon = CdIcons.Lock,
                )
            }
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = tr("Wipe the inbox?"),
            text = tr("All {0} received files are overwritten with zeros and deleted. This can't be undone.", files.size),
            confirmLabel = tr("Wipe all"),
            onConfirm = {
                confirmWipe = false
                inbox.shredAll(); tick++; onChanged()
            },
            onDismiss = { confirmWipe = false },
        )
    }
    confirmDelete?.let { item ->
        ConfirmDialog(
            title = tr("Delete {0}?", item.name),
            text = tr("The file is overwritten with zeros and removed from this device."),
            confirmLabel = tr("Delete"),
            onConfirm = {
                confirmDelete = null
                inbox.shred(item); tick++; onChanged()
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun StorageCard(
    files: List<InboxStore.ReceivedFile>,
    kinds: Map<InboxStore.ReceivedFile, FileKind>,
    modifier: Modifier = Modifier,
) {
    val sizes = remember(files) { files.associateWith { it.size } }
    val total = sizes.values.sum()
    // Category totals, largest first; small categories fold into "Other".
    val byKind = remember(files) {
        files.groupBy { kinds[it]!!.let { k -> if (k in MAIN_KINDS) k else FileKind.OTHER } }
            .mapValues { (_, v) -> v.sumOf { sizes[it] ?: 0L } }
            .filterValues { it > 0 }
            .entries.sortedByDescending { it.value }
    }
    GlassCard(modifier.fillMaxWidth(), radius = 26.dp, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBytes(total), style = displayStyle(26), modifier = Modifier.weight(1f))
            val fileCount = files.size
            Text(trPlural(fileCount, "1 file", "{n} files"), color = TextDim, fontSize = 13.sp)
        }
        Row(
            Modifier.fillMaxWidth().height(10.dp).semantics {
                contentDescription = byKind.joinToString(", ") { "${it.key.label} ${formatBytes(it.value)}" }
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            byKind.forEach { (k, bytes) ->
                val w = (bytes.toFloat() / total.coerceAtLeast(1)).coerceAtLeast(0.02f)
                Box(Modifier.weight(w).height(10.dp).clip(RoundedCornerShape(5.dp)).background(k.swatch))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            byKind.forEach { (k, _) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(k.swatch))
                    Text(k.label, color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

private val MAIN_KINDS = setOf(FileKind.VIDEO, FileKind.PHOTO, FileKind.DOC)

@Composable
private fun FileRow(item: InboxStore.ReceivedFile, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = tr("Open"), onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileTypeTile(item.name, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${formatBytes(item.size)} · ${whenLabel(item.modifiedMs)}", color = TextDim, fontSize = 12.sp)
        }
        ChipButton(tr("Open"), onOpen)
        CircleButton(CdIcons.Trash, tr("Delete {0}", item.name), onDelete, size = 40.dp, iconSize = 17.dp, fill = androidx.compose.ui.graphics.Color.Transparent, tint = TextDim)
    }
}

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 36.dp, horizontal = 20.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(GlassHigh), contentAlignment = Alignment.Center) {
                CdIcon(CdIcons.Inbox, TextSecondary, size = 26.dp)
            }
            Spacer(Modifier.height(14.dp))
            Text(tr("Nothing received yet"), style = displayStyle(17))
            Spacer(Modifier.height(4.dp))
            Text(tr("Files you accept land here."), color = TextDim, fontSize = 13.sp)
        }
    }
}

private fun whenLabel(ts: Long): String {
    fun dayStart(back: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -back)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val pattern = when {
        ts >= dayStart(0) -> "HH:mm"
        ts >= dayStart(1) -> return tr("Yesterday")
        ts >= dayStart(6) -> "EEE"
        else -> "MMM d"
    }
    return SimpleDateFormat(pattern, com.crossdrop.core.i18n.I18n.locale()).format(Date(ts))
}

private fun openFile(context: android.content.Context, item: InboxStore.ReceivedFile) {
    item.file?.let { return openFile(context, it) }
    val uri = item.uri ?: return
    runCatching {
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

/** Hand a file from the app's own storage to whatever app views its type. */
fun openFile(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
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
