package com.crossdrop.core.vault

import com.crossdrop.core.protocol.protocolJson
import kotlinx.serialization.Serializable

/**
 * JSON payloads for the vault frames (frame IDs 0x10–0x1A in FrameType).
 * Everything is `ignoreUnknownKeys` tolerant via [protocolJson].
 */
@Serializable
data class VaultLs(val path: String = "/")

@Serializable
data class VaultDirEntry(
    val name: String,
    val size: Long = 0,
    val isDir: Boolean = false,
    val mtime: Long = 0,
    val mime: String? = null,
)

/** Directory batch; a listing may span several frames ([eof] marks the last). */
@Serializable
data class VaultDir(val path: String = "", val entries: List<VaultDirEntry>, val eof: Boolean = true)

@Serializable
data class VaultStat(val path: String)

@Serializable
data class VaultAttr(
    val name: String,
    val size: Long = 0,
    val isDir: Boolean = false,
    val mtime: Long = 0,
    val mime: String? = null,
    /** BLAKE3 hex for files ≤ HASH_MAX bytes; null for dirs/huge files. */
    val hash: String? = null,
)

/** Pull `length` bytes starting at `offset` (-1 = to EOF). */
@Serializable
data class VaultPull(val path: String, val offset: Long = 0, val length: Long = -1,
                     val transferId: Long = 0)

@Serializable
data class VaultPushInit(val path: String, val size: Long, val mime: String? = null)

/** Reused for both PUSH init replies and PIN auth replies. */
@Serializable
data class VaultAck(val status: String, val transferId: Long = 0, val reason: String? = null)

@Serializable
data class VaultAuth(val token: String)   // base64 HKDF token

@Serializable
data class VaultDone(val transferId: Long, val hash: String,
                     val path: String? = null)

@Serializable
data class VaultErr(val code: Int, val message: String = "")

internal inline fun <reified T> encodeMsg(v: T): ByteArray =
    protocolJson.encodeToString(v).toByteArray(Charsets.UTF_8)

internal inline fun <reified T> decodeMsg(b: ByteArray): T =
    protocolJson.decodeFromString(b.toString(Charsets.UTF_8))
