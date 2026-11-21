package com.crossdrop.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.crossdrop.core.Peer
import com.crossdrop.core.backup.BackupLedger
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.pairing.PairedDevice
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.media.MediaLibrary
import com.crossdrop.net.BackupItem
import com.crossdrop.net.BackupRunner
import com.crossdrop.net.TransferHub
import com.crossdrop.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Back up to a computer" on Android: pairs with one computer, recognises it whenever it shows up on the
 * radar, sends the photos and videos it doesn't have yet (automatically while CrossDrop is open, or on
 * request), and frees up space by deleting phone copies the computer has just re-checked.
 */
class BackupManager(
    private val scope: CoroutineScope,
    private val context: Context,
    hub: TransferHub,
    engine: TransferEngine,
    private val pairings: PairingStore,
    private val peers: StateFlow<Map<String, Peer>>,
    private val history: HistoryStore,
    /** Keeps the process alive (foreground service) while a backup runs. */
    private val keepAlive: () -> Unit,
) {
    enum class PairPhase { CONNECTING, WAITING, DECLINED, FAILED }

    data class Pairing(val peerName: String, val phase: PairPhase, val code: String? = null)

    data class Counts(
        val total: Int, val totalBytes: Long,
        val safe: Int, val safeBytes: Long,
        /** Safely on the computer, older than [FREE_AFTER_DAYS], still on the phone. */
        val freeable: Int, val freeableBytes: Long,
    ) {
        val pending: Int get() = total - safe
        val pendingBytes: Long get() = totalBytes - safeBytes
    }

    data class Run(val itemsDone: Int, val itemsTotal: Int, val bytesDone: Long, val bytesTotal: Long, val bytesPerSec: Long)

    sealed interface FreeUp {
        data object Checking : FreeUp
        /** Ask Android to delete these; it shows its own confirmation. */
        data class Ready(val uris: List<Uri>, val keys: List<String>, val bytes: Long) : FreeUp
        data class Problem(val message: String) : FreeUp
        data class Freed(val items: Int, val bytes: Long) : FreeUp
    }

    data class State(
        val device: PairedDevice? = null,
        val pairing: Pairing? = null,
        /** Where the paired computer is on the radar right now; null when it isn't there. */
        val computer: Peer? = null,
        val run: Run? = null,
        val counts: Counts? = null,
        val lastRunAt: Long = 0,
        val lastError: String? = null,
        val auto: Boolean = true,
        val freeUp: FreeUp? = null,
    )

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val runner = BackupRunner(hub, engine)
    private val _state = MutableStateFlow(State(auto = prefs.getBoolean("auto", true), lastRunAt = prefs.getLong("last_at", 0)))
    val state: StateFlow<State> = _state

    /** Debug builds: back up one folder only (tests must never touch someone's real photos). */
    @Volatile var debugOnlyPath: String? = null

    private var runJob: Job? = null
    private var pairJob: Job? = null
    private var findJob: Job? = null
    /** Radar ids asked "are you my computer?" and when, per pairing; one that didn't answer is asked again later. */
    private val checked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            // The radar changes every few seconds (signal strength, liveness); a check in flight must not be
            // cancelled by that, or a device marked "checked" would never be asked again.
            combine(pairings.devices, peers) { devices, all -> devices.firstOrNull { it.backup } to all }.collect { (device, all) ->
                _state.update { it.copy(device = device) }
                if (device == null) { _state.update { it.copy(computer = null) }; return@collect }
                // Still on the radar? (Its id rotates per launch, so a restart of the computer is a new id.)
                val cur = _state.value.computer
                if (cur != null && cur.instanceId !in all) _state.update { it.copy(computer = null) }
                if (_state.value.computer == null && findJob?.isActive != true) {
                    val now = System.currentTimeMillis()
                    val fresh = all.values.filter { p ->
                        val key = device.id + "|" + p.instanceId
                        p.endpoint != null && p.vault == null && now - (checked[key] ?: 0L) > RECHECK_MS &&
                            checked.put(key, now).let { true }
                    }
                    if (fresh.isNotEmpty()) findJob = scope.launch(Dispatchers.IO) { recognise(device, fresh) }
                }
            }
        }
    }

    private suspend fun recognise(device: PairedDevice, fresh: List<Peer>) {
        val found = runCatching { runner.find(fresh, device.secret()) }.getOrNull() ?: return
        pairings.seen(device.id, found.name)
        _state.update { it.copy(computer = found) }
        if (_state.value.auto && dueForAuto()) backupNow()
    }

    private fun dueForAuto(): Boolean =
        runJob?.isActive != true && System.currentTimeMillis() - prefs.getLong("last_auto", 0) > AUTO_EVERY_MS &&
            MediaLibrary.canReadLibrary(context)

    // ---------------------------------------------------------------- pairing --

    fun pairWith(peer: Peer) {
        pairJob?.cancel()
        _state.update { it.copy(pairing = Pairing(peer.name, PairPhase.CONNECTING)) }
        pairJob = scope.launch(Dispatchers.IO) {
            val secret = runCatching {
                runner.pair(peer, pairings) { code -> _state.update { it.copy(pairing = Pairing(peer.name, PairPhase.WAITING, code)) } }
            }
            when {
                secret.isFailure -> _state.update { it.copy(pairing = Pairing(peer.name, PairPhase.FAILED)) }
                secret.getOrNull() == null -> _state.update { it.copy(pairing = Pairing(peer.name, PairPhase.DECLINED)) }
                else -> {
                    // Other pairings for backups are replaced: one computer at a time.
                    val id = secret.getOrNull()!!.id
                    pairings.devices.value.filter { it.id != id && it.backup }.forEach { unpairQuietly(it.id) }
                    _state.update { it.copy(pairing = null, computer = peer) }
                    refreshCounts()
                }
            }
        }
    }

    fun cancelPairing() {
        pairJob?.cancel()
        _state.update { it.copy(pairing = null) }
    }

    fun unpair() {
        stop()
        _state.value.device?.let { unpairQuietly(it.id) }
        _state.update { State(auto = it.auto) }
    }

    private fun unpairQuietly(id: String) {
        pairings.remove(id)
        ledger(id).clear()
        checked.keys.removeAll { it.startsWith("$id|") }
    }

    fun setAuto(on: Boolean) {
        prefs.edit { putBoolean("auto", on) }
        _state.update { it.copy(auto = on) }
    }

    // ----------------------------------------------------------------- backup --

    private fun ledger(id: String) = BackupLedger(File(context.filesDir, "backup/$id.log"))

    private fun library(): List<MediaLibrary.Item> =
        MediaLibrary.query(context, video = false, debugOnlyPath) + MediaLibrary.query(context, video = true, debugOnlyPath)

    fun backupNow() {
        val device = _state.value.device ?: return
        val peer = _state.value.computer ?: return
        if (runJob?.isActive == true) return
        keepAlive()
        prefs.edit { putLong("last_auto", System.currentTimeMillis()) }
        runJob = scope.launch(Dispatchers.IO) {
            val items = library().map { m -> BackupItem(m.versionKey, m.meta) { MediaLibrary.open(context, m.uri) } }
            val ledger = ledger(device.id)
            _state.update { it.copy(run = Run(0, 0, 0, 0, 0), lastError = null) }
            val outcome = runCatching {
                runner.run(peer, device.secret(), items, ledger) { p ->
                    _state.update { it.copy(run = Run(p.itemsDone, p.itemsTotal, p.bytesDone, p.bytesTotal, p.bytesPerSec)) }
                }
            }.getOrElse { BackupRunner.Outcome.Stopped(0, it.message ?: tr("connection lost")) }
            val now = System.currentTimeMillis()
            prefs.edit { putLong("last_at", now) }
            val (sent, bytes, error) = when (outcome) {
                is BackupRunner.Outcome.Done -> Triple(outcome.sent, outcome.bytes, null)
                is BackupRunner.Outcome.Stopped -> Triple(outcome.sent, 0L, outcome.reason)
            }
            if (sent > 0) runCatching {
                history.add(HistoryStore.Entry(now, "SENDING", device.name, tr("Backup"), sent, bytes, if (error == null) "COMPLETED" else "FAILED"))
            }
            _state.update { it.copy(run = null, lastRunAt = now, lastError = error) }
            refreshCountsNow()
        }
    }

    fun stop() {
        runJob?.cancel()
        _state.update { it.copy(run = null) }
    }

    fun refreshCounts() { scope.launch(Dispatchers.IO) { refreshCountsNow() } }

    private fun refreshCountsNow() {
        val device = _state.value.device ?: return
        if (!MediaLibrary.canReadLibrary(context)) { _state.update { it.copy(counts = null) }; return }
        val lib = library()
        val ledger = ledger(device.id)
        val cutoff = System.currentTimeMillis() - FREE_AFTER_DAYS * 86_400_000L
        var safe = 0; var safeBytes = 0L; var free = 0; var freeBytes = 0L
        for (m in lib) {
            if (!ledger.has(m.versionKey, m.meta.size)) continue
            safe++; safeBytes += m.meta.size
            if (m.takenAt in 1 until cutoff) { free++; freeBytes += m.meta.size }
        }
        _state.update {
            it.copy(counts = Counts(lib.size, lib.sumOf { m -> m.meta.size }, safe, safeBytes, free, freeBytes))
        }
    }

    // -------------------------------------------------------- free up space --

    /** Android 11+ can ask the person to delete many photos at once; Android 10 can't. */
    val canFreeUp: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Have the computer re-check every candidate now; only what it confirms is offered for deleting. */
    fun prepareFreeUp() {
        val device = _state.value.device ?: return
        val peer = _state.value.computer ?: run {
            _state.update { it.copy(freeUp = FreeUp.Problem(tr("Your computer has to be on the same Wi-Fi, with CrossDrop open, to check the files first."))) }
            return
        }
        _state.update { it.copy(freeUp = FreeUp.Checking) }
        scope.launch(Dispatchers.IO) {
            val ledger = ledger(device.id)
            val cutoff = System.currentTimeMillis() - FREE_AFTER_DAYS * 86_400_000L
            val candidates = library().filter { m ->
                m.takenAt in 1 until cutoff && ledger.has(m.versionKey, m.meta.size) && ledger.get(m.versionKey)?.freed != true
            }
            if (candidates.isEmpty()) {
                _state.update { it.copy(freeUp = FreeUp.Problem(tr("Nothing to free up yet."))) }
                return@launch
            }
            val confirmed = runCatching {
                runner.verify(peer, device.secret(), candidates.mapNotNull { ledger.get(it.versionKey) })
            }.getOrElse {
                _state.update { s -> s.copy(freeUp = FreeUp.Problem(tr("Couldn't reach {0}. Try again when it's on the same Wi-Fi.", device.name))) }
                return@launch
            }
            val ok = candidates.filter { it.versionKey in confirmed }
            _state.update {
                it.copy(
                    freeUp = if (ok.isEmpty()) FreeUp.Problem(tr("{0} couldn't confirm any of these files, so nothing will be deleted.", device.name))
                    else FreeUp.Ready(ok.map { m -> m.uri }, ok.map { m -> m.versionKey }, ok.sumOf { m -> m.meta.size }),
                )
            }
        }
    }

    /** Android deleted them (the person confirmed). */
    fun freed(ready: FreeUp.Ready) {
        val device = _state.value.device ?: return
        ledger(device.id).markFreed(ready.keys)
        _state.update { it.copy(freeUp = FreeUp.Freed(ready.keys.size, ready.bytes)) }
        refreshCounts()
    }

    fun dismissFreeUp() = _state.update { it.copy(freeUp = null) }

    private companion object {
        /** Automatic runs at most this often (a run with nothing new costs one library query). */
        const val AUTO_EVERY_MS = 15 * 60_000L
        const val FREE_AFTER_DAYS = 30
        /** A radar device that didn't prove the pairing is asked again after this long. */
        const val RECHECK_MS = 2 * 60_000L
    }
}

