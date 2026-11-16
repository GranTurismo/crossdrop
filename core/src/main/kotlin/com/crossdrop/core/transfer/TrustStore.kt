package com.crossdrop.core.transfer

import com.crossdrop.core.protocol.TrustGrant
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory-only "accept once, then keep receiving" state, both directions.
 *
 * Receiver side: [grant] mints a [TrustGrant] to send back inside an ACCEPT; [match] finds the grant
 * a later offer proves it holds. A grant is good for its kind only, and lapses after [TrustGrant.ttlMs]
 * without use or when [revoke]d. Nothing is persisted: restarting either app forgets everything, so a
 * device's rotating identity never turns into a long-lived pairing.
 *
 * Sender side: [remember] keeps grants received from peers, keyed by the peer's instance id (which
 * also rotates per launch), and [credentialFor] hands the matching one to the next offer.
 */
class TrustStore(private val clock: () -> Long = System::currentTimeMillis) {

    class Granted(
        val grant: TrustGrant,
        val token: ByteArray,
        val peerName: String,
        @Volatile var lastUsedMs: Long,
    ) {
        /** Transfer ids already let through: a recorded session replayed between the same two app
         *  launches carries a valid proof, so each id is good once. */
        internal val seen: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
        val id: String get() = grant.id
        val kind: String get() = grant.kind
    }

    private val granted = ConcurrentHashMap<String, Granted>()
    private val held = ConcurrentHashMap<String, Pair<TrustGrant, Long>>()   // "peer|kind" -> grant, last use
    private val random = SecureRandom()

    // ------------------------------------------------------------ receiver --

    fun grant(kind: String, peerName: String, ttlMs: Long): TrustGrant {
        val token = ByteArray(32).also(random::nextBytes)
        val id = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val g = TrustGrant(id, Base64.getEncoder().encodeToString(token), kind, ttlMs)
        granted[id] = Granted(g, token, peerName, clock())
        return g
    }

    /** The live grant this request proves it holds, for the offer's kind; null when none. */
    fun match(request: InboundRequest): Granted? {
        val id = request.offer.trustId ?: return null
        val g = granted[id] ?: return null
        val now = clock()
        if (now - g.lastUsedMs > g.grant.ttlMs) { granted.remove(id); return null }
        if (g.kind != (request.offer.kind ?: return null)) return null
        if (!request.provesTrust(g.token)) return null
        if (!g.seen.add(request.offer.transferId)) return null
        g.lastUsedMs = now
        return g
    }

    fun revoke(id: String) { granted.remove(id) }

    fun revokeKind(kind: String) { granted.values.removeIf { it.kind == kind } }

    fun active(kind: String): List<Granted> {
        val now = clock()
        return granted.values.filter { it.kind == kind && now - it.lastUsedMs <= it.grant.ttlMs }
    }

    // -------------------------------------------------------------- sender --

    fun remember(peerKey: String, grant: TrustGrant) {
        held["$peerKey|${grant.kind}"] = grant to clock()
    }

    fun credentialFor(peerKey: String, kind: String): TrustCredential? {
        val key = "$peerKey|$kind"
        val (grant, last) = held[key] ?: return null
        val now = clock()
        if (now - last > grant.ttlMs) { held.remove(key); return null }
        held[key] = grant to now
        return TrustCredential.of(grant)
    }

    fun forget(peerKey: String, kind: String) { held.remove("$peerKey|$kind") }
}
