package com.crossdrop.switchphones

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.delta.FileDeltaStore
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.media.MediaSaver
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.SwitchReceiver
import com.crossdrop.net.TransferHub
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receiving side of "Switch phones" on Android: each batch is staged privately, then photos and
 * videos go into the gallery (original dates kept), contacts into the address book (duplicates
 * skipped) and anything else into the inbox. Staged copies are deleted as soon as they're imported.
 */
class SwitchImporter(
    private val context: Context,
    private val hub: TransferHub,
    private val inbox: InboxStore,
    private val history: HistoryStore,
) : SwitchReceiver {

    enum class Phase { RECEIVING, IMPORTING, DONE, FAILED }

    data class State(
        val peerName: String,
        val phase: Phase = Phase.RECEIVING,
        val batch: Int = 0,
        val batches: Int = 0,
        val photosTotal: Int = 0,
        val videosTotal: Int = 0,
        val contactsTotal: Int = 0,
        val bytesTotal: Long = 0,
        val photos: Int = 0,
        val videos: Int = 0,
        val contactsAdded: Int = 0,
        val contactsSkipped: Int = 0,
        val files: Int = 0,
        val failedItems: Int = 0,
        /** Contacts that arrived while the app may not write to the address book. */
        val pendingContacts: File? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val lock = Mutex()

    private val root: File get() = File(context.filesDir, "switch-in")

    /**
     * Each batch stages in its own folder (`b<index>`): the next batch may arrive while this one is
     * still being imported, and names repeat across batches. A retried batch lands in the same
     * folder, so its received chunks resume. Inside, files are prefixed with their position in the
     * offer, so names that sanitise alike can't overwrite each other.
     */
    private fun batchDir(offer: Offer) = File(root, "b" + (offer.batch?.index ?: 0))

    private fun stagedName(offer: Offer, meta: FileMeta): String {
        val i = offer.files.indexOfFirst { it === meta }.takeIf { it >= 0 } ?: offer.files.indexOf(meta)
        return "%05d_%s".format(maxOf(i, 0), sanitize(meta.name))
    }

    override fun storeFor(offer: Offer): DeltaReceiver =
        FileDeltaStore(batchDir(offer)) { meta -> stagedName(offer, meta) }

    override fun batchFile(offer: Offer, meta: FileMeta): File =
        File(batchDir(offer).apply { mkdirs() }, stagedName(offer, meta))

    override fun begin(peerName: String, batch: BatchInfo?) {
        _state.value = State(
            peerName,
            batches = batch?.count ?: 1,
            photosTotal = batch?.photos ?: 0, videosTotal = batch?.videos ?: 0,
            contactsTotal = batch?.contacts ?: 0, bytesTotal = batch?.totalBytes ?: 0,
        )
    }

    override suspend fun importBatch(peerName: String, offer: Offer, files: List<File>) {
        lock.withLock { withContext(Dispatchers.IO) { importNow(peerName, offer, files) } }
    }

    private fun importNow(peerName: String, offer: Offer, files: List<File>) {
        run {
            root.mkdirs()
            if (_state.value == null) begin(peerName, offer.batch)
            _state.update { it?.copy(phase = Phase.IMPORTING, batch = offer.batch?.index ?: it.batch) }
            val folder = "DCIM/" + sanitize(peerName).ifBlank { "CrossDrop" }
            offer.files.forEachIndexed { i, meta ->
                val f = files.getOrNull(i) ?: return@forEachIndexed
                if (!f.isFile) { _state.update { it?.copy(failedItems = it.failedItems + 1) }; return@forEachIndexed }
                // One bad item (full storage, odd file) must not stop the rest of the move.
                try { importItem(f, meta, folder) } catch (e: Exception) {
                    _state.update { it?.copy(failedItems = it.failedItems + 1) }
                    runCatching { moveToInbox(f, meta) }
                }
            }
            // This batch's folder only (with its .staging/.idx): the next batch has its own.
            batchDir(offer).deleteRecursively()
            val last = offer.batch == null || offer.batch!!.index >= offer.batch!!.count - 1
            if (last) finishMove(peerName) else _state.update { it?.copy(phase = Phase.RECEIVING) }
        }
    }

    private fun importItem(f: File, meta: FileMeta, folder: String) {
        // A sender's label alone doesn't put a file in the gallery: it must look like media.
        val media = MediaSaver.isVideo(meta.name, meta.mime) || isImage(meta.name, meta.mime)
        when (if (media) meta.cat else if (meta.cat == ItemCategory.CONTACTS) meta.cat else null) {
            ItemCategory.PHOTO, ItemCategory.VIDEO -> {
                val taken = meta.mtime?.takeIf { it > 0 }
                val ok = MediaSaver.saveToGallery(context, f, meta.name, meta.mime, taken, folder) != null
                _state.update {
                    it?.let { s ->
                        when {
                            !ok -> s.copy(failedItems = s.failedItems + 1)
                            meta.cat == ItemCategory.PHOTO -> s.copy(photos = s.photos + 1)
                            else -> s.copy(videos = s.videos + 1)
                        }
                    }
                }
                if (ok) f.delete() else moveToInbox(f, meta)
            }
            ItemCategory.CONTACTS -> importContacts(f)
            else -> {
                moveToInbox(f, meta)
                _state.update { it?.copy(files = it.files + 1) }
            }
        }
    }

    private fun finishMove(peerName: String) {
        hub.trust.revokeKind(OfferKind.SWITCH)
        _state.update { it?.copy(phase = Phase.DONE) }
        val s = _state.value
        runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(), direction = "RECEIVING", peerName = peerName,
                    summary = "Switch phones",
                    files = (s?.photos ?: 0) + (s?.videos ?: 0) + (s?.contactsAdded ?: 0) + (s?.files ?: 0),
                    bytes = s?.bytesTotal ?: 0, status = "COMPLETED",
                )
            )
        }
    }

    override fun batchEnded(status: SessionStatus, error: String?) {
        _state.update {
            it?.copy(
                phase = Phase.FAILED,
                error = if (status == SessionStatus.CANCELLED) "The other phone stopped the move."
                    else "Connection lost${error?.let { e -> " ($e)" } ?: ""}. Start again on the other phone — it picks up where it stopped.",
            )
        }
    }

    private fun canWriteContacts() =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun importContacts(f: File) {
        if (!canWriteContacts()) {
            val kept = File(inbox.dir, "Contacts from ${sanitize(_state.value?.peerName ?: "phone")}.vcf")
            f.copyTo(kept, overwrite = true)
            f.delete()
            _state.update { it?.copy(pendingContacts = kept) }
            return
        }
        val list = runCatching { VCard.parse(f.readText()) }.getOrDefault(emptyList())
        val r = AndroidContacts.import(context, list)
        _state.update { it?.copy(contactsAdded = it.contactsAdded + r.added, contactsSkipped = it.contactsSkipped + r.skipped) }
        f.delete()
    }

    /** After the user allows contacts access: import what was set aside. */
    suspend fun importPendingContacts() = withContext(Dispatchers.IO) {
        val f = _state.value?.pendingContacts ?: return@withContext
        if (!canWriteContacts()) return@withContext
        val list = runCatching { VCard.parse(f.readText()) }.getOrDefault(emptyList())
        val r = AndroidContacts.import(context, list)
        f.delete()
        _state.update { it?.copy(contactsAdded = it.contactsAdded + r.added, contactsSkipped = it.contactsSkipped + r.skipped, pendingContacts = null) }
    }

    private fun moveToInbox(f: File, meta: FileMeta) {
        val target = inbox.newFileFor(meta)
        if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
    }

    /** Stop accepting batches (the sender's next one asks again) and close the screen. */
    fun stop() {
        hub.trust.revokeKind(OfferKind.SWITCH)
        hub.pruneQuiet(OfferKind.SWITCH)
        _state.value = null
    }

    fun close() {
        hub.pruneQuiet(OfferKind.SWITCH)
        if (_state.value?.phase == Phase.DONE || _state.value?.phase == Phase.FAILED) _state.value = null
    }

    private fun isImage(name: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true ||
            name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp", "avif")

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_")
            .trim().ifBlank { "file" }.take(96)
}
