package com.crossdrop.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.CrossDropApp
import com.crossdrop.MainActivity
import com.crossdrop.core.Peer
import com.crossdrop.di.AppContainer
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferGroup
import com.crossdrop.net.outgoingFileFor
import com.crossdrop.service.TransferService
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.isLinkText
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.CrossDropTheme
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import kotlinx.coroutines.delay

/**
 * "Share → CrossDrop" from any app. A sheet slides up over that app, already scanning: tap a
 * device and the send starts (progress also shows in the notification); tick several to send to
 * all of them. Picking a Direct Share target (a device used before) sends to it as soon as it shows
 * up on the network. Text and links go as text, so the receiver can copy or open them.
 */
class ShareActivity : ComponentActivity() {

    private var payload by mutableStateOf<Payload?>(null)
    private var preferred by mutableStateOf<String?>(null)

    sealed interface Payload {
        data class Items(val uris: List<Uri>, val label: String) : Payload
        data class Text(val text: String) : Payload
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        read(intent)
        val container = (application as CrossDropApp).container
        setContent {
            CrossDropTheme {
                payload?.let { p -> ShareSheet(container, p, preferred, ::send, ::sendToAnyone, ::finish) }
            }
        }
        if (payload == null) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        intent ?: return
        preferred = DirectShare.deviceNameFrom(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        val uris = streams(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        payload = when {
            uris.isNotEmpty() -> Payload.Items(uris, label(uris))
            !text.isNullOrEmpty() -> Payload.Text(text)
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val list = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            else -> null
        }.orEmpty()
        val clip = intent.clipData
        val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return (list + fromClip).distinct().filter(::isForeignContent)
    }

    /**
     * Any app can send us a share intent, so it must not be able to make us read our own private
     * files (a `content://` URI on our FileProvider, or a `file://` path into our sandbox) and send
     * them off: only content owned by some other app is accepted.
     */
    private fun isForeignContent(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        if (authority.startsWith(packageName)) return false
        val owner = runCatching { packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner != packageName
    }

    private fun label(uris: List<Uri>): String {
        val first = runCatching { outgoingFileFor(uris.first())?.meta?.name }.getOrNull()
        return when {
            uris.size == 1 -> first ?: "1 item"
            else -> "${uris.size} items"
        }
    }

    /**
     * Start the send. The read grants on the shared items belong to this activity, so they're
     * handed to the transfer service (which outlives it) before anything else.
     */
    private fun send(targets: List<Peer>): String? {
        val container = (application as CrossDropApp).container
        val p = payload ?: return null
        startTransferService(p)
        return when (p) {
            is Payload.Items -> container.coordinator.sendTo(targets, p.uris)
            is Payload.Text -> container.coordinator.sendText(targets, p.text)
        }
    }

    private fun startTransferService(p: Payload) {
        val i = Intent(this, TransferService::class.java)
        if (p is Payload.Items) {
            val clip = ClipData.newRawUri("shared", p.uris.first())
            p.uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startForegroundService(i) }
    }

    /** No app on the other side: open CrossDrop's QR share with these items. */
    private fun sendToAnyone() {
        val p = payload as? Payload.Items ?: return
        val container = (application as CrossDropApp).container
        startTransferService(p)
        container.qrShare.start(p.uris)
        val clip = ClipData.newRawUri("shared", p.uris.first())
        p.uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .also { it.clipData = clip },
        )
        finish()
    }
}

@Composable
private fun ShareSheet(
    container: AppContainer,
    payload: ShareActivity.Payload,
    preferred: String?,
    onSend: (List<Peer>) -> String?,
    onAnyone: () -> Unit,
    onClose: () -> Unit,
) {
    val peers by container.peers.collectAsState()
    val groups by container.coordinator.groups.collectAsState()
    val devices = peers.values.filter { it.vault == null && it.endpoint != null }.sortedBy { it.name.lowercase() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var autoSent by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.id == groupId }

    fun go(targets: List<Peer>) { if (targets.isNotEmpty()) groupId = onSend(targets) }

    // Direct Share: the device picked in the system menu is sent to the moment it's seen.
    LaunchedEffect(preferred, devices.map { it.name }) {
        if (preferred != null && !autoSent && groupId == null) {
            devices.firstOrNull { it.name == preferred }?.let { autoSent = true; go(listOf(it)) }
        }
    }
    // Done → close by itself.
    LaunchedEffect(group?.isTerminal, group?.problems) {
        if (group != null && group.isTerminal && group.problems == 0) { delay(900); onClose() }
    }
    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize().background(Color(0x9E040609))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(true, enter = slideInVertically { it / 2 } + fadeIn()) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                    .clip(RoundedCornerShape(36.dp)).background(InkSheet)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)))
                Header(payload)
                if (group != null) {
                    Progress(group)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (group.isTerminal && group.problems > 0) {
                            PillButton("Close", onClose, Modifier.weight(1f), style = PillStyle.Glass)
                            PillButton("Retry", { container.coordinator.retryFailed(group.id) }, Modifier.weight(1f))
                        } else {
                            PillButton(if (group.isTerminal) "Done" else "Hide", onClose, Modifier.weight(1f), style = PillStyle.Glass)
                        }
                    }
                } else {
                    if (preferred != null && !autoSent) {
                        Text("Looking for $preferred…", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(
                        Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (devices.isEmpty()) Scanning()
                        devices.forEach { d ->
                            DeviceRow(
                                d, d.instanceId in selected,
                                onTap = {
                                    if (selected.isEmpty()) go(listOf(d))
                                    else selected = if (d.instanceId in selected) selected - d.instanceId else selected + d.instanceId
                                },
                                onToggle = { selected = if (d.instanceId in selected) selected - d.instanceId else selected + d.instanceId },
                            )
                        }
                    }
                    if (selected.isNotEmpty()) {
                        PillButton(
                            "Send to ${selected.size} device${if (selected.size == 1) "" else "s"}",
                            { go(devices.filter { it.instanceId in selected }) },
                            Modifier.fillMaxWidth(), icon = CdIcons.ArrowUp, glow = true,
                        )
                    } else if (payload is ShareActivity.Payload.Items) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GlassMid)
                                .clickable(role = Role.Button, onClick = onAnyone).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CdIcon(CdIcons.Qr, Accent, size = 22.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Send to anyone", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("No app needed — they scan a QR code", color = TextDim, fontSize = 12.sp)
                            }
                            CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(payload: ShareActivity.Payload) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            CdIcon(
                when (payload) {
                    is ShareActivity.Payload.Text -> if (isLinkText(payload.text)) CdIcons.Globe else CdIcons.Note
                    is ShareActivity.Payload.Items -> CdIcons.ArrowUp
                },
                Accent, size = 22.dp,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("Send with CrossDrop", style = displayStyle(18))
            Text(
                when (payload) {
                    is ShareActivity.Payload.Text -> payload.text.replace('\n', ' ')
                    is ShareActivity.Payload.Items -> payload.label
                },
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceRow(peer: Peer, selected: Boolean, onTap: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (selected) Accent.copy(alpha = 0.14f) else GlassMid)
            .border(1.dp, if (selected) Accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = "Send", onClick = onTap)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(GlassHigh), contentAlignment = Alignment.Center) {
            CdIcon(deviceIconFor(peer.name), TextPrimary, size = 20.dp)
        }
        Text(peer.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Checkbox, onClickLabel = "Select", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (selected) Accent else Color.Transparent)
                    .border(1.5.dp, if (selected) Accent else TextDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) CdIcon(CdIcons.Check, OnAccent, size = 14.dp) }
        }
    }
}

