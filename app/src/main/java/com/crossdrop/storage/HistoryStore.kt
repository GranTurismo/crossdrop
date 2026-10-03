package com.crossdrop.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted log of finished transfers — direction, peer, size, outcome.
 * Stored as a JSON array in prefs (no DB dependency, capped at [MAX_ENTRIES]).
 * History is local-only, like everything else in this app.
 */
class HistoryStore(context: Context) {

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

    private val prefs = context.getSharedPreferences("crossdrop", Context.MODE_PRIVATE)

    fun list(): List<Entry> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                ts = o.getLong("ts"),
                direction = o.getString("direction"),
                peerName = o.getString("peerName"),
                summary = o.getString("summary"),
                files = o.getInt("files"),
                bytes = o.getLong("bytes"),
                status = o.getString("status"),
            )
        }
    }.getOrDefault(emptyList())

    fun add(entry: Entry) {
        val next = (listOf(entry) + list()).take(MAX_ENTRIES)
        save(next)
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("ts", e.ts)
                put("direction", e.direction)
                put("peerName", e.peerName)
                put("summary", e.summary)
                put("files", e.files)
                put("bytes", e.bytes)
                put("status", e.status)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "transfer_history"
        const val MAX_ENTRIES = 100
    }
}
