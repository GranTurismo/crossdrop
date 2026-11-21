package com.crossdrop.net

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.TooManyFilesException
import com.crossdrop.core.transfer.FailureKind
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.SendOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

// Shared verbatim with :desktop (copied at build time, like TransferHub) — no android.* here.

/**
 * One thing waiting to go. What [ref] means is up to the platform that queued it ([kind]): a file or folder path
 * (desktop), a document or folder URI with a persisted read grant (Android), or a private copy of a file
 * whose grant could not be kept.
 */
@Serializable
data class OutboxItem(val kind: String, val ref: String, val label: String)

/** What is waiting for one of "My devices": picked while it was away, sent when it is back. */
@Serializable
data class OutboxEntry(
    val id: String,
    /** The pairing it goes to; only a device that proves this pairing ever receives it. */
    val pairId: String,
    val deviceName: String,
    val items: List<OutboxItem>,
    val createdAt: Long,
    /** Tries that failed for a reason other than the device being away (it said no, the files were gone…). */
    val attempts: Int = 0,
)

/**
 * "Send when it's back": files and folders picked for one of "My devices" that is not here right now. A small
 * JSON file in app-private storage; nothing is copied unless the platform has to (see [OutboxItem]). Entries
 * go when delivered, after [MAX_ATTEMPTS] real failures, after [MAX_AGE_MS], or when the pairing is removed.
 */
