package com.crossdrop.net

import com.crossdrop.core.Peer
import com.crossdrop.core.backup.BackupLedger
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.protocol.VerifyItem
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.PairSecret
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

// Shared verbatim with :desktop (copied at build time, like TransferHub) — no android.* here.

/** One photo or video of the phone's library, as a backup sees it. [key] is stable for this exact version. */
class BackupItem(val key: String, val meta: FileMeta, val open: () -> InputStream)

/**
 * The sending side of "Back up to a computer", free of any platform: pairing, finding the paired computer
 * among the devices on the radar, sending what it doesn't have yet in batches, and asking it to re-check
 * files before the phone frees up space. Platforms bring the library (Android: MediaStore) and the UI.
 */
class BackupRunner(private val hub: TransferHub, private val engine: TransferEngine) {

    data class Progress(
        val itemsDone: Int,
        val itemsTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSec: Long = 0,
    )

    sealed interface Outcome {
        /** Everything new is on the computer ([sent] items this time). */
        data class Done(val sent: Int, val bytes: Long) : Outcome
        /** Stopped part-way; what finished is recorded and the next run continues from there. */
        data class Stopped(val sent: Int, val reason: String) : Outcome
    }

    /**
     * Pair with [peer]: its human sees [onCode]'s 6-digit code too and agrees. Stores the pairing on
     * success and returns it; null when they declined or didn't answer.
     */
    suspend fun pair(peer: Peer, store: PairingStore, onCode: (String) -> Unit): PairSecret? {
        val endpoint = peer.endpoint ?: return null
        val id = PairingStore.newId()
        val result = engine.control(endpoint) {
            onCode(sas)
            pair(id)?.let { it to peerName }
        } ?: return null
        val (key, name) = result
        store.add(id, name, key)
        return PairSecret(id, key)
    }

    /**
     * Which of [peers] is the device that holds [secret]. Every candidate is asked at once; the check proves
     * the pairing both ways without revealing it to the others.
     */
    suspend fun find(peers: List<Peer>, secret: PairSecret): Peer? = coroutineScope {
        peers.filter { it.endpoint != null && it.vault == null }.map { p ->
            async {
                withTimeoutOrNull(8_000) {
                    runCatching {
                        engine.control(p.endpoint!!) {
                            // The name it gives itself, not whatever label the radar had for it.
                            if (pairCheck(listOf(secret))?.id == secret.id) p.copy(name = peerName) else null
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().firstOrNull { it != null }
    }

    /**
     * Send everything in [items] the [ledger] doesn't already hold, in batches; record each batch once the
     * computer confirmed every file's BLAKE3 root. Stops at the first failed batch.
     */
    suspend fun run(
        peer: Peer,
        secret: PairSecret,
        items: List<BackupItem>,
        ledger: BackupLedger,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        val todo = items.filter { !ledger.has(it.key, it.meta.size) }
        val bytesTotal = todo.sumOf { it.meta.size }
        val batches = batch(todo)
        var done = 0
        var bytesDone = 0L
        onProgress(Progress(0, todo.size, 0, bytesTotal))
        for ((i, b) in batches.withIndex()) {
            val info = BatchInfo(
                index = i, count = batches.size,
                photos = todo.count { it.meta.cat == ItemCategory.PHOTO },
                videos = todo.count { it.meta.cat == ItemCategory.VIDEO },
                totalBytes = bytesTotal,
            )
            val files = uniqueNames(b).map { (item, name) -> OutgoingFile(item.meta.copy(name = name, key = item.key), item.open) }
            val before = bytesDone
            val result = hub.sendNow(peer, files, SendOptions(kind = OfferKind.BACKUP, batch = info, pairKey = secret.key)) { s ->
                if (s.status == SessionStatus.TRANSFERRING) {
                    onProgress(Progress(done, todo.size, before + s.bytesDone.coerceAtMost(b.sumOf { it.meta.size }), bytesTotal, s.bytesPerSec))
                }
            }
            if (result.status != SessionStatus.COMPLETED) {
                return Outcome.Stopped(done, result.error ?: when (result.status) {
                    SessionStatus.DECLINED -> tr("The computer refused the backup. Pair again.")
                    SessionStatus.CANCELLED -> tr("Stopped")
                    else -> tr("connection lost")
                })
            }
            // v1 receivers give no roots; such a batch isn't proof of anything, so it isn't recorded.
            val roots = result.roots
            if (roots != null && roots.size == b.size) {
                val now = System.currentTimeMillis()
                ledger.record(b.mapIndexed { j, item -> BackupLedger.Entry(item.key, item.meta.size, roots[j], now) })
            }
            done += b.size
            bytesDone += b.sumOf { it.meta.size }
            onProgress(Progress(done, todo.size, bytesDone, bytesTotal))
        }
        return Outcome.Done(done, bytesDone)
    }

    /** The keys among [entries] the paired computer re-read and found intact, right now. */
    suspend fun verify(peer: Peer, secret: PairSecret, entries: List<BackupLedger.Entry>): Set<String> {
        val endpoint = peer.endpoint ?: return emptySet()
        return engine.control(endpoint) {
            if (pairCheck(listOf(secret))?.id != secret.id) return@control emptySet()
            verify(entries.map { VerifyItem(it.key, it.root, it.size) })
        }
    }

    /** Up to [MAX_ITEMS] items or [MAX_BYTES] per batch; an item larger than that rides alone. */
    private fun batch(items: List<BackupItem>): List<List<BackupItem>> {
        val out = ArrayList<List<BackupItem>>()
        var cur = ArrayList<BackupItem>()
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

    /** Two "IMG_0001.JPG" from different folders must not overwrite each other in the batch. */
    private fun uniqueNames(items: List<BackupItem>): List<Pair<BackupItem, String>> {
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
