package com.crossdrop.switchphones

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import com.crossdrop.core.Peer
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.TransferHub
import com.crossdrop.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sending side of "Switch phones": the whole gallery and address book to one device, in batches.
 *
 * Each batch is one ordinary transfer (kind = switch). The receiver accepts the first one and its
 * trust grant lets the rest through without asking. Finished batches are written to a ledger, so a
 * dropped connection resumes with the next unfinished batch, and the unfinished one resumes from its
 * received chunks (delta/resume) rather than from zero.
 */
class SwitchSender(
    private val scope: CoroutineScope,
    private val context: Context,
    private val hub: TransferHub,
    private val history: HistoryStore,
) {
    data class Plan(
        val photos: Int = 0, val photoBytes: Long = 0,
        val videos: Int = 0, val videoBytes: Long = 0,
        val contacts: Int = 0,
        /** Android 14 "selected photos only": the gallery the app sees is partial. */
        val partialMedia: Boolean = false,
    )

    enum class Phase { PREPARING, WAITING, MOVING, DONE, FAILED, DECLINED, CANCELLED }

    data class State(
        val peerName: String,
        val phase: Phase,
        val batch: Int = 0,
        val batches: Int = 0,
        val itemsDone: Int = 0,
        val itemsTotal: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        val bytesPerSec: Long = 0,
        val error: String? = null,
        val photos: Int = 0,
        val videos: Int = 0,
        val contacts: Int = 0,
        val skippedDone: Int = 0,
    ) {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
        val finished: Boolean get() = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.DECLINED || phase == Phase.CANCELLED
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var job: Job? = null
    private var last: Triple<Peer, Set<String>, Boolean>? = null
    private val prefs = context.getSharedPreferences("switch_phones", Context.MODE_PRIVATE)

    private class Item(val key: String, val meta: FileMeta, val open: () -> java.io.InputStream)

    // ------------------------------------------------------------- plan --

    suspend fun plan(includeContacts: Boolean): Plan = withContext(Dispatchers.IO) {
        val photos = queryMedia(video = false)
        val videos = queryMedia(video = true)
        Plan(
            photos = photos.size, photoBytes = photos.sumOf { it.meta.size },
            videos = videos.size, videoBytes = videos.sumOf { it.meta.size },
            contacts = if (includeContacts) AndroidContacts.count(context) else 0,
            partialMedia = android.os.Build.VERSION.SDK_INT >= 34 &&
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun queryMedia(video: Boolean): List<Item> {
        val uri = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<Item>()
        runCatching {
            context.contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    val itemUri: Uri = ContentUris.withAppendedId(uri, id)
                    val taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000)
                    out += Item(
                        (if (video) "v:" else "p:") + id,
                        FileMeta(
                            c.getString(1) ?: "item_$id", size, c.getString(3),
                            cat = if (video) ItemCategory.VIDEO else ItemCategory.PHOTO,
                            mtime = taken.takeIf { it > 0 },
                        ),
                    ) { context.contentResolver.openInputStream(itemUri) ?: throw java.io.FileNotFoundException("$itemUri") }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ moving --

    fun start(peer: Peer, photos: Boolean, videos: Boolean, contacts: Boolean) {
        val cats = buildSet {
            if (photos) add(ItemCategory.PHOTO)
            if (videos) add(ItemCategory.VIDEO)
            if (contacts) add(ItemCategory.CONTACTS)
        }
        last = Triple(peer, cats, true)
        job?.cancel()
        _state.value = State(peer.name, Phase.PREPARING)
        job = scope.launch(Dispatchers.IO) { run(peer, cats) }
    }

    /** Pick up after a failure with the next unfinished batch. [peer] may have a new address. */
    fun resume(peer: Peer? = null) {
        val (p, cats, _) = last ?: return
        start(peer ?: p, ItemCategory.PHOTO in cats, ItemCategory.VIDEO in cats, ItemCategory.CONTACTS in cats)
    }

    fun cancel() {
        job?.cancel()
        _state.update { it?.copy(phase = Phase.CANCELLED, bytesPerSec = 0) }
    }

    fun close() {
        if (_state.value?.finished != false) _state.value = null
    }

    private suspend fun run(peer: Peer, cats: Set<String>) {
        val ledgerKey = "done:" + peer.name
        val done = prefs.getStringSet(ledgerKey, emptySet()).orEmpty().toMutableSet()

        val items = ArrayList<Item>()
        var contactCount = 0
        if (ItemCategory.CONTACTS in cats) {
            val list = runCatching { AndroidContacts.readAll(context, withPhotos = true) }.getOrDefault(emptyList())
            contactCount = list.size
            if (list.isNotEmpty()) {
                val dir = File(context.cacheDir, "switch").apply { mkdirs() }
                val vcf = File(dir, "Contacts.vcf").apply { writeText(VCard.write(list)) }
                items += Item("c:all", FileMeta("Contacts.vcf", vcf.length(), "text/vcard", cat = ItemCategory.CONTACTS)) { vcf.inputStream() }
            }
        }
        val photoItems = if (ItemCategory.PHOTO in cats) queryMedia(video = false) else emptyList()
        val videoItems = if (ItemCategory.VIDEO in cats) queryMedia(video = true) else emptyList()
        items += photoItems
        items += videoItems

        val todo = items.filter { it.key !in done }
        val skipped = items.size - todo.size
        val batches = batch(todo)
        val bytesTotal = todo.sumOf { it.meta.size }
        _state.value = State(
            peer.name, Phase.WAITING, batch = 0, batches = batches.size,
            itemsTotal = items.size, itemsDone = skipped, bytesTotal = bytesTotal,
            photos = photoItems.size, videos = videoItems.size, contacts = contactCount, skippedDone = skipped,
        )
        if (batches.isEmpty()) {
            finish(peer, ledgerKey, items.size)
            return
        }

        var bytesBefore = 0L
        for ((i, b) in batches.withIndex()) {
            val info = BatchInfo(
                index = i, count = batches.size,
                photos = photoItems.size, videos = videoItems.size, contacts = contactCount,
                totalBytes = bytesTotal,
            )
            val files = uniqueNames(b).map { (item, name) -> OutgoingFile(item.meta.copy(name = name), item.open) }
            _state.update { it?.copy(batch = i) }
            val result = hub.sendNow(peer, files, SendOptions(kind = OfferKind.SWITCH, batch = info)) { s ->
                _state.update { st ->
                    st?.copy(
                        phase = if (s.status == SessionStatus.TRANSFERRING) Phase.MOVING else st.phase,
                        bytesDone = bytesBefore + if (s.status == SessionStatus.TRANSFERRING) s.bytesDone else 0,
                        bytesPerSec = s.bytesPerSec,
                    )
                }
            }
            when (result.status) {
                SessionStatus.COMPLETED -> {
                    done += b.map { it.key }
                    prefs.edit { putStringSet(ledgerKey, done.toSet()) }
                    bytesBefore += b.sumOf { it.meta.size }
                    _state.update { it?.copy(itemsDone = it.itemsDone + b.size, bytesDone = bytesBefore, phase = Phase.MOVING) }
                }
                SessionStatus.DECLINED -> {
                    _state.update { it?.copy(phase = Phase.DECLINED, bytesPerSec = 0) }
                    return
                }
                SessionStatus.CANCELLED -> {
                    _state.update { it?.copy(phase = Phase.CANCELLED, bytesPerSec = 0) }
                    return
                }
                else -> {
                    _state.update { it?.copy(phase = Phase.FAILED, error = result.error ?: "connection lost", bytesPerSec = 0) }
                    return
                }
            }
        }
        finish(peer, ledgerKey, items.size)
    }

    private fun finish(peer: Peer, ledgerKey: String, total: Int) {
        prefs.edit { remove(ledgerKey) }
        _state.update { it?.copy(phase = Phase.DONE, bytesPerSec = 0, itemsDone = total) }
        runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(), direction = "SENDING", peerName = peer.name,
                    summary = "Switch phones", files = total, bytes = _state.value?.bytesTotal ?: 0, status = "COMPLETED",
                )
            )
        }
    }

    /** Up to [MAX_ITEMS] items or [MAX_BYTES] per batch; an item larger than that rides alone. */
    private fun batch(items: List<Item>): List<List<Item>> {
        val out = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        var bytes = 0L
        for (it in items) {
            if (cur.isNotEmpty() && (cur.size >= MAX_ITEMS || bytes + it.meta.size > MAX_BYTES)) {
                out += cur; cur = ArrayList(); bytes = 0
            }
            cur += it; bytes += it.meta.size
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Two "IMG_0001.JPG" from different folders must not overwrite each other on the receiver. */
    private fun uniqueNames(items: List<Item>): List<Pair<Item, String>> {
        val seen = HashSet<String>()
        return items.map { item ->
            val base = item.meta.name.replace('/', '_').replace('\\', '_')
            var name = base
            var n = 1
            while (!seen.add(name.lowercase())) {
                val ext = base.substringAfterLast('.', "")
                name = base.substringBeforeLast('.') + " ($n)" + if (ext.isNotEmpty() && base.contains('.')) ".$ext" else ""
                n++
            }
            item to name
        }
    }

    companion object {
        const val MAX_ITEMS = 100
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
