package com.crossdrop.net

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.MAX_CLIPBOARD_BYTES
import com.crossdrop.core.protocol.ProtocolException
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// Shared verbatim with :desktop (copied at build time, like TransferHub) — no android.* here.

/** What last happened between this device's clipboard and one of "My devices". */
enum class ClipShare {
    /** A copy made here went there. */
    SENT,
    /** A copy made there was put on this clipboard. */
    RECEIVED,
    /** Clipboard sharing with this device is off on the other device. */
    OFF_THERE,
    /** The other device shares its clipboard with us, but sharing is off here (its copy was refused). */
    WANTED,
    /** The other device runs a CrossDrop that predates clipboard sharing. */
    OLD_VERSION,
    /** It couldn't be reached, or whoever answered couldn't prove the pairing. */
    FAILED,
}

/** What [ClipboardSync.copied] did with a copy. */
enum class CopyOutcome { SHARING, NOT_SHARING, TOO_LONG, EMPTY, UNCHANGED }

/** What an explicit "share my clipboard now" achieved: pairing id → result (null: the device wasn't found in time). */
class ShareNowResult(val outcome: CopyOutcome, val results: Map<String, ClipShare?>)

/**
 * Clipboard sharing with "My devices": text copied on this device goes to each paired device that has sharing on and is
 * here, and their copies land on this device's clipboard. Text only, at most [MAX_CLIPBOARD_BYTES].
 *
 * - Only paired devices, only with the per-device switch on ([com.crossdrop.core.pairing.PairedDevice.clipboard]), and
 *   only after the other device proved the pairing on that same connection (PAIR_CHECK; a device that borrows the
 *   name or the radar id gets nothing). The other side must have its switch on too, or it refuses the copy.
 * - The platform watches its own clipboard (only while [active]) and calls [copied]; password managers' copies are
 *   skipped there (they mark them). Whatever arrives is handed to [apply].
 * - A copy that just arrived is not sent back (its text is remembered), and is not passed on to a third device: only
 *   the devices the copying device itself paired with get it.
 * - A copy waits for a device that isn't on the radar yet for [freshMs], no longer: an old copy turning up later
 *   would overwrite whatever was copied there since.
 */
