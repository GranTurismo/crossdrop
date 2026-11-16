package com.crossdrop.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson = Json {
    ignoreUnknownKeys = true   // forward/backward version tolerance
    encodeDefaults = true
}

/** v2 adds content-defined-chunk delta/resume (CHUNK_MAP/CHUNK_REQ/CHUNK).
 *  v3 adds push mode (PUSH_CHUNK/PUSH_END) — no hashing before the first byte. */
const val PROTOCOL_VERSION = 3

@Serializable
data class Hello(
    /** Missing field ⇒ v1: peers older than the field can't claim v2. */
    val version: Int = 1,
    val deviceName: String,
    /** 32-byte X25519 ephemeral public key, base64. */
    val publicKeyB64: String,
    /** Rotating instance token, hex — same value the peer advertises over NSD/BLE. */
    val instanceId: String,
)

@Serializable
data class HelloAck(
    /** Missing field ⇒ v1: peers older than the field can't claim v2. */
    val version: Int = 1,
    val deviceName: String,
    val publicKeyB64: String,
    val instanceId: String,
    /** Vault listeners set "open"/"pin" so clients know whether to send
     *  VAULT_AUTH next. Null/absent on plain transfer endpoints. */
    val vaultAuth: String? = null,
)

@Serializable
data class FileMeta(
    val name: String,
    val size: Long,
    val mime: String? = null,
    /** What the item is in a "Switch phones" move ([ItemCategory]); null = an ordinary file. */
    val cat: String? = null,
    /** Original capture/modification time, epoch ms — kept when the receiver imports it into a gallery. */
    val mtime: Long? = null,
)

/** Values of [FileMeta.cat]. */
object ItemCategory {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val CONTACTS = "contacts"
}

/**
 * Values of [Offer.kind]. Receivers that predate a kind ignore it and treat the offer as plain
 * files, so every kind must also make sense as ordinary files.
 */
object OfferKind {
    /** Ordinary files (also what an absent kind means). */
    const val FILES = "files"
    /** A piece of text or a link; [Offer.text] carries it, the single file is a `.txt` copy. */
    const val TEXT = "text"
    /** Photos/videos taken in CrossDrop's camera. */
    const val CAMERA = "camera"
    /** One batch of a "Switch phones" move; see [Offer.batch]. */
    const val SWITCH = "switch"
}

/** Longest [Offer.text] a sender inlines (the file copy carries anything longer). */
const val MAX_INLINE_TEXT = 64 * 1024

@Serializable
data class Offer(
    val transferId: String,
    val files: List<FileMeta>,
    /** [OfferKind]; null = plain files (and every older sender). */
    val kind: String? = null,
    /** [OfferKind.TEXT]: the shared text itself, so the receiver can show it before accepting. */
    val text: String? = null,
    /** Set when the sender holds a [TrustGrant] from this receiver: the grant's id… */
    val trustId: String? = null,
    /** …and HMAC-SHA256(token, session binding ‖ transferId), base64 — proves the sender holds the
     *  token without revealing it, and can't be replayed on another connection. */
    val trustProof: String? = null,
    /** [OfferKind.SWITCH]: where this batch sits in the whole move. */
    val batch: BatchInfo? = null,
)

/** Totals of a whole "Switch phones" move, repeated on each batch's offer. */
@Serializable
data class BatchInfo(
    val index: Int,
    val count: Int,
    val photos: Int = 0,
    val videos: Int = 0,
    val contacts: Int = 0,
    val files: Int = 0,
    val totalBytes: Long = 0,
)

/**
 * Receiver → sender, inside an encrypted ACCEPT: "offers that prove they hold [token] are accepted
 * without asking me, for this [kind], until I stop it or [ttlMs] passes". Lives only in memory on
 * both sides.
 */
@Serializable
data class TrustGrant(
    val id: String,
    /** 32 random bytes, base64. */
    val token: String,
    val kind: String,
    val ttlMs: Long,
)

@Serializable
data class Accept(
    val transferId: String,
    /** Receiver opts into the v2 chunked delta path (it has a DeltaReceiver). */
    val delta: Boolean = false,
    /** v3: receiver holds nothing reusable for this offer, so the sender
     *  streams chunks immediately instead of sending a manifest first. */
    val push: Boolean = false,
    /** Receiver lets later offers of the same kind skip its accept prompt (see [TrustGrant]). */
    val trust: TrustGrant? = null,
)

@Serializable
data class Decline(val transferId: String, val reason: String? = null)

@Serializable
data class Done(
    val transferId: String,
    val sha256: String,
    /** v2: per-file BLAKE3 roots, parallel to Offer.files. Null on v1 sessions. */
    val roots: List<String>? = null,
)

@Serializable
data class Cancel(val reason: String? = null)

@Serializable
data class Error(val message: String)

inline fun <reified T> T.encode(): ByteArray =
    protocolJson.encodeToString(this).toByteArray(Charsets.UTF_8)

inline fun <reified T> ByteArray.decode(): T =
    protocolJson.decodeFromString(this.toString(Charsets.UTF_8))
