package com.crossdrop.net

import android.content.Context
import android.net.Uri
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.contacts.ContactShare
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.contacts.VContact
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
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
            override fun received(files: List<File>) { files.forEach { inbox.publish(it) } }
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

    /**
     * Send the picked items to every device in [peers] at once. Contact cards (a `.vcf` from the
     * Contacts app's Share, or picked as a file) go as a contact offer, so the receiver can add them.
     */
    fun sendTo(peers: List<Peer>, uris: List<Uri>): String {
        val cards = uris.isNotEmpty() && uris.all { u ->
            ContactShare.isVCard(u.lastPathSegment, runCatching { context.contentResolver.getType(u) }.getOrNull())
        }
        if (!cards) return hub.send(peers, { uris.mapNotNull { context.outgoingFileFor(it) } })
        return hub.send(
            peers,
            { uris.mapNotNull { context.outgoingFileFor(it)?.let(::asCards) } },
            SendOptions(kind = OfferKind.CONTACT),
            inline = { previewOf(it) },
        )
    }

    fun sendTo(peer: Peer, uris: List<Uri>): String = sendTo(listOf(peer), uris)

    /** Text or a link: shown on the receiver with Copy / Open; older versions get a .txt file. */
    fun sendText(peers: List<Peer>, text: String): String {
        val file = textFile(text)
        return hub.send(peers, { listOf(file) }, SendOptions(kind = OfferKind.TEXT, text = text))
    }

    /** Contact cards picked in CrossDrop: the receiver sees who they are and adds them with one tap. */
    fun sendContacts(peers: List<Peer>, contacts: List<VContact>): String {
        val vcf = VCard.write(contacts)
        val bytes = vcf.toByteArray(Charsets.UTF_8)
        val file = OutgoingFile(
            FileMeta(ContactShare.fileName(contacts), bytes.size.toLong(), "text/vcard", cat = ItemCategory.CONTACTS),
        ) { java.io.ByteArrayInputStream(bytes) }
        return hub.send(peers, { listOf(file) }, SendOptions(kind = OfferKind.CONTACT, text = ContactShare.preview(vcf)))
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
    fun respondToOffer(offer: IncomingOffer, accept: Boolean) = hub.respondToOffer(offer, accept)

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
            return OutgoingFile(FileMeta(name, bytes.size.toLong(), "text/plain")) { java.io.ByteArrayInputStream(bytes) }
        }

        /** A shared `.vcf`, marked as contact cards (read into memory: cards are small). */
        private fun asCards(f: OutgoingFile): OutgoingFile? {
            if (f.meta.size > ContactShare.MAX_CARD_FILE) return f
            val bytes = runCatching {
                f.open().use { readAtMost(it, (ContactShare.MAX_CARD_FILE + 1).toInt()) }
            }.getOrNull() ?: return null
            if (bytes.size > ContactShare.MAX_CARD_FILE) return f          // a backup, not a few cards
            // The Contacts app's share has no file name of its own: name it after the person.
            val name = f.meta.name.takeIf { ContactShare.isVCard(it, null) }
                ?: ContactShare.fileName(runCatching { VCard.parse(bytes.toString(Charsets.UTF_8)) }.getOrDefault(emptyList()))
            return OutgoingFile(f.meta.copy(name = name, size = bytes.size.toLong(), mime = "text/vcard", cat = ItemCategory.CONTACTS)) {
                java.io.ByteArrayInputStream(bytes)
            }
        }

        /** InputStream.readNBytes is API 33+. */
        private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** Offer.text of a contact send: the photo-less cards, when they fit. */
        fun previewOf(files: List<OutgoingFile>): String? {
            val all = files.filter { it.meta.size <= ContactShare.MAX_CARD_FILE }
                .joinToString("") { f -> runCatching { f.open().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("") }
            return ContactShare.preview(all)
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
