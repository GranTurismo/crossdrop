package com.crossdrop.net

import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.core.Peer
import com.crossdrop.core.contacts.ContactShare
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
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
 * Desktop face of the shared [TransferHub]: local files and text out, the inbox for files in,
 * history for every finished session. The UI observes the hub's flows; it never touches sockets.
 */
class TransferCoordinator(
    scope: CoroutineScope,
    engine: TransferEngine,
    private val server: TransferServer,
    private val inbox: InboxStore,
    private val history: HistoryStore,
    resumePolicy: com.crossdrop.core.transfer.ResumePolicy = com.crossdrop.core.transfer.ResumePolicy(),
) {
    val hub = TransferHub(
        scope, engine,
        port = object : ReceivePort {
            override fun newInboxFile(meta: FileMeta): File = inbox.uniqueFile(meta)
            override val inboxDelta: DeltaReceiver get() = inbox.delta
        },
        onEnded = ::record,
        resumePolicy = resumePolicy,
    )

    val sessions: StateFlow<List<SessionState>> get() = hub.sessions
    val groups: StateFlow<List<TransferGroup>> get() = hub.groups
    val focusedGroup: StateFlow<TransferGroup?> get() = hub.focusedGroup
    val incomingOffer: StateFlow<IncomingOffer?> get() = hub.incomingOffer
    val live: StateFlow<LiveFeed?> get() = hub.live

    fun start() {
        server.onSocket = hub::onInboundSocket
    }

    /**
     * Send the same files to every device in [peers] at once. Contact cards (`.vcf`) go as a contact
     * offer, so a phone on the other end can add them to its address book with one tap.
     */
    fun sendTo(peers: List<Peer>, picked: List<File>): String {
        val cards = picked.isNotEmpty() && picked.all {
            it.isFile && ContactShare.isVCard(it.name, null) && it.length() <= ContactShare.MAX_CARD_FILE
        }
        if (!cards) return hub.send(peers, { picked.toOutgoingFiles() })
        return hub.send(
            peers,
            {
                picked.mapNotNull { f ->
                    f.toOutgoingFile()?.let { o -> OutgoingFile(o.meta.copy(mime = "text/vcard", cat = ItemCategory.CONTACTS), o.open) }
                }
            },
            SendOptions(kind = OfferKind.CONTACT),
            inline = { files ->
                ContactShare.preview(files.joinToString("") { f -> runCatching { f.open().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("") })
            },
        )
    }

    fun sendTo(peer: Peer, picked: List<File>): String = sendTo(listOf(peer), picked)

    /** Text or a link: the receiver gets Copy / Open; older versions receive a .txt file. */
    fun sendText(peers: List<Peer>, text: String): String =
        hub.send(peers, { listOf(textFile(text)) }, SendOptions(kind = OfferKind.TEXT, text = text))

    fun respondToOffer(accept: Boolean) = hub.respondToOffer(accept)
    fun respondToOffer(offer: IncomingOffer, accept: Boolean) = hub.respondToOffer(offer, accept)
    fun retryFailed(groupId: String) = hub.retryFailed(groupId)
    fun cancelGroup(groupId: String) = hub.cancelGroup(groupId)
    fun dismissGroup(groupId: String) = hub.dismissGroup(groupId)
    fun focus(groupId: String?) = hub.focus(groupId)
    fun stopLive() = hub.stopLive()

    private fun record(direction: Direction, s: SessionState) {
        val files = s.offer?.files.orEmpty()
        val text = s.kind == OfferKind.TEXT
        val cards = s.kind == OfferKind.CONTACT
        runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(),
                    direction = direction.name,
                    peerName = s.peerName,
                    summary = when {
                        text -> if (isLink(s.offer?.text)) tr("Link") else tr("Text")
                        cards -> files.firstOrNull()?.name?.removeSuffix(".vcf") ?: tr("Contact")
                        files.size == 1 -> files.first().name
                        files.size > 1 -> trPlural(files.size, "1 file", "{n} files")
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
            return OutgoingFile(FileMeta(name, bytes.size.toLong(), "text/plain")) { bytes.inputStream() }
        }
    }
}
