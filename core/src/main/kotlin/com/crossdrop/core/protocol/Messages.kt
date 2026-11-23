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
    /** What the device runs on ([com.crossdrop.core.Platforms]); absent on older builds. */
    val os: String? = null,
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
    /** What the device runs on ([com.crossdrop.core.Platforms]); absent on older builds. */
    val os: String? = null,
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
    /** [OfferKind.BACKUP]: the sender's stable id for this item, so the receiver can recognise it later. */
    val key: String? = null,
    /** [OfferKind.APP]: the Android package this file belongs to (an app may come as several split APKs). */
    val pkg: String? = null,
    /**
     * A folder being sent: the folder this file sits in, `/`-separated, starting with the folder that was
     * picked ("Holiday/Day 1"); null for a loose file. Older receivers ignore it and get a flat file.
     * Peer-supplied, so receivers clean every segment ([com.crossdrop.core.protocol.Folders.relativePath]).
     */
    val dir: String? = null,
)

/** Values of [FileMeta.cat]. */
object ItemCategory {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val CONTACTS = "contacts"
    /** One APK of an installed app ([FileMeta.pkg] groups the pieces). */
    const val APP = "app"
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
    /** One or more contact cards: a single `.vcf` file; [Offer.text] carries a photo-less copy for the prompt. */
    const val CONTACT = "contact"
    /** One batch of a phone backup to a paired computer; carries [Offer.pairProof]. Never prompts. */
    const val BACKUP = "backup"
    /** Installed Android apps: each app's APKs (base + splits), grouped by [FileMeta.pkg]. Android to Android only. */
    const val APP = "app"
}

/** Longest [Offer.text] a sender inlines (the file copy carries anything longer). */
const val MAX_INLINE_TEXT = 64 * 1024

@Serializable
data class Offer(
    val transferId: String,
    val files: List<FileMeta>,
    /** [OfferKind]; null = plain files (and every older sender). */
    val kind: String? = null,
    /** [OfferKind.TEXT]: the shared text itself, so the receiver can show it before accepting.
     *  [OfferKind.CONTACT]: the cards as vCard without photos, for the receiver's preview. */
    val text: String? = null,
    /** Set when the sender holds a [TrustGrant] from this receiver: the grant's id… */
    val trustId: String? = null,
    /** …and HMAC-SHA256(token, session binding ‖ transferId), base64 — proves the sender holds the
     *  token without revealing it. The binding comes from the two devices' current keys (new on
     *  every app launch), and a receiver accepts each transferId only once per grant. */
    val trustProof: String? = null,
    /** [OfferKind.SWITCH]: where this batch sits in the whole move. */
    val batch: BatchInfo? = null,
    /** Set when the sender and receiver are paired ("My devices"): HMAC(pairing key, "offer" ‖ binding ‖
     *  transferId), base64. The receiver finds which pairing it belongs to without any id on the wire. */
    val pairProof: String? = null,
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
    /** Answer to [Offer.pairProof]: HMAC(pairing key, "accept" ‖ binding ‖ transferId). The sender checks it
     *  before sending a byte, so a device that only borrowed the paired device's name gets nothing. */
    val pairProof: String? = null,
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

// ------------------------------------------------- control exchanges (no files) --

/**
 * ANNOUNCE: "I'm here; reach me on [port]". Lets a device that joined another's private link (or any
 * network where multicast is blocked) appear on that device's radar. The answer's [port] is 0: "the port
 * you connected to".
 */
@Serializable
data class Announce(val port: Int)

/**
 * SHAKE: "this phone was shaken just now" (shake to connect). Carries no time: the receiver notes when it
 * arrived on its own clock, so the two phones' clocks never need to agree. [port] is the sender's listener,
 * so a phone that wasn't on the receiver's radar yet can be reached back. The answer's [port] is 0.
 */
@Serializable
data class Shake(val port: Int = 0)

/** PAIR, first frame: the initiator asks to become one of the receiver's devices under [pairId] (32 hex). */
@Serializable
data class PairRequest(
    val pairId: String,
    /** What the pairing is for ([PairPurpose]); absent on older builds, which only ever paired for backups. */
    val purpose: String? = null,
)

/** Values of [PairRequest.purpose]. */
object PairPurpose {
    /** "My devices": these two send each other files without asking. */
    const val DEVICES = "devices"
}

/** PAIR, answer: the human on the receiving side agreed (or not, or didn't answer in time). */
@Serializable
data class PairReply(val accepted: Boolean)

/**
 * PAIR_CHECK, both directions. Request: a fresh [nonce] and one proof per pairing the initiator holds,
 * HMAC(key, "check-i" ‖ binding ‖ nonce). Answer: the same nonce and, if one of them is a pairing this
 * device holds too, that pairing's HMAC(key, "check-r" ‖ binding ‖ nonce); otherwise no proofs. No pairing
 * id ever crosses the network, and a recorded answer is useless against a new nonce.
 */
@Serializable
data class PairCheck(val nonce: String, val proofs: List<String> = emptyList())

/** One backed-up item as the phone remembers it: its key, whole-file BLAKE3 root and size. */
@Serializable
data class VerifyItem(val key: String, val root: String, val size: Long)

/** BACKUP_VERIFY (only after a successful PAIR_CHECK on the same connection): "do you still hold these?" */
@Serializable
data class VerifyRequest(val items: List<VerifyItem>)

/** The keys of the items the receiver re-read and found intact. */
@Serializable
data class VerifyReply(val ok: List<String>)

/**
 * CLIPBOARD (only after a successful PAIR_CHECK on the same connection): "this is what I just copied". [id] is fresh
 * for every copy: the same two app launches derive the same session keys, so a recorded connection could otherwise be
 * played again to put an old copy back on the clipboard.
 */
@Serializable
data class ClipboardPush(val id: String, val text: String)

/** The answer: [accepted] is false when clipboard sharing with the sender is off on this side (or the copy was refused). */
@Serializable
data class ClipboardReply(val accepted: Boolean)

/** The largest copy shared, in UTF-8 bytes. Android's clipboard goes through a 1 MB binder buffer, shared with everything else. */
const val MAX_CLIPBOARD_BYTES = 256 * 1024

@Serializable
data class Error(val message: String)

inline fun <reified T> T.encode(): ByteArray =
    protocolJson.encodeToString(this).toByteArray(Charsets.UTF_8)

inline fun <reified T> ByteArray.decode(): T =
    protocolJson.decodeFromString(this.toString(Charsets.UTF_8))
