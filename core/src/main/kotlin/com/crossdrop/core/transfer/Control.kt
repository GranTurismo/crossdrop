package com.crossdrop.core.transfer

import com.crossdrop.core.crypto.SessionCrypto
import com.crossdrop.core.crypto.SessionKeys
import com.crossdrop.core.protocol.Announce
import com.crossdrop.core.protocol.FrameIO
import com.crossdrop.core.protocol.FrameType
import com.crossdrop.core.protocol.PairCheck
import com.crossdrop.core.protocol.PairReply
import com.crossdrop.core.protocol.PairRequest
import com.crossdrop.core.protocol.ProtocolException
import com.crossdrop.core.protocol.VerifyItem
import com.crossdrop.core.protocol.VerifyReply
import com.crossdrop.core.protocol.VerifyRequest
import com.crossdrop.core.protocol.decode
import com.crossdrop.core.protocol.encode
import java.io.EOFException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A pairing this device holds: its id and the 32-byte key both devices derived when they paired. */
class PairSecret(val id: String, val key: ByteArray)

/**
 * The receiving side of the exchanges that carry no files. The transfer listener hands every connection
 * whose first frame isn't an OFFER here. Every method has a harmless default, so a platform implements
 * only what it supports.
 */
interface ControlHandler {
    /** A device told us where to reach it: [host] is the address it connected from, [port] its listener. */
    fun announced(name: String, instanceId: String, host: String, port: Int) {}

    /** A device asks to pair. Suspend until the human answers; false (or never answering) declines. */
    suspend fun pairRequested(name: String, sas: String): Boolean = false

    /** The human agreed and both devices now hold [key] under [pairId]. */
    fun paired(pairId: String, name: String, key: ByteArray) {}

    /** The pairings tried against a PAIR_CHECK. */
    fun pairings(): List<PairSecret> = emptyList()

    /** A paired device asks which of [items] this device still holds intact (re-read, not remembered). */
    fun verify(pairId: String, items: List<VerifyItem>): List<String> = emptyList()
}

/**
 * The initiator's end of a connection that carries no files, opened by [TransferEngine.control]. The
 * handshake is done and every frame is sealed. One exchange per connection, except that a successful
 * [pairCheck] may be followed by any number of [verify] calls.
 */
class ControlChannel internal constructor(
    private val socket: Socket,
    private val io: FrameIO,
    private val keys: SessionKeys,
    /** The other device's name and per-launch id, from its HELLO_ACK. */
    val peerName: String,
    val peerInstanceId: String,
    /** The 6-digit code both screens show for this connection. */
    val sas: String,
) {
    private var matched: PairSecret? = null

    /** Tell the other device where to reach us. Returns once it answered. */
    fun announce(listenPort: Int) {
        io.write(FrameType.ANNOUNCE, Announce(listenPort).encode())
        val (type, _) = io.read()
        if (type != FrameType.ANNOUNCE) throw ProtocolException("expected ANNOUNCE, got $type")
    }

    /**
     * Ask the other device to pair under [pairId]. Its human sees [sas] and answers; this waits up to
     * [waitMs]. Returns the pairing key when they agreed, null when they declined.
     */
    fun pair(pairId: String, waitMs: Int = PAIR_WAIT_MS): ByteArray? {
        require(PAIR_ID.matches(pairId)) { "bad pair id" }
        io.write(FrameType.PAIR, PairRequest(pairId).encode())
        socket.soTimeout = waitMs
        val (type, payload) = io.read()
        if (type != FrameType.PAIR) throw ProtocolException("expected PAIR, got $type")
        if (!payload.decode<PairReply>().accepted) return null
        return SessionCrypto.pairKey(keys.shared, keys.binding, pairId)
    }

    /**
     * Which of [held] the other device holds too, proven both ways over a fresh nonce; null when none.
     * Strangers learn nothing they could link to this device later.
     */
    fun pairCheck(held: List<PairSecret>): PairSecret? {
        if (held.isEmpty()) return null
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val proofs = held.take(MAX_PROOFS).map { b64(SessionCrypto.pairProof(it.key, "check-i", keys.binding, nonce)) }
        io.write(FrameType.PAIR_CHECK, PairCheck(b64(nonce), proofs).encode())
        val (type, payload) = io.read()
        if (type != FrameType.PAIR_CHECK) throw ProtocolException("expected PAIR_CHECK, got $type")
        val answer = payload.decode<PairCheck>()
        if (answer.nonce != b64(nonce)) return null
        val theirs = answer.proofs.firstOrNull()?.let { runCatching { unb64(it) }.getOrNull() } ?: return null
        matched = held.firstOrNull {
            MessageDigest.isEqual(theirs, SessionCrypto.pairProof(it.key, "check-r", keys.binding, nonce))
        }
        return matched
    }

    /** After a successful [pairCheck]: the keys of [items] the other device re-read and found intact. */
    fun verify(items: List<VerifyItem>, waitMs: Int = VERIFY_WAIT_MS): Set<String> {
        check(matched != null) { "verify needs a successful pairCheck first" }
        val ok = HashSet<String>()
        socket.soTimeout = waitMs
        for (part in items.chunked(MAX_VERIFY_ITEMS)) {
            io.write(FrameType.BACKUP_VERIFY, VerifyRequest(part).encode())
            val (type, payload) = io.read()
            if (type != FrameType.BACKUP_VERIFY) throw ProtocolException("expected BACKUP_VERIFY, got $type")
            val asked = part.mapTo(HashSet()) { it.key }
            payload.decode<VerifyReply>().ok.filterTo(ok) { it in asked }
        }
        return ok
    }

    companion object {
        /** How long [pair] waits for the human on the other side. */
        const val PAIR_WAIT_MS = 75_000
        /** Re-hashing large videos takes a while on the other side. */
        const val VERIFY_WAIT_MS = 600_000
        const val MAX_PROOFS = 64
        const val MAX_VERIFY_ITEMS = 2_000
        val PAIR_ID = Regex("[0-9a-f]{32}")
    }
}

