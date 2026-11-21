package com.crossdrop.net

import com.crossdrop.core.Peer
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

// Shared verbatim with :desktop (copied at build time, like TransferHub) — no android.* here.

/**
 * Which of "My devices" are on the radar right now. A device's name and address are not proof of who it is (its
 * id changes on every launch and anyone can use any name), so every device that appears on the radar is asked
 * once, over a short connection, whether it holds one of our pairings ([com.crossdrop.core.transfer.ControlChannel.pairCheck]:
 * proven both ways, nothing linkable to strangers). The answer is kept for as long as that radar entry lives.
 *
 * Costs nothing while this device holds no pairing. Used by "send when it's back" and by auto-resume, which both
 * must find a particular paired device again after it went away.
 */
class PairedPresence(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val pairings: PairingStore,
    radar: StateFlow<Map<String, Peer>>,
    private val checkTimeoutMs: Long = 3_000,
    /** How long before a check that got no answer (not even a "no") is tried again. */
    private val retryMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _present = MutableStateFlow<Map<String, Peer>>(emptyMap())

    /** Pairing id → the radar entry that proved it holds that pairing. */
    val present: StateFlow<Map<String, Peer>> = _present

    private val lock = Any()
    private class Verdict(val pairId: String?, val endpoint: com.crossdrop.core.Endpoint?)   // pairId null = answered "not one of mine"
    private val verdict = HashMap<String, Verdict>()     // radar id → what that device proved, and where from
    private val failedAt = HashMap<String, Long>()       // radar id → when a check got no answer
    private val inFlight = HashSet<String>()
    private var lastRadar: Map<String, Peer> = emptyMap()
    private var lastPairIds: Set<String> = emptySet()
    private val gate = Semaphore(4)

    init {
        scope.launch {
            combine(radar, pairings.devices) { r, d -> r to d.map { it.id }.toSet() }.collect { (r, ids) -> evaluate(r, ids) }
        }
        // A check that got no answer is tried again; nothing else needs a timer.
        scope.launch {
            while (true) {
                delay(retryMs)
                val (r, ids) = synchronized(lock) { lastRadar to lastPairIds }
                evaluate(r, ids)
            }
        }
    }

    private fun evaluate(radar: Map<String, Peer>, pairIds: Set<String>) {
        val toCheck = ArrayList<Peer>()
        synchronized(lock) {
            // Pairings changed: a device that said "not mine" may be mine now, and one that proved a pairing that is gone
            // (unpaired, then paired again under a new id) must prove itself again.
            if (pairIds != lastPairIds) verdict.values.removeAll { it.pairId == null || it.pairId !in pairIds }
            lastRadar = radar; lastPairIds = pairIds
            val candidates = radar.values.filter { it.endpoint != null && it.vault == null }
            val here = candidates.mapTo(HashSet()) { it.instanceId }
            verdict.keys.retainAll(here); failedAt.keys.retainAll(here)
            // A proof was given from one address; the same radar id showing up somewhere else is not covered by it
            // (the id is public: anyone can advertise it).
            for (p in candidates) if (verdict[p.instanceId]?.let { it.endpoint != p.endpoint } == true) verdict.remove(p.instanceId)
            publish(candidates, pairIds)
            if (pairIds.isEmpty()) return
            val now = clock()
            for (p in candidates) {
                if (p.instanceId in verdict || p.instanceId in inFlight) continue
                if (now - (failedAt[p.instanceId] ?: 0L) < retryMs) continue
                inFlight += p.instanceId
                toCheck += p
            }
        }
        for (p in toCheck) scope.launch(Dispatchers.IO) { check(p) }
    }

    private suspend fun check(p: Peer) {
        val secrets = pairings.secrets()
        val endpoint = p.endpoint
        var answer: Result<com.crossdrop.core.transfer.PairSecret?>? = null
        if (endpoint != null && secrets.isNotEmpty()) {
            answer = gate.withPermit {
                withTimeoutOrNull(checkTimeoutMs) {
                    runCatching { engine.control(endpoint, connectTimeoutMs = 2_000, readTimeoutMs = checkTimeoutMs.toInt()) { pairCheck(secrets) } }
                }
            }
        }
        val found = answer?.getOrNull()
        synchronized(lock) {
            inFlight -= p.instanceId
            if (answer != null && answer.isSuccess) verdict[p.instanceId] = Verdict(found?.id, endpoint) else failedAt[p.instanceId] = clock()
            publish(lastRadar.values.filter { it.endpoint != null && it.vault == null }, lastPairIds)
        }
        if (found != null) pairings.seen(found.id, null)
    }

    /** Called with [lock] held. */
    private fun publish(candidates: List<Peer>, pairIds: Set<String>) {
        val next = HashMap<String, Peer>()
        for (p in candidates) verdict[p.instanceId]?.takeIf { it.endpoint == p.endpoint }?.pairId?.takeIf { it in pairIds }?.let { next[it] = p }
        // A radar entry's signal strength and last-seen time change all the time; only who is here (and where) matters.
        fun key(m: Map<String, Peer>) = m.mapValues { it.value.instanceId to it.value.endpoint }
        if (key(next) != key(_present.value)) _present.value = next
    }
}
