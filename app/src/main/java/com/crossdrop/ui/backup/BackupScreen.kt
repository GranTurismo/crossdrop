package com.crossdrop.ui.backup

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.backup.BackupManager
import com.crossdrop.core.Peer
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.media.MediaLibrary
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.ConfirmDialog
import com.crossdrop.ui.Dot
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.IconTile
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.ToggleRow
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/**
 * "Back up to a computer": pair once with a computer on the radar, then photos and videos copy to it
 * whenever both are on the same Wi-Fi, and the phone can free up space once the computer re-checked them.
 */
@Composable
fun BackupScreen(manager: BackupManager, peers: List<Peer>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    var permTick by remember { mutableIntStateOf(0) }
    val canRead = remember(permTick) { MediaLibrary.canReadLibrary(context) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        manager.refreshCounts()
    }
    LaunchedEffect(state.device?.id) { if (state.device != null) manager.refreshCounts() }

    Box(modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.18f, centerX = 0.5f, centerY = 220.dp, radius = 260.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackHeader(tr("Back up to a computer"), onClose)
                val device = state.device
                if (device == null) {
                    NotPaired(state, peers.filter { it.vault == null && it.endpoint != null }, manager)
                } else {
                    Paired(state, device.name, canRead, onAllow = { permissions.launch(MediaLibrary.readPermissions()) }, manager)
                }
            }
        }
    }
}

