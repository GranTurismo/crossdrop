package com.crossdrop.backup

import com.crossdrop.core.backup.BackupIndex
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.delta.FileDeltaStore
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.VerifyItem
import com.crossdrop.net.BackupReceiver
import com.crossdrop.net.SessionStatus
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
import java.time.Instant
import java.time.ZoneId

/**
 * This computer as the backup of a paired phone: batches arrive without a prompt, are staged per batch,
 * then filed as `<root>/<phone>/<year>/<month>/<name>` with their original dates. An index per pairing
 * (key → path, size, BLAKE3 root) skips what is already here, even under another name, and answers the
 * phone's "do you still hold these?" by re-reading the files before it frees up space.
 */
class DesktopBackupReceiver(
    private val pairings: PairingStore,
    private val history: HistoryStore,
    /** Where backups go; each phone gets its own folder inside. */
    private val root: () -> File,
    /** Staging and indexes: app state, never inside the backup folder. */
    private val stateDir: File,
    private val folderNames: FolderNames,
) : BackupReceiver {

    /** Remembers which folder each pairing's backup lives in, so renaming the phone doesn't start a new one. */
    interface FolderNames {
        fun get(pairId: String): String?
        fun put(pairId: String, name: String)
    }

    data class Summary(val pairId: String, val deviceName: String, val folder: File, val items: Int, val lastAt: Long, val lastAdded: Int)

    private val _last = MutableStateFlow<Map<String, Summary>>(emptyMap())
    /** The latest batch filed per pairing, for Settings ("last backup 2 min ago, 12 new"). */
    val last: StateFlow<Map<String, Summary>> = _last

    private val lock = Mutex()
    private val indexes = HashMap<String, BackupIndex>()

    private fun staging(pairId: String, offer: Offer) =
        File(stateDir, "backup-in/$pairId/b" + (offer.batch?.index ?: 0))

    private fun stagedName(offer: Offer, meta: FileMeta): String {
        val i = offer.files.indexOfFirst { it === meta }.takeIf { it >= 0 } ?: offer.files.indexOf(meta)
        return "%05d_%s".format(maxOf(i, 0), InboxStore.sanitizeName(meta.name))
    }

    /** The folder of [pairId]'s backup, fixed the first time it is used. */
    fun folderFor(pairId: String): File {
        val name = folderNames.get(pairId) ?: run {
            val base = InboxStore.sanitizeName(pairings.get(pairId)?.name ?: "Phone")
            var n = base
            var i = 2
            while (File(root(), n).exists()) n = "$base ($i)".also { i++ }
            folderNames.put(pairId, n)
            n
        }
        return File(root(), name)
    }

    fun index(pairId: String): BackupIndex = synchronized(indexes) {
        indexes.getOrPut(pairId) { BackupIndex(File(stateDir, "backup-$pairId.json"), folderFor(pairId)) }
    }

    override fun storeFor(pairId: String, offer: Offer): DeltaReceiver =
        FileDeltaStore(staging(pairId, offer)) { stagedName(offer, it) }

    override fun batchFile(pairId: String, offer: Offer, meta: FileMeta): File =
        File(staging(pairId, offer).apply { mkdirs() }, stagedName(offer, meta))

    override suspend fun importBatch(pairId: String, peerName: String, offer: Offer, files: List<File>, roots: List<String>?) {
        lock.withLock { withContext(Dispatchers.IO) { importNow(pairId, peerName, offer, files, roots) } }
    }

    private fun importNow(pairId: String, peerName: String, offer: Offer, files: List<File>, roots: List<String>?) {
        val index = index(pairId)
        val folder = index.root.apply { mkdirs() }
        val added = HashMap<String, BackupIndex.Item>()
        var newFiles = 0
        offer.files.forEachIndexed { i, meta ->
            val f = files.getOrNull(i)?.takeIf { it.isFile } ?: return@forEachIndexed
            val key = meta.key ?: return@forEachIndexed
            val root = roots?.getOrNull(i) ?: runCatching { BackupIndex.blake3Hex(f) }.getOrNull() ?: return@forEachIndexed
            val now = System.currentTimeMillis()
            // Already here under this key, or the same bytes under another (a reinstall, a renamed file): keep one copy.
            val known = index.get(key)?.takeIf { it.root == root && index.resolve(it.path)?.isFile == true }
                ?: index.findContent(root, meta.size)?.takeIf { index.resolve(it.path)?.isFile == true }
                ?: added.values.firstOrNull { it.root == root && it.size == meta.size }   // earlier in this batch
            if (known != null) {
                added[key] = known.copy(at = now)
                f.delete()
                return@forEachIndexed
            }
            val dir = File(folder, monthFolder(meta.mtime)).apply { mkdirs() }
            val target = uniqueFile(dir, InboxStore.sanitizeName(meta.name))
            val moved = runCatching {
                if (!f.renameTo(target)) { f.copyTo(target, overwrite = false); f.delete() }
            }.isSuccess
            if (!moved) return@forEachIndexed
            meta.mtime?.takeIf { it > 0 }?.let { runCatching { target.setLastModified(it) } }
            added[key] = BackupIndex.Item(target.relativeTo(folder).invariantSeparatorsPath, meta.size, root, now)
            newFiles++
        }
        index.put(added)
        staging(pairId, offer).deleteRecursively()
        val name = pairings.get(pairId)?.name ?: peerName
        val now = System.currentTimeMillis()
        _last.update { all ->
            // Batches of one run arrive minutes apart at most: count the run's new files together.
            val sameRun = all[pairId]?.takeIf { now - it.lastAt < RUN_GAP_MS }
            all + (pairId to Summary(pairId, name, folder, index.size(), now, (sameRun?.lastAdded ?: 0) + newFiles))
        }
        val last = offer.batch == null || offer.batch!!.index >= offer.batch!!.count - 1
        if (last) runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(), direction = "RECEIVING", peerName = name,
                    summary = tr("Phone backup"), files = _last.value[pairId]?.lastAdded ?: newFiles,
                    bytes = offer.batch?.totalBytes ?: offer.files.sumOf { it.size }, status = "COMPLETED",
                ),
            )
        }
    }

    override fun verify(pairId: String, items: List<VerifyItem>): List<String> = index(pairId).verify(items)

    override fun batchEnded(pairId: String, status: SessionStatus, error: String?) = Unit

    /** Forget a pairing's index and staging (the files stay where they are). */
    fun forget(pairId: String) {
        synchronized(indexes) { indexes.remove(pairId) }
        File(stateDir, "backup-$pairId.json").delete()
        File(stateDir, "backup-in/$pairId").deleteRecursively()
    }

    private fun monthFolder(mtime: Long?): String {
        // Not translated: a folder name must not change with the app's language.
        if (mtime == null || mtime <= 0) return "Undated"
        val d = Instant.ofEpochMilli(mtime).atZone(ZoneId.systemDefault())
        return "%04d/%02d".format(d.year, d.monthValue)
    }

    private companion object {
        const val RUN_GAP_MS = 10 * 60_000L
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
}
