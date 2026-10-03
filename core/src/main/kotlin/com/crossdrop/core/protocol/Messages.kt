package com.crossdrop.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson = Json {
    ignoreUnknownKeys = true   // forward/backward version tolerance
    encodeDefaults = true
}

const val PROTOCOL_VERSION = 1

@Serializable
data class Hello(
    val version: Int = PROTOCOL_VERSION,
    val deviceName: String,
    /** 32-byte X25519 ephemeral public key, base64. */
    val publicKeyB64: String,
    /** Rotating instance token, hex — same value the peer advertises over NSD/BLE. */
    val instanceId: String,
)

@Serializable
data class HelloAck(
    val version: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val publicKeyB64: String,
    val instanceId: String,
)

@Serializable
data class FileMeta(val name: String, val size: Long, val mime: String? = null)

@Serializable
data class Offer(val transferId: String, val files: List<FileMeta>)

@Serializable
data class Accept(val transferId: String)

@Serializable
data class Decline(val transferId: String, val reason: String? = null)

@Serializable
data class Done(val transferId: String, val sha256: String)

@Serializable
data class Cancel(val reason: String? = null)

@Serializable
data class Error(val message: String)

inline fun <reified T> T.encode(): ByteArray =
    protocolJson.encodeToString(this).toByteArray(Charsets.UTF_8)

inline fun <reified T> ByteArray.decode(): T =
    protocolJson.decodeFromString(this.toString(Charsets.UTF_8))