class ClipboardSync(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val pairings: PairingStore,
    /** Pairing id → the radar entry that proved it ([PairedPresence.present]). */
    private val present: StateFlow<Map<String, Peer>>,
    /** Put text on this device's clipboard (any thread). */
    private val apply: (String) -> Unit,
    private val freshMs: Long = FRESH_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClipboardReceiver {

    /** True while any paired device has clipboard sharing on: the platform watches the clipboard only then. */
    val active: StateFlow<Boolean> = pairings.devices.map { d -> d.any { it.clipboard } }
        .stateIn(scope, SharingStarted.Eagerly, pairings.devices.value.any { it.clipboard })

    private val _status = MutableStateFlow<Map<String, ClipShare>>(emptyMap())
    /** Pairing id → what last happened with that device's clipboard. */
    val status: StateFlow<Map<String, ClipShare>> = _status

    private class Clip(val text: String, val hash: String, val at: Long, val seq: Long)

    private val lock = Any()
    private var seq = 0L
    /** The last copy made here, until it has gone everywhere it can go (or got too old). */
    private var latest: Clip? = null
    /** Hash of the text this device's clipboard is known to hold: copied here, or taken from a device. */
    private var known: String? = null
    /** Pairing id → [Clip.seq] of the copy last sent (or tried) there. */
    private val tried = HashMap<String, Long>()
    /** Pairing id → (seq, result) of the last try, for [shareNow]. */
    private val outcomes = MutableStateFlow<Map<String, Pair<Long, ClipShare>>>(emptyMap())
    /** Ids of copies taken: the same id again is a recorded connection played back. */
    private val seen = LinkedHashSet<String>()
    private val sending = ConcurrentHashMap<String, Mutex>()

    init {
        // A device showing up (or its switch going on) may be owed the last copy.
        scope.launch { combine(present, pairings.devices) { _, _ -> }.collect { deliver() } }
    }

    /**
     * This device's clipboard now holds [text] (the platform noticed a copy). It goes to every device with sharing on that
     * is here, or that turns up within [freshMs]. [force] sends it even when it is what the clipboard already held
     * (someone asked for it explicitly).
     */
    fun copied(text: String, force: Boolean = false): CopyOutcome {
        if (text.isBlank()) return CopyOutcome.EMPTY
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) return CopyOutcome.TOO_LONG
        if (pairings.devices.value.none { it.clipboard }) return CopyOutcome.NOT_SHARING
        val h = hash(text)
        synchronized(lock) {
            if (!force && h == known) return CopyOutcome.UNCHANGED
            known = h
            latest = Clip(text, h, clock(), ++seq)
        }
        deliver()
        return CopyOutcome.SHARING
    }

    /**
     * Share [text] now and wait (up to [waitMs]) until every device with sharing on got it or refused it. A device that
     * isn't on the radar yet is waited for, within that time.
     */
    suspend fun shareNow(text: String, waitMs: Long = 12_000): ShareNowResult {
        val outcome = copied(text, force = true)
        val targets = pairings.devices.value.filter { it.clipboard }.map { it.id }
        val mine = synchronized(lock) { latest?.seq }
        if (outcome != CopyOutcome.SHARING || mine == null) return ShareNowResult(outcome, emptyMap())
        withTimeoutOrNull(waitMs) { outcomes.first { m -> targets.all { m[it]?.first == mine } } }
        val m = outcomes.value
        return ShareNowResult(outcome, targets.associateWith { id -> m[id]?.takeIf { it.first == mine }?.second })
    }

    /** Forget what happened with [pairId] (unpaired). */
    fun forget(pairId: String) {
        synchronized(lock) { tried.remove(pairId) }
        _status.update { it - pairId }
        outcomes.update { it - pairId }
    }

    override fun received(pairId: String, clipId: String, text: String): Boolean {
        val device = pairings.get(pairId) ?: return false
        if (!device.clipboard) {
            _status.update { it + (pairId to ClipShare.WANTED) }
            return false
        }
        val h = hash(text)
        synchronized(lock) {
            if (!seen.add(clipId)) return false
            while (seen.size > MAX_SEEN) seen.remove(seen.first())
            // Before it lands, so the platform's watcher sees it as ours and doesn't send it straight back. And ours is no
            // longer what's on the clipboard: nothing of ours is owed to anyone now.
            known = h
            latest = null
        }
        if (runCatching { apply(text) }.isFailure) return false
        _status.update { it + (pairId to ClipShare.RECEIVED) }
        pairings.seen(pairId, null)
        return true
    }

    private fun deliver() {
        val clip = synchronized(lock) { latest } ?: return
        if (clock() - clip.at > freshMs) return
        val here = present.value
        for (d in pairings.devices.value) {
            if (!d.clipboard || here[d.id]?.endpoint == null) continue
            if (synchronized(lock) { tried[d.id] == clip.seq }) continue
            scope.launch(Dispatchers.IO) { sendTo(d.id) }
        }
    }

    /** One device at a time, newest copy only: two quick copies must not arrive the wrong way round. */
    private suspend fun sendTo(pairId: String) = sending.getOrPut(pairId) { Mutex() }.withLock {
        val device = pairings.get(pairId)?.takeIf { it.clipboard } ?: return@withLock
        val endpoint = present.value[pairId]?.endpoint ?: return@withLock
        val secret = runCatching { device.secret() }.getOrNull() ?: return@withLock
        val clip = synchronized(lock) {
            latest?.takeIf { tried[pairId] != it.seq && clock() - it.at <= freshMs }?.also { tried[pairId] = it.seq }
        } ?: return@withLock
        val answer = withTimeoutOrNull(SEND_TIMEOUT_MS) {
            runCatching {
                engine.control(endpoint, connectTimeoutMs = 2_000, readTimeoutMs = 5_000) {
                    // Proven both ways first: the copy only ever goes to the device that holds this pairing.
                    if (pairCheck(listOf(secret)) == null) null else clipboard(clip.text)
                }
            }
        }
        val result = when {
            answer == null -> ClipShare.FAILED
            answer.isFailure -> when (answer.exceptionOrNull()) {
                // An older build fails the unknown frame and drops the connection.
                is EOFException, is ProtocolException -> ClipShare.OLD_VERSION
                else -> ClipShare.FAILED
            }
            answer.getOrNull() == null -> ClipShare.FAILED
            answer.getOrNull() == true -> ClipShare.SENT
            else -> ClipShare.OFF_THERE
        }
        _status.update { it + (pairId to result) }
        outcomes.update { it + (pairId to (clip.seq to result)) }
        if (result == ClipShare.SENT) pairings.seen(pairId, null)
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        /** How long a copy waits for a device that isn't here yet. */
        const val FRESH_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 10_000L
        private const val MAX_SEEN = 512
    }
}