class Outbox(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** An entry left (sent, expired, cleared): the platform deletes whatever it copied for it. */
    private val onRemoved: (OutboxEntry) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<OutboxEntry>> = _entries

    /** Queue [items] for a device. More than [MAX_ITEMS] become several entries, so nothing picked is ever left out. Returns the first. */
    @Synchronized
    fun add(pairId: String, deviceName: String, items: List<OutboxItem>): OutboxEntry? {
        if (items.isEmpty()) return null
        return items.chunked(MAX_ITEMS).map { addOne(pairId, deviceName, it) }.first()
    }

    private fun addOne(pairId: String, deviceName: String, items: List<OutboxItem>): OutboxEntry {
        val entry = OutboxEntry(
            ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
            pairId, deviceName.take(64), items, clock(),
        )
        // A full queue never refuses what the person just picked: the oldest entry makes room, preferring one for the
        // same device, so a busy device can't push another device's queue out.
        var all = _entries.value + entry
        val dropped = ArrayList<OutboxEntry>()
        while (all.size > MAX_ENTRIES) {
            val victim = all.firstOrNull { it.pairId == pairId && it.id != entry.id } ?: all.first()
            dropped += victim; all = all - victim
        }
        set(all)
        dropped.forEach(onRemoved)        // after the queue no longer lists them: the platform checks what is still referenced
        return entry
    }

    @Synchronized
    fun remove(id: String) {
        val gone = _entries.value.firstOrNull { it.id == id } ?: return
        set(_entries.value.filterNot { it.id == id })
        onRemoved(gone)
    }

    @Synchronized
    fun clearFor(pairId: String) {
        val gone = _entries.value.filter { it.pairId == pairId }
        if (gone.isEmpty()) return
        set(_entries.value.filterNot { it.pairId == pairId })
        gone.forEach(onRemoved)
    }

    /** A try failed for real. Returns true when that was the last one and the entry was dropped. */
    @Synchronized
    fun failed(id: String): Boolean {
        val cur = _entries.value.firstOrNull { it.id == id } ?: return false
        if (cur.attempts + 1 >= MAX_ATTEMPTS) { remove(id); return true }
        set(_entries.value.map { if (it.id == id) it.copy(attempts = it.attempts + 1) else it })
        return false
    }

    /** Drop what is too old, and what belongs to a pairing that no longer exists. */
    @Synchronized
    fun prune(pairIds: Set<String>) {
        val now = clock()
        val gone = _entries.value.filter { now - it.createdAt > MAX_AGE_MS || it.pairId !in pairIds }
        if (gone.isEmpty()) return
        set(_entries.value - gone.toSet())
        gone.forEach(onRemoved)
    }

    fun countFor(pairId: String): Int = _entries.value.filter { it.pairId == pairId }.sumOf { it.items.size }

    private fun set(next: List<OutboxEntry>) { _entries.value = next; save() }

    private fun load(): List<OutboxEntry> = runCatching {
        if (file.isFile) json.decodeFromString(ListSerializer(OutboxEntry.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(OutboxEntry.serializer()), _entries.value))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_ITEMS = 100
        const val MAX_ATTEMPTS = 3
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** How a queued send ended, for the platform to tell the person. */
data class OutboxResult(val entry: OutboxEntry, val delivered: Boolean, val reason: String? = null)

/**
 * Sends [Outbox] entries as their device shows up. The device must prove the pairing ([PairedPresence], then
 * again by the engine before a byte is sent: [SendOptions.pairKey]), so nothing queued for "Mum's laptop" can
 * ever reach whoever borrowed the name. One try per entry each time the device appears: a "no" or a failure
 * waits for its next visit instead of nagging it.
 */
class OutboxRunner(
    private val scope: CoroutineScope,
    private val hub: TransferHub,
    private val presence: PairedPresence,
    private val outbox: Outbox,
    private val pairings: PairingStore,
    /** The platform turns an entry into streamable files (empty = nothing readable now). May throw [TooManyFilesException]. */
    private val resolve: suspend (OutboxEntry) -> List<OutgoingFile>,
    /** Keep the app alive while sending (Android's foreground service); failures to do so are ignored. */
    private val keepAlive: () -> Unit = {},
    private val onResult: (OutboxResult) -> Unit = {},
) {
    private val _sending = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the entries on their way right now. */
    val sending: StateFlow<Set<String>> = _sending

    private val draining = HashSet<String>()                 // pairing ids being worked on
    private val tried = HashMap<String, String>()            // entry id → radar id it was last tried against
    private val awayRetries = HashMap<String, Int>()         // entry id → tries this visit that failed only because the device dropped out

    init {
        scope.launch {
            combine(presence.present, outbox.entries, pairings.devices) { p, e, d -> Triple(p, e, d.map { it.id }.toSet()) }
                .collect { (present, entries, pairIds) -> drain(present, entries, pairIds) }
        }
    }

    private fun drain(present: Map<String, Peer>, entries: List<OutboxEntry>, pairIds: Set<String>) {
        outbox.prune(pairIds)
        synchronized(tried) {
            // A device that went away starts over: the next time it is here, everything for it gets one more try.
            val away = entries.filter { it.pairId !in present }.map { it.id }.toSet()
            tried.keys.removeAll { it in away || entries.none { e -> e.id == it } }
            awayRetries.keys.removeAll { it in away || entries.none { e -> e.id == it } }
        }
        for ((pairId, peer) in present) {
            if (entries.none { it.pairId == pairId }) continue
            if (!synchronized(draining) { draining.add(pairId) }) continue
            scope.launch(Dispatchers.IO) {
                try { drainDevice(pairId, peer) } finally { synchronized(draining) { draining.remove(pairId) } }
                // Something queued in the instant between the last look and now would otherwise wait for the next radar change.
                if (presence.present.value.containsKey(pairId) && outbox.entries.value.any { it.pairId == pairId }) {
                    drain(presence.present.value, outbox.entries.value, pairings.devices.value.map { it.id }.toSet())
                }
            }
        }
    }

    private suspend fun drainDevice(pairId: String, first: Peer) {
        val secret = pairings.get(pairId)?.secret() ?: return
        var peer = first
        while (true) {
            peer = presence.present.value[pairId] ?: return                       // it left again: stays queued
            val entry = outbox.entries.value.firstOrNull {
                it.pairId == pairId && synchronized(tried) { tried[it.id] != peer.instanceId }
            } ?: return
            synchronized(tried) { tried[entry.id] = peer.instanceId }
            _sending.update { it + entry.id }
            val dropped = try { sendEntry(entry, peer, secret.key) } finally { _sending.update { it - entry.id } }
            // The device is on the radar but the transfer was cut off (a blip shorter than the radar's own check): a few
            // more tries during this visit, with a pause, rather than waiting for it to leave and come back.
            if (dropped && synchronized(tried) { awayRetries.merge(entry.id, 1, Int::plus)!! } <= AWAY_RETRIES) {
                delay(AWAY_RETRY_MS)
                synchronized(tried) { tried.remove(entry.id) }
            }
        }
    }

    /** Sends one entry. Returns true when the device dropped out mid-way (worth another try soon), false otherwise. */
    private suspend fun sendEntry(entry: OutboxEntry, peer: Peer, key: ByteArray): Boolean {
        val files = try {
            resolve(entry)
        } catch (e: TooManyFilesException) {
            outbox.remove(entry.id); onResult(OutboxResult(entry, false, "too many files")); return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Something went wrong reading (storage hiccup, a grant not ready): that is a failed try, not proof there is nothing to send.
            if (outbox.failed(entry.id)) onResult(OutboxResult(entry, false, "couldn't read the files"))
            return false
        }
        if (files.isEmpty()) { outbox.remove(entry.id); onResult(OutboxResult(entry, false, "nothing readable")); return false }
        runCatching { keepAlive() }
        val result = hub.sendNow(peer, files, SendOptions(pairKey = key), quiet = true)
        when (result.status) {
            SessionStatus.COMPLETED -> { outbox.remove(entry.id); onResult(OutboxResult(entry, true)) }
            SessionStatus.CANCELLED -> Unit
            else -> {
                // The device going away again is not the entry's fault; a "no" or a real failure is counted.
                val away = result.failure == FailureKind.UNREACHABLE || result.failure == FailureKind.CONNECTION_LOST
                if (!away && outbox.failed(entry.id)) onResult(OutboxResult(entry, false, result.error ?: result.status.name))
                return away
            }
        }
        return false
    }

    private companion object {
        const val AWAY_RETRIES = 3
        const val AWAY_RETRY_MS = 5_000L
    }
}
