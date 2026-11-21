package com.crossdrop.ui.pairing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.Peer
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.core.pairing.PairedDevice
import com.crossdrop.core.transfer.FailureAdvice
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.ConfirmDialog
import com.crossdrop.ui.GroupCard
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.ToggleRow
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How asking another device to pair ended. */
enum class AddDeviceResult { PAIRED, DECLINED, FAILED }

/**
 * "My devices": the devices this one is paired with, each with a switch for "accept files from it without
 * asking" and Unpair, plus "Add a device". Platform-free: shared with desktop. [subtitleFor] lets a platform
 * say more under a name (a phone's last backup); [extra] adds rows under the list (the backup folder).
 */
@Composable
fun MyDevicesSection(
    devices: List<PairedDevice>,
    peers: List<Peer>,
    onAccept: (id: String, on: Boolean) -> Unit,
    onUnpair: (id: String) -> Unit,
    onPair: suspend (Peer, (String) -> Unit) -> AddDeviceResult,
    modifier: Modifier = Modifier,
    subtitleFor: (PairedDevice) -> String? = { null },
    /** Pairing ids of the devices on the radar right now (see [com.crossdrop.net.PairedPresence]). */
    present: Set<String> = emptySet(),
    /** Pairing id → items waiting for that device ("send when it's back"). */
    queued: Map<String, Int> = emptyMap(),
    /** Pairing ids whose queue is being sent right now. */
    sending: Set<String> = emptySet(),
    /** Pick things to send to a device that is away; null hides the action. */
    onQueue: ((PairedDevice) -> Unit)? = null,
    onClearQueue: (id: String) -> Unit = {},
    /** A line about the last queued send ("Sent to Laptop"). */
    note: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf<PairedDevice?>(null) }
    SectionLabel(tr("My devices"), modifier.padding(top = 6.dp))
    note?.let { Text(it, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
    GroupCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        devices.forEach { d ->
            val waiting = queued[d.id] ?: 0
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CdIcon(deviceIconFor(d.name), TextPrimary, size = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Here / not here, what is waiting for it, and whatever the platform adds (a phone's last backup).
                    val line = listOfNotNull(
                        if (onQueue != null) (if (d.id in present) tr("Here now") else tr("Not here")) else null,
                        if (d.id in sending) tr("Sending…") else if (waiting > 0) trPlural(waiting, "1 item waiting", "{n} items waiting") else null,
                        subtitleFor(d),
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, color = if (d.id in sending) Accent else TextDim, fontSize = 12.sp)
                }
                ChipButton(tr("Unpair"), { unpairing = d }, height = 32.dp, fontSize = 12)
            }
            if (onQueue != null && d.id !in present) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(tr("Send when it's back"), { onQueue(d) }, icon = CdIcons.ArrowUp, height = 32.dp, fontSize = 12)
                    if (waiting > 0) ChipButton(tr("Clear"), { onClearQueue(d.id) }, height = 32.dp, fontSize = 12)
                }
            }
            ToggleRow(tr("Accept files without asking"), tr("Only from {0}", d.name), d.accept, { onAccept(d.id, it) })
            RowSeparator()
        }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { adding = true }.heightIn(min = 54.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CdIcon(CdIcons.Link, Accent, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Add a device"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (devices.isEmpty()) Text(tr("Your own phones and computers can then send each other files without asking."), color = TextDim, fontSize = 12.sp)
            }
            CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
        }
        extra()
    }
    if (adding) AddDeviceDialog(peers, onPair, onClose = { adding = false })
    unpairing?.let { d ->
        ConfirmDialog(
            title = tr("Unpair {0}?", d.name),
            text = tr("{0} will have to ask before sending files, and can no longer back up here.", d.name),
            confirmLabel = tr("Unpair"),
            onConfirm = { unpairing = null; onUnpair(d.id) },
            onDismiss = { unpairing = null },
        )
    }
}

private sealed interface Phase {
    data object Pick : Phase
    data class Waiting(val name: String, val code: String?) : Phase
    data class Done(val name: String, val result: AddDeviceResult) : Phase
}

/**
 * Pick a device on the radar, then pair with it: both screens show the same 6-digit code and the other person
 * agrees. [onPair] does the pairing (it suspends until they answer); leaving the dialog cancels it.
 */
