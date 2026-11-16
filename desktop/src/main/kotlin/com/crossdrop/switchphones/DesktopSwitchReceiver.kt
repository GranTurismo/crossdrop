package com.crossdrop.switchphones

import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.delta.FileDeltaStore
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.SwitchReceiver
import com.crossdrop.net.TransferHub
import com.crossdrop.platform.AppDirs
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A phone "switching" to this computer: its whole library lands in a folder under Pictures
 * (`CrossDrop/<phone> <date>/`), photos and videos with their original dates, contacts as one
 * `Contacts.vcf` any address book imports. A ready-made phone backup.
 */
class DesktopSwitchReceiver(
    private val hub: TransferHub,
    private val history: HistoryStore,
    private val picturesDir: File = File(System.getProperty("user.home"), "Pictures"),
) : SwitchReceiver {

    enum class Phase { RECEIVING, IMPORTING, DONE, FAILED }

    data class State(
        val peerName: String,
        val folder: File,
        val phase: Phase = Phase.RECEIVING,
        val batch: Int = 0,
        val batches: Int = 0,
        val photosTotal: Int = 0,
        val videosTotal: Int = 0,
        val contactsTotal: Int = 0,
        val bytesTotal: Long = 0,
        val photos: Int = 0,
        val videos: Int = 0,
        val contacts: Int = 0,
        val files: Int = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val lock = Mutex()
    private val staging = File(AppDirs.support, "switch-in")

    override val batchStore: DeltaReceiver = FileDeltaStore(staging) { InboxStore.sanitizeName(it.name) }

    override fun batchFile(meta: FileMeta): File = File(staging.apply { mkdirs() }, InboxStore.sanitizeName(meta.name))

    override fun begin(peerName: String, batch: BatchInfo?) {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val folder = unique(File(picturesDir, "CrossDrop/" + InboxStore.sanitizeName("$peerName $stamp")))
        _state.value = State(
            peerName, folder,
            batches = batch?.count ?: 1,
            photosTotal = batch?.photos ?: 0, videosTotal = batch?.videos ?: 0,
            contactsTotal = batch?.contacts ?: 0, bytesTotal = batch?.totalBytes ?: 0,
        )
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        var i = 2
        while (File(f.parentFile, "${f.name} ($i)").exists()) i++
        return File(f.parentFile, "${f.name} ($i)")
    }

    override suspend fun importBatch(peerName: String, offer: Offer, files: List<File>) {
        lock.withLock { withContext(Dispatchers.IO) { importNow(peerName, offer, files) } }
    }

    private fun importNow(peerName: String, offer: Offer, files: List<File>) {
        if (_state.value == null) begin(peerName, offer.batch)
        val folder = _state.value!!.folder.apply { mkdirs() }
        _state.update { it?.copy(phase = Phase.IMPORTING, batch = offer.batch?.index ?: it.batch) }
        offer.files.forEachIndexed { i, meta ->
            val f = files.getOrNull(i)?.takeIf { it.isFile } ?: return@forEachIndexed
            val target = uniqueFile(folder, InboxStore.sanitizeName(meta.name))
            if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
            meta.mtime?.let { target.setLastModified(it) }
            _state.update {
                it?.let { s ->
                    when (meta.cat) {
                        ItemCategory.PHOTO -> s.copy(photos = s.photos + 1)
                        ItemCategory.VIDEO -> s.copy(videos = s.videos + 1)
                        ItemCategory.CONTACTS -> s.copy(contacts = s.contactsTotal)
                        else -> s.copy(files = s.files + 1)
                    }
                }
            }
        }
        // Only this batch's index sidecars: the next batch may already be arriving.
        files.forEach { f -> File(staging, ".idx/${f.name}.idx").delete() }
        val last = offer.batch == null || offer.batch!!.index >= offer.batch!!.count - 1
        if (last) {
            hub.trust.revokeKind(OfferKind.SWITCH)
            _state.update { it?.copy(phase = Phase.DONE) }
            val s = _state.value
            runCatching {
                history.add(
                    HistoryStore.Entry(
                        ts = System.currentTimeMillis(), direction = "RECEIVING", peerName = peerName,
                        summary = "Phone backup", files = (s?.photos ?: 0) + (s?.videos ?: 0) + (s?.files ?: 0),
                        bytes = s?.bytesTotal ?: 0, status = "COMPLETED",
                    )
                )
            }
        } else _state.update { it?.copy(phase = Phase.RECEIVING) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (f.exists()) { f = File(dir, "$stem ($i)$ext"); i++ }
        return f
    }

    override fun batchEnded(status: SessionStatus, error: String?) {
        _state.update {
            it?.copy(
                phase = Phase.FAILED,
                error = if (status == SessionStatus.CANCELLED) "The phone stopped the move."
                    else "Connection lost. Start again on the phone — it picks up where it stopped.",
            )
        }
    }

    fun stop() {
        hub.trust.revokeKind(OfferKind.SWITCH)
        hub.pruneQuiet(OfferKind.SWITCH)
        _state.value = null
    }

    fun close() {
        hub.pruneQuiet(OfferKind.SWITCH)
        val p = _state.value?.phase
        if (p == Phase.DONE || p == Phase.FAILED) _state.value = null
    }
}
