package com.crossdrop.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.net.ContactReceiver
import com.crossdrop.net.TransferHub
import com.crossdrop.storage.InboxStore
import com.crossdrop.switchphones.AndroidContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Contact cards someone sent on their own: added straight to the address book (skipping people who
 * are already there), or kept in the inbox as a `.vcf` while contacts access is off.
 */
class ContactInbox(
    private val context: Context,
    private val hub: TransferHub,
    private val inbox: InboxStore,
) : ContactReceiver {

    /** What the last cards turned into — the "Added to contacts" card shows it. */
    data class Drop(
        val id: String = UUID.randomUUID().toString(),
        val peerName: String,
        val names: List<String>,
        val added: Int = 0,
        val skipped: Int = 0,
        /** Opens the first contact that was added. */
        val viewUri: Uri? = null,
        /** Saved to the inbox instead, because CrossDrop may not write contacts (yet). */
        val kept: File? = null,
        /** Not a card anything here could read; it's kept in the inbox. */
        val unreadable: Boolean = false,
    )

    private val _state = MutableStateFlow<Drop?>(null)
    val state: StateFlow<Drop?> = _state

    private val staging = File(context.cacheDir, "contacts-in")

    fun canWrite(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun stagingFile(meta: FileMeta): File {
        staging.mkdirs()
        // Leftovers of receives that died midway.
        val now = System.currentTimeMillis()
        staging.listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.delete() }
        return File(staging, UUID.randomUUID().toString() + ".vcf")
    }

    override suspend fun received(peerName: String, offer: Offer, files: List<File>) = withContext(Dispatchers.IO) {
        try {
            val text = files.filter { it.isFile }.joinToString("") { it.readText() }
            val cards = runCatching { VCard.parse(text) }.getOrDefault(emptyList())
            val names = cards.map { it.displayName }
            _state.value = when {
                cards.isEmpty() -> Drop(peerName = peerName, names = names, kept = keep(offer, text), unreadable = true)
                canWrite() -> {
                    val r = AndroidContacts.import(context, cards)
                    Drop(peerName = peerName, names = names, added = r.added, skipped = r.skipped, viewUri = r.firstAdded)
                }
                else -> Drop(peerName = peerName, names = names, kept = keep(offer, text))
            }
        } finally {
            files.forEach { it.delete() }
            hub.pruneQuiet(OfferKind.CONTACT)
        }
    }

    /** After the user allowed contacts access: add the cards that were set aside. */
    suspend fun addKept() = withContext(Dispatchers.IO) {
        val d = _state.value ?: return@withContext
        val f = d.kept ?: return@withContext
        if (d.unreadable || !canWrite() || !f.isFile) return@withContext
        val cards = runCatching { VCard.parse(f.readText()) }.getOrDefault(emptyList())
        val r = AndroidContacts.import(context, cards)
        if (r.added + r.skipped > 0) f.delete()
        _state.value = d.copy(added = r.added, skipped = r.skipped, viewUri = r.firstAdded, kept = null)
    }

    fun dismiss(id: String) {
        if (_state.value?.id == id) _state.value = null
    }

    private fun keep(offer: Offer, text: String): File {
        val name = offer.files.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Contact.vcf"
        val f = inbox.newFileFor(FileMeta(name, text.length.toLong(), "text/vcard"))
        f.writeText(text)
        return f
    }

    private companion object {
        const val STALE_MS = 60 * 60_000L
    }
}