@Composable
private fun Scanning() {
    val t by rememberInfiniteTransition(label = "scan").animateFloat(0.6f, 1.1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "s")
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp).scale(t).border(1.5.dp, Accent.copy(alpha = (1.1f - t) * 1.6f), CircleShape))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
        }
        Column {
            Text("Looking for devices…", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Open CrossDrop on the other device, on this Wi-Fi", color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Progress(g: TransferGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        g.sessions.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CdIcon(if (s.status == SessionStatus.COMPLETED) CdIcons.Check else deviceIconFor(s.peerName),
                    if (s.status == SessionStatus.COMPLETED) Accent else TextPrimary, size = 18.dp)
                Text(s.peerName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (s.status) {
                        SessionStatus.CONNECTING -> "Connecting…"
                        SessionStatus.AWAITING_ACCEPT -> "Waiting to accept"
                        SessionStatus.TRANSFERRING -> "${(s.fraction * 100).toInt()}%"
                        SessionStatus.COMPLETED -> "Sent"
                        SessionStatus.DECLINED -> "Declined"
                        SessionStatus.CANCELLED -> "Cancelled"
                        SessionStatus.FAILED -> "Failed"
                    },
                    color = when (s.status) {
                        SessionStatus.COMPLETED, SessionStatus.TRANSFERRING -> Accent
                        SessionStatus.FAILED, SessionStatus.DECLINED -> DangerText
                        else -> TextSecondary
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(GlassHigh)) {
                Box(Modifier.fillMaxWidth(s.fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
