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
data class FileMeta(val name: String, val size: Long, val mime: String? = null)

@Serializable
data class Offer(val transferId: String, val files: List<FileMeta>)

@Serializable
data class Accept(
    val transferId: String,
    /** Receiver opts into the v2 chunked delta path (it has a DeltaReceiver). */
    val delta: Boolean = false,
    /** v3: receiver holds nothing reusable for this offer, so the sender
     *  streams chunks immediately instead of sending a manifest first. */
    val push: Boolean = false,
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
