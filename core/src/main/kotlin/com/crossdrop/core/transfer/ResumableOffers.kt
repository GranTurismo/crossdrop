package com.crossdrop.core.transfer

import com.crossdrop.core.protocol.FileMeta
import java.security.MessageDigest
import java.util.Base64

/**
 * Receiver side of "auto-resume": offers of files the person (or a pairing) just accepted, remembered for
 * [ttlMs] so that the same sender, picking an interrupted transfer up again, isn't asked about it a second time.
 *
 * A repeat counts as the same transfer only if all three hold: it comes from the same two app launches
 * ([InboundRequest.sessionKey], which only the real sender can reproduce: it follows from the launches' keys),
 * it lists exactly the same files (names, folders, sizes, in order), and the first one has not completed.
 * Anything else, a different file, a new launch, a different device, is a new offer and is asked about as usual.
 * Memory only: restarting the app forgets everything.
 */
class ResumableOffers(
    private val ttlMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fingerprint: String, var expiresAt: Long, var sessionId: String?, var stopped: Boolean = false)

    private val entries = LinkedHashMap<String, Entry>()

    /** An offer was accepted ([sessionId] = the receive that is now running, so a retry can replace it). */
    @Synchronized
    fun remember(request: InboundRequest, sessionId: String?) {
        prune()
        entries[request.sessionKey] = Entry(fingerprint(request.offer.files), clock() + ttlMs, sessionId)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** True when [request] repeats the offer accepted earlier from the same launches; refreshes its time. */
    @Synchronized
    fun matches(request: InboundRequest): Boolean {
        prune()
        val e = entries[request.sessionKey] ?: return false
        if (e.stopped || e.fingerprint != fingerprint(request.offer.files)) return false
        e.expiresAt = clock() + ttlMs
        return true
    }

    /**
     * The person at this end cancelled the transfer. To a sender that is mid-stream that looks like a dropped
     * connection and it will try again; a repeat of these files is then no longer taken on trust (it is asked about,
     * and a "no" ends the retrying).
     */
    @Synchronized
    fun stop(request: InboundRequest) { entries[request.sessionKey]?.stopped = true }

    /** True when [request] repeats files whose transfer the person cancelled here, within the last [ttlMs]. */
    @Synchronized
    fun isStopped(request: InboundRequest): Boolean {
        prune()
        val e = entries[request.sessionKey] ?: return false
        return e.stopped && e.fingerprint == fingerprint(request.offer.files)
    }

    /** The receive that was running for [request]'s earlier offer (a dead connection may still be holding it), if any. */
    @Synchronized
    fun staleSession(request: InboundRequest): String? = entries[request.sessionKey]?.sessionId

    /** Point the remembered offer at the receive that replaced the stale one. */
    @Synchronized
    fun replaced(request: InboundRequest, sessionId: String) { entries[request.sessionKey]?.sessionId = sessionId }

    /** The transfer finished: a repeat after this is a new send, not a resume. */
    @Synchronized
    fun completed(request: InboundRequest) { entries.remove(request.sessionKey) }

    @Synchronized
    fun size(): Int { prune(); return entries.size }

    private fun prune() {
        val now = clock()
        entries.values.removeIf { it.expiresAt <= now }
    }

    companion object {
        private const val MAX_ENTRIES = 64

        /** Names, folders and sizes in order: what "the same files" means. */
        fun fingerprint(files: List<FileMeta>): String {
            val d = MessageDigest.getInstance("SHA-256")
            for (f in files) {
                d.update(f.name.toByteArray()); d.update(0); d.update((f.dir ?: "").toByteArray()); d.update(0)
                d.update(f.size.toString().toByteArray()); d.update(1)
            }
            return Base64.getEncoder().encodeToString(d.digest())
        }
    }
}
