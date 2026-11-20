package com.crossdrop.core.backup

import java.io.File

/**
 * The phone's record of what a paired computer holds: one append-only text file per pairing. A line is
 * written only after a batch finished with both sides agreeing on every file's BLAKE3 root, so an entry
 * means "the computer confirmed these exact bytes".
 *
 *     B<TAB>key<TAB>size<TAB>root<TAB>epoch-ms     backed up
 *     F<TAB>key<TAB>epoch-ms                       the phone's own copy was deleted to free up space
 *
 * Append-only so a crash mid-write loses at most the last line; [compact] rewrites it when it has grown.
 */
class BackupLedger(private val file: File) {

    data class Entry(val key: String, val size: Long, val root: String, val at: Long, val freedAt: Long = 0) {
        val freed: Boolean get() = freedAt > 0
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var lines = 0
    private var loaded = false

    @Synchronized
    fun get(key: String): Entry? { load(); return entries[key] }

    /** True when the computer confirmed an item with this [key] and [size] (an edited photo has a new size). */
    @Synchronized
    fun has(key: String, size: Long): Boolean { load(); return entries[key]?.size == size }

    @Synchronized
    fun all(): List<Entry> { load(); return entries.values.toList() }

    @Synchronized
    fun record(items: List<Entry>) {
        load()
        if (items.isEmpty()) return
        val text = buildString {
            for (e in items) {
                if (!clean(e.key) || !clean(e.root)) continue
                append("B\t").append(e.key).append('\t').append(e.size).append('\t').append(e.root).append('\t').append(e.at).append('\n')
                entries[e.key] = e.copy(freedAt = 0)
                lines++
            }
        }
        append(text)
        if (lines > 2 * entries.size + 1_000) compact()
    }

    @Synchronized
    fun markFreed(keys: Collection<String>, now: Long = System.currentTimeMillis()) {
        load()
        val text = buildString {
            for (k in keys) {
                val e = entries[k] ?: continue
                append("F\t").append(k).append('\t').append(now).append('\n')
                entries[k] = e.copy(freedAt = now)
                lines++
            }
        }
        append(text)
    }

    /** Forget everything (the pairing was removed). */
    @Synchronized
    fun clear() {
        entries.clear(); lines = 0; loaded = true
        file.delete()
    }

    @Synchronized
    fun compact() {
        load()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildString {
            for (e in entries.values) {
                append("B\t").append(e.key).append('\t').append(e.size).append('\t').append(e.root).append('\t').append(e.at).append('\n')
                if (e.freed) append("F\t").append(e.key).append('\t').append(e.freedAt).append('\n')
            }
        })
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        lines = entries.size + entries.values.count { it.freed }
    }

    private fun append(text: String) {
        if (text.isEmpty()) return
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val p = line.split('\t')
            when (p.getOrNull(0)) {
                "B" -> if (p.size >= 5) {
                    val size = p[2].toLongOrNull() ?: return@forEachLine
                    entries[p[1]] = Entry(p[1], size, p[3], p[4].toLongOrNull() ?: 0)
                }
                "F" -> if (p.size >= 3) entries[p[1]]?.let { entries[p[1]] = it.copy(freedAt = p[2].toLongOrNull() ?: 1) }
            }
            lines++
        }
    }

    private fun clean(s: String) = s.isNotEmpty() && s.none { it == '\t' || it == '\n' || it == '\r' }
}
