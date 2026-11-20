package com.crossdrop.core.pairing

import com.crossdrop.core.transfer.PairSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** One of "My devices": a device this one paired with. [key] is the shared 32-byte secret, base64. */
@Serializable
data class PairedDevice(
    val id: String,
    /** The name the device had when it was last seen. */
    val name: String,
    val key: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
) {
    fun secret(): PairSecret = PairSecret(id, Base64.getDecoder().decode(key))
}

/**
 * "My devices": the pairings this device holds, kept in one small JSON file in app-private storage (never
 * in cloud backups). A pairing is the only long-lived link CrossDrop keeps between devices; it is created
 * only when both humans agree (and compare the 6-digit code) and removed by Unpair on either side. Nothing
 * about it is advertised: devices recognise each other with [com.crossdrop.core.transfer.ControlChannel.pairCheck].
 */
class PairingStore(private val file: File) {
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<PairedDevice>> = _devices

    fun secrets(): List<PairSecret> = _devices.value.mapNotNull { runCatching { it.secret() }.getOrNull() }

    fun get(id: String): PairedDevice? = _devices.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(id: String, name: String, key: ByteArray, now: Long = System.currentTimeMillis()): PairedDevice {
        val d = PairedDevice(id, name.trim().take(64), Base64.getEncoder().encodeToString(key), now, now)
        _devices.value = _devices.value.filterNot { it.id == id } + d
        save()
        return d
    }

    /** Remembers the device's current name and when it was last recognised. */
    @Synchronized
    fun seen(id: String, name: String?, now: Long = System.currentTimeMillis()) {
        val cur = get(id) ?: return
        val next = cur.copy(name = name?.trim()?.take(64)?.ifBlank { null } ?: cur.name, lastSeen = now)
        if (next == cur) return
        _devices.value = _devices.value.map { if (it.id == id) next else it }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        if (get(id) == null) return
        _devices.value = _devices.value.filterNot { it.id == id }
        save()
    }

    private fun load(): List<PairedDevice> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString(ListSerializer(PairedDevice.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(PairedDevice.serializer()), _devices.value))
        ownerOnly(tmp)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun ownerOnly(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** A fresh pairing id: 16 random bytes, hex. */
        fun newId(): String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