@Composable
fun AddDeviceDialog(peers: List<Peer>, onPair: suspend (Peer, (String) -> Unit) -> AddDeviceResult, onClose: () -> Unit) {
    var phase by remember { mutableStateOf<Phase>(Phase.Pick) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val devices = peers.filter { it.endpoint != null && it.vault == null }

    fun start(peer: Peer) {
        phase = Phase.Waiting(peer.name, null)
        job = scope.launch {
            val result = try {
                onPair(peer) { code -> phase = Phase.Waiting(peer.name, code) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AddDeviceResult.FAILED
            }
            phase = Phase.Done(peer.name, result)
        }
    }
    val close = { job?.cancel(); onClose() }

    AlertDialog(
        onDismissRequest = close,
        containerColor = InkSheet,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        title = {
            Text(
                when (val p = phase) {
                    Phase.Pick -> tr("Add a device")
                    is Phase.Waiting -> tr("Pairing with {0}", p.name)
                    is Phase.Done -> when (p.result) {
                        AddDeviceResult.PAIRED -> tr("Paired with {0}", p.name)
                        AddDeviceResult.DECLINED -> tr("{0} didn't pair", p.name)
                        AddDeviceResult.FAILED -> tr("Couldn't reach {0}", p.name)
                    }
                },
                style = displayStyle(20),
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val p = phase) {
                    Phase.Pick -> {
                        Text(
                            if (devices.isEmpty()) tr("No devices found. Open CrossDrop on the other device, on this Wi-Fi.")
                            else tr("Pick a device that has CrossDrop open. Both screens show a code: check that they match."),
                            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                        devices.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { start(d) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CdIcon(deviceIconFor(d.name), TextPrimary, size = 22.dp)
                                Text(d.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
                            }
                        }
                    }
                    is Phase.Waiting -> {
                        Text(
                            p.code?.chunked(3)?.joinToString(" ") ?: tr("Connecting…"),
                            style = monoStyle(34, Accent), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        )
                        Text(
                            tr("Ask {0} to check the code and tap Pair.", p.name),
                            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Phase.Done -> Text(
                        when (p.result) {
                            AddDeviceResult.PAIRED -> tr("You can now send each other files without asking. Turn that off any time in Settings.")
                            AddDeviceResult.DECLINED -> FailureAdvice.declinedHint()
                            AddDeviceResult.FAILED -> tr("Open CrossDrop on that device and make sure you're both on the same Wi-Fi.")
                        },
                        color = if (p.result == AddDeviceResult.PAIRED) TextSecondary else DangerText,
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            }
        },
        confirmButton = {
            val p = phase
            if (p is Phase.Done && p.result != AddDeviceResult.PAIRED) {
                TextButton(onClick = { phase = Phase.Pick }) { Text(tr("Try again"), color = Accent, fontWeight = FontWeight.SemiBold) }
            } else if (p is Phase.Done) {
                TextButton(onClick = close) { Text(tr("Done"), color = Accent, fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = {
            if (phase !is Phase.Done || (phase as Phase.Done).result != AddDeviceResult.PAIRED)
                TextButton(onClick = close) { Text(tr("Cancel"), color = TextDim, fontWeight = FontWeight.SemiBold) }
        },
    )
}

/**
 * On the radar: the paired devices that are not here, each with a way to queue something for when it is.
 * Shows at most [max] so the radar stays about the devices that are around.
 */
@Composable
fun OfflineDevicesStrip(
    devices: List<PairedDevice>,
    queued: Map<String, Int>,
    sending: Set<String>,
    onSend: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 2,
) {
    val shown = devices.take(max)
    if (shown.isEmpty()) return
    GroupCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        shown.forEachIndexed { i, d ->
            if (i > 0) RowSeparator()
            val waiting = queued[d.id] ?: 0
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CdIcon(deviceIconFor(d.name), TextDim, size = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            d.id in sending -> tr("Sending…")
                            waiting > 0 -> trPlural(waiting, "1 item waiting", "{n} items waiting")
                            else -> tr("Not here")
                        },
                        color = if (d.id in sending) Accent else TextDim, fontSize = 12.sp,
                    )
                }
                ChipButton(tr("Send when it's back"), { onSend(d) }, icon = CdIcons.ArrowUp, height = 34.dp, fontSize = 12)
            }
        }
    }
}
