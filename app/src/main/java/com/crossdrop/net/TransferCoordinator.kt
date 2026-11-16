package com.crossdrop.net

import android.content.Context
import android.net.Uri
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android face of [TransferHub]: turns content URIs, text and camera shots into outgoing files,
 * points receives at the inbox and logs finished sessions to history. The UI observes the hub's
 * flows and calls these intent-style methods; it never touches sockets.
 */
class TransferCoordinator(
    scope: CoroutineScope,
    private val context: Context,
    engine: TransferEngine,
    private val server: TransferServer,
    private val inbox: InboxStore,
    private val history: HistoryStore,
) {
    val hub = TransferHub(
        scope, engine,
        port = object : ReceivePort {
            override fun newInboxFile(meta: FileMeta): File = inbox.newFileFor(meta)
            override val inboxDelta: DeltaReceiver get() = inbox.delta
        },
        onEnded = ::record,
    )

    val sessions: StateFlow<List<SessionState>> get() = hub.sessions
    val groups: StateFlow<List<TransferGroup>> get() = hub.groups
    val focusedGroup: StateFlow<TransferGroup?> get() = hub.focusedGroup
    val incomingOffer: StateFlow<IncomingOffer?> get() = hub.incomingOffer
    val live: StateFlow<LiveFeed?> get() = hub.live

    fun start() {
        server.onSocket = hub::onInboundSocket
    }

    // ------------------------------------------------------------ sending --

    /** Send the picked items to every device in [peers] at once. */
    fun sendTo(peers: List<Peer>, uris: List<Uri>): String =
        hub.send(peers, { uris.mapNotNull { context.outgoingFileFor(it) } })

    fun sendTo(peer: Peer, uris: List<Uri>): String = sendTo(listOf(peer), uris)

    /** Text or a link: shown on the receiver with Copy / Open; older versions get a .txt file. */
    fun sendText(peers: List<Peer>, text: String): String {
        val file = textFile(text)
        return hub.send(peers, { listOf(file) }, SendOptions(kind = OfferKind.TEXT, text = text))
    }

    /** One or more local files (camera shots). [live] = part of a live camera session. */
    fun sendFiles(peers: List<Peer>, files: List<File>, live: Boolean = false): String =
        hub.send(
            peers,
            { files.filter { it.isFile }.map { localFile(it) } },
            SendOptions(kind = if (live) OfferKind.CAMERA else null),
            quiet = live,
        )

    fun respondToOffer(accept: Boolean) = hub.respondToOffer(accept)

    fun retryFailed(groupId: String) = hub.retryFailed(groupId)
    fun cancelGroup(groupId: String) = hub.cancelGroup(groupId)
    fun dismissGroup(groupId: String) = hub.dismissGroup(groupId)
    fun focus(groupId: String?) = hub.focus(groupId)
    fun stopLive() = hub.stopLive()

    var autoAcceptOnce: Boolean
        get() = hub.autoAcceptOnce
        set(v) { hub.autoAcceptOnce = v }

    /**
     * Debug: send a generated file to a listener — our own by default, or a remote [host] for
     * cross-device testing without discovery. Exercises handshake, offer sheet, encrypted path, inbox.
     */
    fun selfTransfer(host: String = "127.0.0.1", port: Int = server.port) {
        val target = if (port == 0) server.port else port
        if (target == 0) return
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val file = OutgoingFile(FileMeta("self-test.bin", bytes.size.toLong(), "application/octet-stream")) {
            java.io.ByteArrayInputStream(bytes)
        }
        // Loopback sends accept their own offer sheet.
        if (host == "127.0.0.1" || host == "::1") hub.autoAcceptOnce = true
        val peer = Peer("self-$host-$target", "self", Endpoint(host, target))
        hub.send(listOf(peer), { listOf(file) })
    }

    private fun record(direction: Direction, s: SessionState) {
        if (direction == Direction.SENDING && s.status == SessionStatus.COMPLETED)
            com.crossdrop.share.DirectShare.remember(context, s.peerName)
        val files = s.offer?.files.orEmpty()
        val text = s.kind == OfferKind.TEXT
        runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(),
                    direction = direction.name,
                    peerName = s.peerName,
                    summary = when {
                        text -> if (isLink(s.offer?.text)) "Link" else "Text"
                        files.size == 1 -> files.first().name
                        files.size > 1 -> "${files.size} files"
                        s.currentFileName != null -> s.currentFileName
                        else -> "—"
                    },
                    files = if (text) 1 else files.size,
                    bytes = if (files.isNotEmpty()) files.sumOf { it.size } else s.bytesDone,
                    status = s.status.name,
                )
            )
        }
    }

    companion object {
        const val OFFER_TIMEOUT_MS = TransferHub.OFFER_TIMEOUT_MS

        fun isLink(text: String?): Boolean {
            val t = text?.trim() ?: return false
            return !t.contains(' ') && !t.contains('\n') &&
                (t.startsWith("http://", true) || t.startsWith("https://", true))
        }

        fun textFile(text: String): OutgoingFile {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val name = if (isLink(text)) "Link.txt" else "Text.txt"
            return OutgoingFile(FileMeta(name, bytes.size.toLong(), "text/plain")) { java.io.ByteArrayInputStream(bytes) }
        }

        fun localFile(f: File, mime: String? = null): OutgoingFile =
            OutgoingFile(FileMeta(f.name, f.length(), mime ?: mimeFor(f.name), mtime = f.lastModified())) { f.inputStream() }

        fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "vcf" -> "text/vcard"
            "txt" -> "text/plain"
            else -> null
        }
    }
}
