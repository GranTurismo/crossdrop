package com.crossdrop.ui.vault

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.vault.VaultDirEntry
import com.crossdrop.net.VaultExplorerManager
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.FileKind
import com.crossdrop.ui.FileTypeTile
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.IconTile
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextFaint
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.Violet
import com.crossdrop.ui.theme.VioletText
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native explorer for a mounted LAN drive: breadcrumb chips, folders as
 * tiles, files in one grouped list with per-file download, batch select,
 * uploads into editable drives, and stream-in-place playback of media
 * through the loopback bridge. Back walks up a folder, then closes.
 */
@Composable
fun VaultExplorerScreen(
    state: VaultExplorerManager.Ui,
    onBrowse: (String) -> Unit,
    onUp: () -> Unit,
    onToggleSelect: (VaultDirEntry) -> Unit,
    onDownload: (List<String>) -> Unit,
    onUpload: (List<android.net.Uri>) -> Unit,
    onStream: (String) -> String?,
    isMedia: (VaultDirEntry) -> Boolean,
    onClose: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    LaunchedEffect(state.selected.isEmpty()) { if (state.selected.isNotEmpty()) selecting = true }

    BackHandler {
        when {
            selecting -> { state.selected.forEach { key -> state.entries.firstOrNull { joinKey(state.path, it.name) == key }?.let(onToggleSelect) }; selecting = false }
            state.path.isNotEmpty() && !state.pinRequired -> onUp()
            else -> onClose()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) onUpload(uris) }

    fun open(e: VaultDirEntry) {
        val key = joinKey(state.path, e.name)
        when {
            e.isDir -> onBrowse(key)
            selecting -> onToggleSelect(e)
            isMedia(e) -> onStream(key)?.let { url ->
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(url.toUri(), e.mime ?: "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(i) }
            }
            else -> onDownload(listOf(key))
        }
    }

    val folders = state.entries.filter { it.isDir }
    val files = state.entries.filter { !it.isDir }

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(color = Amber, alpha = 0.14f, centerX = 0.9f, centerY = 70.dp, radius = 210.dp)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                BackHeader(
                    state.peerName, onClose, backLabel = tr("Close drive"), titleSize = 20,
                    subtitle = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            CdIcon(CdIcons.Lock, Accent, size = 12.dp)
                            Text(
                                when {
                                    state.pinRequired -> tr("Encrypted · PIN needed")
                                    state.writable -> tr("Encrypted · you can add & edit")
                                    else -> tr("Encrypted · view & download")
                                },
                                color = Accent, fontSize = 12.sp,
                            )
                        }
                    },
                )

                if (state.pinRequired) {
                    PinGate(state.pinFailed, pin, { pin = it }) { onUnlock(pin) }
                    return@Column
                }

                Spacer(Modifier.height(14.dp))
                Crumbs(state.peerName, state.path, onBrowse)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.writable) {
                        PillButton(tr("Upload"), { picker.launch(arrayOf("*/*")) }, height = 44.dp, icon = CdIcons.ArrowUp, fontSize = 14)
                    }
                    PillButton(
                        if (selecting) tr("Done") else tr("Select"),
                        {
                            if (selecting) state.selected.forEach { key ->
                                state.entries.firstOrNull { joinKey(state.path, it.name) == key }?.let(onToggleSelect)
                            }
                            selecting = !selecting
                        },
                        height = 44.dp, style = PillStyle.Outline, fontSize = 14,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(tr("Reading the drive…"), color = TextDim, fontSize = 13.sp)
                        }
                        state.error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            PillButton(tr("Try again"), { onBrowse(state.path) }, height = 44.dp, style = PillStyle.Glass)
                        }
                        state.entries.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            IconTile(CdIcons.Folder, Amber, size = 56.dp, radius = 28.dp, iconSize = 26.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(tr("This folder is empty"), style = displayStyle(16))
                            if (state.writable) Text(tr("Upload something to fill it."), color = TextDim, fontSize = 13.sp)
                        }
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 140.dp),
                        ) {
                            folders.chunked(2).forEach { pair ->
                                item(key = "d:" + pair.first().name) {
                                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        pair.forEach { d -> FolderTile(d, Modifier.weight(1f)) { open(d) } }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            itemsIndexed(files, key = { _, e -> "f:" + e.name }) { i, e ->
                                val last = i == files.lastIndex
                                val shape = RoundedCornerShape(
                                    topStart = if (i == 0) 26.dp else 0.dp, topEnd = if (i == 0) 26.dp else 0.dp,
                                    bottomStart = if (last) 26.dp else 0.dp, bottomEnd = if (last) 26.dp else 0.dp,
                                )
                                Column(
                                    Modifier.fillMaxWidth().clip(shape).background(GlassLow)
                                        .padding(start = 6.dp, end = 6.dp, top = if (i == 0) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp),
                                ) {
                                    if (i > 0) RowSeparator()
                                    FileRow(
                                        e = e,
                                        media = isMedia(e),
                                        selecting = selecting,
                                        selected = joinKey(state.path, e.name) in state.selected,
                                        onOpen = { open(e) },
                                        onDownload = { onDownload(listOf(joinKey(state.path, e.name))) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- bottom panel: batch download, then the transfer queue
        val jobs = state.jobs.filter { !it.done || it.failed != null }
        if (state.selected.isNotEmpty() || jobs.isNotEmpty()) {
            GlassCard(
                Modifier.align(Alignment.BottomCenter).systemBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 16.dp).widthIn(max = 640.dp).fillMaxWidth(),
                radius = 30.dp, fill = Color(0xE6181D24), border = Color.White.copy(alpha = 0.1f),
                glow = Color.Black.copy(alpha = 0.5f), glowRadius = 50.dp, glowOffsetY = (-10).dp,
                contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                jobs.take(3).forEach { j -> JobRow(j) }
                if (state.selected.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("{0} selected", state.selected.size), color = TextSecondary, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        PillButton(
                            tr("Download"), { onDownload(state.selected.toList()); selecting = false },
                            height = 48.dp, icon = CdIcons.Download, glow = true,
                        )
                    }
                }
            }
        }
    }
}

private fun joinKey(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

@Composable
private fun Crumbs(root: String, path: String, onBrowse: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Crumb(root, current = parts.isEmpty()) { onBrowse("") }
        var acc = ""
        parts.forEachIndexed { i, seg ->
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val target = acc
            CdIcon(CdIcons.ChevronRight, TextFaint, size = 14.dp)
            Crumb(seg, current = i == parts.lastIndex) { onBrowse(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (current) TextPrimary else GlassMid)
            .clickable(enabled = !current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (current) Ink else TextSecondary, fontSize = 13.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}

@Composable
private fun FolderTile(e: VaultDirEntry, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(
        modifier, radius = 22.dp, fill = Glass, contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), onClick = onClick, onClickLabel = tr("Open folder"),
    ) {
        CdIcon(CdIcons.Folder, Amber, size = 26.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("Folder"), color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FileRow(
    e: VaultDirEntry,
    media: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val kind = FileKind.of(e.name)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = if (selecting) tr("Select") else if (media) tr("Stream") else tr("Download"), onClick = onOpen)
            .padding(start = 10.dp, end = 0.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (media && kind == FileKind.VIDEO) {
            IconTile(CdIcons.Play, VioletText, bg = Violet.copy(alpha = 0.16f), size = 44.dp)
        } else {
            FileTypeTile(e.name, size = 44.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(e.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatBytes(e.size) + " · " + if (media) tr("tap to stream")
                else SimpleDateFormat("MMM d, HH:mm", com.crossdrop.core.i18n.I18n.locale()).format(Date(e.mtime * 1000)),
                color = TextDim, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (selecting) {
            Box(
                Modifier.size(44.dp).semantics { contentDescription = if (selected) tr("Selected") else tr("Not selected") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (selected) Accent else Color.Transparent)
                        .border(1.5.dp, if (selected) Accent else HairlineStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) CdIcon(CdIcons.Check, OnAccent, size = 14.dp) }
            }
        } else {
            CircleButton(CdIcons.Download, tr("Download {0}", e.name), onDownload, fill = Color.Transparent, tint = TextSecondary)
        }
    }
}

@Composable
private fun JobRow(j: VaultExplorerManager.Job) {
    val frac = if (j.total > 0) (j.received.toFloat() / j.total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1F2A2F)).border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { CdIcon(if (j.uploading) CdIcons.ArrowUp else CdIcons.ArrowDown, if (j.failed != null) DangerText else Accent, size = 18.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(j.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    j.failed ?: (if (j.uploading) tr("Uploading") else tr("Saving to inbox")),
                    color = if (j.failed != null) DangerText else TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (j.failed != null) "" else "${formatBytes(j.received)} / ${formatBytes(j.total)}",
                style = monoStyle(11, TextDim),
            )
        }
        if (j.failed == null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
            }
        }
    }
}

@Composable
private fun PinGate(failed: Boolean, pin: String, onPin: (String) -> Unit, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).dropShadow(CircleShape, Shadow(40.dp, Amber.copy(alpha = 0.18f)))
                .clip(CircleShape).background(Amber.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { CdIcon(CdIcons.Lock, Amber, size = 30.dp) }
        Spacer(Modifier.height(18.dp))
        Text(tr("This drive needs a passcode"), style = displayStyle(22), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(tr("It's the 4 digits shown on the device sharing it."), color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(tr("That passcode didn't work — try again"), color = DangerText, fontSize = 13.sp)
        }
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = pin, onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) onPin(v) },
            singleLine = true,
            textStyle = monoStyle(26).copy(textAlign = TextAlign.Center, letterSpacing = 0.4.em),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = HairlineStrong, cursorColor = Accent,
                focusedContainerColor = GlassLow, unfocusedContainerColor = GlassLow,
            ),
            modifier = Modifier.width(200.dp).semantics { contentDescription = tr("Passcode") },
        )
        Spacer(Modifier.height(18.dp))
        PillButton(tr("Unlock"), onUnlock, Modifier.width(200.dp), enabled = pin.length == 4, glow = true)
    }
}
