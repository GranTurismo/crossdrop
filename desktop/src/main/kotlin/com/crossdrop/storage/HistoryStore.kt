package com.crossdrop.storage

import com.crossdrop.platform.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted log of finished transfers — direction, peer, size, outcome.
 * A capped JSON file in Application Support; local-only, like everything else.
 */
class HistoryStore(private val file: File = File(AppDirs.support, "history.json")) {

    @Serializable
    data class Entry(
        val ts: Long,
        val direction: String,   // SENDING | RECEIVING
        val peerName: String,
        val summary: String,     // first file name, or "N files"
        val files: Int,
        val bytes: Long,
        val status: String,      // COMPLETED | FAILED | DECLINED | CANCELLED
    ) {
        val isSend: Boolean get() = direction == "SENDING"
        val ok: Boolean get() = status == "COMPLETED"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    @Synchronized fun list(): List<Entry> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    @Synchronized fun add(entry: Entry) {
        save((listOf(entry) + list()).take(MAX_ENTRIES))
    }

    @Synchronized fun clear() { file.delete() }

    private fun save(entries: List<Entry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, entries))
        tmp.renameTo(file)
    }

    private companion object { const val MAX_ENTRIES = 100 }
}