@Composable
private fun NotPaired(state: BackupManager.State, devices: List<Peer>, manager: BackupManager) {
    Text(
        tr("Your photos, on your own computer"),
        style = displayStyle(24).copy(lineHeight = 29.sp), color = TextPrimary,
    )
    Text(
        tr("Photos and videos copy to your computer whenever both are on the same Wi-Fi: full quality, with their dates and places. No cloud, no account."),
        color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
    )
    val pairing = state.pairing
    if (pairing != null) {
        PairingCard(pairing, onCancel = manager::cancelPairing)
        return
    }
    SectionLabel(tr("Pick your computer"))
    if (devices.isEmpty()) {
        GroupCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                tr("Open CrossDrop on your computer, on this Wi-Fi. It shows up here."),
                color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    } else {
        GroupCard {
            devices.forEachIndexed { i, p ->
                if (i > 0) RowSeparator()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .then(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CdIcon(deviceIconFor(p.name), TextPrimary, size = 22.dp)
                    Text(p.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ChipButton(tr("Pair"), { manager.pairWith(p) }, height = 34.dp, fontSize = 13)
                }
            }
        }
    }
    FootNote(
        tr("CrossDrop for Mac, Windows and Linux: github.com/GranTurismo/crossdrop/releases"),
        icon = CdIcons.Laptop,
    )
}

@Composable
private fun PairingCard(p: BackupManager.Pairing, onCancel: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        radius = 26.dp, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (p.phase) {
            BackupManager.PairPhase.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(tr("Reaching {0}…", p.peerName), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            BackupManager.PairPhase.WAITING -> {
                Text(tr("Check the code on {0}", p.peerName), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    p.code.orEmpty().chunked(3).joinToString(" "),
                    style = monoStyle(34, Accent), modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    tr("If it shows the same code, click Pair there. Only pair with your own computer."),
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            BackupManager.PairPhase.DECLINED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(Amber)
                    Text(tr("{0} didn't pair", p.peerName), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    tr("Someone said no there, or nobody answered in time. Only computers keep backups."),
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            BackupManager.PairPhase.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(Amber)
                    Text(tr("Couldn't reach {0}", p.peerName), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(tr("Is CrossDrop still open there, on the same Wi-Fi?"), color = TextSecondary, fontSize = 13.sp)
            }
        }
        val done = p.phase == BackupManager.PairPhase.DECLINED || p.phase == BackupManager.PairPhase.FAILED
        PillButton(if (done) tr("Back") else tr("Cancel"), onCancel, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    }
}

@Composable
private fun Paired(state: BackupManager.State, name: String, canRead: Boolean, onAllow: () -> Unit, manager: BackupManager) {
    val context = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }

    // ---- the computer
    GlassCard(radius = 28.dp, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(deviceIconFor(name), Accent, size = 48.dp, radius = 16.dp, iconSize = 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = displayStyle(19), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(if (state.computer != null) Accent else com.crossdrop.ui.theme.TextFaint, glow = state.computer != null)
                    Text(
                        if (state.computer != null) tr("On this Wi-Fi") else tr("Not on this Wi-Fi right now"),
                        color = TextDim, fontSize = 12.sp,
                    )
                }
            }
        }
        val run = state.run
        if (run != null) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (run.itemsTotal == 0) tr("Looking for new photos and videos…")
                    else tr("Backing up {0} of {1}", run.itemsDone, run.itemsTotal),
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { if (run.bytesTotal <= 0) 0f else (run.bytesDone.toFloat() / run.bytesTotal).coerceIn(0f, 1f) },
                    color = Accent, trackColor = GlassLow, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                if (run.bytesTotal > 0) Text(
                    tr("{0} of {1}", formatBytes(run.bytesDone), formatBytes(run.bytesTotal)),
                    style = monoStyle(12, TextDim),
                )
                ChipButton(tr("Stop"), manager::stop)
            }
        } else {
            state.lastError?.let { Text(tr("Last backup stopped: {0}", it), color = Amber, fontSize = 13.sp, lineHeight = 18.sp) }
            if (state.lastRunAt > 0 && state.lastError == null) Text(
                tr("Last backup: {0}", android.text.format.DateUtils.getRelativeTimeSpanString(state.lastRunAt).toString()),
                color = TextDim, fontSize = 13.sp,
            )
            PillButton(
                tr("Back up now"), manager::backupNow, Modifier.fillMaxWidth(),
                enabled = state.computer != null && canRead, glow = state.computer != null && canRead,
            )
        }
    }

    // ---- what is safe
    if (!canRead) {
        GroupCard(contentPadding = PaddingValues(16.dp)) {
            Text(tr("CrossDrop needs access to your photos and videos to back them up."), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Box(Modifier.padding(top = 10.dp)) { ChipButton(tr("Allow access"), onAllow) }
        }
    }
    state.counts?.let { c ->
        GroupCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                tr("{0} of {1} photos and videos are on {2}", c.safe, c.total, name),
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (c.pending == 0) tr("Everything is backed up.")
                else trPlural(c.pending, "1 new item ({0}) to back up", "{n} new items ({0}) to back up", formatBytes(c.pendingBytes)),
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    // ---- free up space
    if (manager.canFreeUp) FreeUpCard(state, name, manager, context)

    GroupCard {
        ToggleRow(
            tr("Back up automatically"),
            tr("Whenever CrossDrop is open and {0} is on the same Wi-Fi", name),
            state.auto, manager::setAuto, Modifier.padding(horizontal = 12.dp),
        )
    }
    FootNote(tr("On the computer, backups go to Pictures/CrossDrop Backup, one folder per month. Nothing goes over the internet."), icon = CdIcons.Shield)
    PillButton(tr("Unpair {0}", name), { confirmUnpair = true }, Modifier.fillMaxWidth(), style = PillStyle.Glass)
    if (confirmUnpair) ConfirmDialog(
        title = tr("Unpair {0}?", name),
        text = tr("Backups stop. The copies already on {0} stay there.", name),
        confirmLabel = tr("Unpair"),
        onConfirm = { confirmUnpair = false; manager.unpair() },
        onDismiss = { confirmUnpair = false },
    )
}

@Composable
private fun FreeUpCard(state: BackupManager.State, name: String, manager: BackupManager, context: android.content.Context) {
    val c = state.counts ?: return
    val freeUp = state.freeUp
    var pending by remember { mutableStateOf<BackupManager.FreeUp.Ready?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ready = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK && ready != null) manager.freed(ready) else manager.dismissFreeUp()
    }
    // The computer confirmed: Android asks the person, in its own dialog, before anything is deleted.
    LaunchedEffect(freeUp) {
        val ready = freeUp as? BackupManager.FreeUp.Ready ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT < 30) { manager.dismissFreeUp(); return@LaunchedEffect }
        val batch = ready.copy(uris = ready.uris.take(MAX_DELETE), keys = ready.keys.take(MAX_DELETE))
        pending = batch
        runCatching {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, batch.uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { pending = null; manager.dismissFreeUp() }
    }
    if (c.freeable == 0 && freeUp !is BackupManager.FreeUp.Freed) return
    GlassCard(radius = 26.dp, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(CdIcons.Trash, Accent, size = 40.dp, radius = 14.dp, iconSize = 20.dp)
            Text(tr("Free up space"), style = displayStyle(17), modifier = Modifier.weight(1f))
        }
        when (freeUp) {
            is BackupManager.FreeUp.Freed -> {
                Text(
                    tr("Freed {0}. The photos and videos are still on {1}.", formatBytes(freeUp.bytes), name),
                    color = Accent, fontSize = 14.sp, lineHeight = 20.sp,
                )
                ChipButton(tr("OK"), manager::dismissFreeUp)
            }
            is BackupManager.FreeUp.Problem -> {
                Text(freeUp.message, color = Amber, fontSize = 13.sp, lineHeight = 18.sp)
                ChipButton(tr("OK"), manager::dismissFreeUp)
            }
            BackupManager.FreeUp.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(tr("{0} is checking every file…", name), color = TextSecondary, fontSize = 13.sp)
            }
            else -> {
                Text(
                    trPlural(c.freeable, "1 photo or video ({0}) older than 30 days is safely on {1}.",
                        "{n} photos and videos ({0}) older than 30 days are safely on {1}.", formatBytes(c.freeableBytes), name),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Text(
                    tr("Before deleting anything, {0} re-reads each file. Android then asks you to confirm.", name),
                    color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
                )
                PillButton(
                    tr("Free up {0}", formatBytes(c.freeableBytes)), manager::prepareFreeUp, Modifier.fillMaxWidth(),
                    enabled = state.computer != null && state.run == null, style = PillStyle.Accent,
                )
                if (state.computer == null) Text(tr("Available when {0} is on the same Wi-Fi.", name), color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

/** Android's delete dialog takes the whole list in one go; keep it to a size it handles well. */
private const val MAX_DELETE = 500