/**
 * The listener's side of one control exchange: [first] is the frame that came instead of an OFFER.
 * Returns when the exchange is over; the caller closes the socket.
 */
internal suspend fun serveControl(
    socket: Socket,
    io: FrameIO,
    keys: SessionKeys,
    peerName: String,
    peerInstanceId: String,
    first: Pair<FrameType, ByteArray>,
    handler: ControlHandler,
) {
    val (type, payload) = first
    when (type) {
        FrameType.ANNOUNCE -> {
            val port = payload.decode<Announce>().port
            if (port !in 1..65535) throw ProtocolException("bad announced port $port")
            val host = socket.inetAddress?.hostAddress?.substringBefore('%') ?: throw ProtocolException("no peer address")
            handler.announced(peerName, peerInstanceId, host, port)
            io.write(FrameType.ANNOUNCE, Announce(0).encode())
        }
        FrameType.PAIR -> {
            val pairId = payload.decode<PairRequest>().pairId
            if (!ControlChannel.PAIR_ID.matches(pairId)) throw ProtocolException("bad pair id")
            socket.soTimeout = ControlChannel.PAIR_WAIT_MS + 5_000
            val accepted = runCatching { handler.pairRequested(peerName, keys.sas) }.getOrDefault(false)
            io.write(FrameType.PAIR, PairReply(accepted).encode())
            if (accepted) handler.paired(pairId, peerName, SessionCrypto.pairKey(keys.shared, keys.binding, pairId))
        }
        FrameType.PAIR_CHECK -> {
            val check = payload.decode<PairCheck>()
            val nonce = runCatching { unb64(check.nonce) }.getOrNull()
                ?.takeIf { it.size in 16..64 } ?: throw ProtocolException("bad nonce")
            val proofs = check.proofs.take(ControlChannel.MAX_PROOFS).mapNotNull { runCatching { unb64(it) }.getOrNull() }
            val match = handler.pairings().firstOrNull { secret ->
                val want = SessionCrypto.pairProof(secret.key, "check-i", keys.binding, nonce)
                proofs.any { MessageDigest.isEqual(it, want) }
            }
            val answer = listOfNotNull(match?.let { b64(SessionCrypto.pairProof(it.key, "check-r", keys.binding, nonce)) })
            io.write(FrameType.PAIR_CHECK, PairCheck(check.nonce, answer).encode())
            if (match == null) return
            // A paired device may now ask about its backups, as long as it keeps the connection open.
            socket.soTimeout = ControlChannel.VERIFY_WAIT_MS
            while (true) {
                val (t, p) = try { io.read() } catch (_: EOFException) { return }
                if (t != FrameType.BACKUP_VERIFY) return
                val items = p.decode<VerifyRequest>().items.take(ControlChannel.MAX_VERIFY_ITEMS)
                val ok = runCatching { handler.verify(match.id, items) }.getOrDefault(emptyList())
                io.write(FrameType.BACKUP_VERIFY, VerifyReply(ok).encode())
            }
        }
        else -> throw ProtocolException("expected OFFER, got $type")
    }
}

private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
private fun unb64(s: String) = Base64.getDecoder().decode(s)
