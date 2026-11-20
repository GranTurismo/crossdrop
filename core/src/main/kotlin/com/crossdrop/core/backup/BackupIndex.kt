package com.crossdrop.core.backup

import com.crossdrop.core.delta.Blake3
import com.crossdrop.core.protocol.VerifyItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The computer's record of one paired phone's backup: the phone's item key → where the file went (relative
 * to [root]), its size and BLAKE3 root. Used to skip what is already there and to answer the phone's
 * "do you still hold these?" before it deletes anything ([verify] re-reads every file it vouches for).
 */
class BackupIndex(private val file: File, val root: File) {

    @Serializable
    data class Item(val path: String, val size: Long, val root: String, val at: Long)

    private var items: MutableMap<String, Item>? = null

    @Synchronized
    fun get(key: String): Item? = map()[key]

    /** Any item already holding exactly this content (same root and size), e.g. sent again after a reinstall. */
    @Synchronized
    fun findContent(root: String, size: Long): Item? = map().values.firstOrNull { it.root == root && it.size == size }

    @Synchronized
    fun put(entries: Map<String, Item>) {
        if (entries.isEmpty()) return
        map().putAll(entries)
        save()
    }

    @Synchronized
    fun size(): Int = map().size

    /**
     * The keys among [asked] whose file is still where the index says, with the same size and the same
     * BLAKE3 root, re-hashed now. Anything moved, edited or deleted on the computer is left out, so the
     * phone keeps its own copy of it.
     */
    fun verify(asked: List<VerifyItem>, hash: (File) -> String = ::blake3Hex): List<String> {
        val snapshot = synchronized(this) { asked.mapNotNull { a -> map()[a.key]?.let { a to it } } }
        return snapshot.filter { (a, item) ->
            if (item.root != a.root || item.size != a.size) return@filter false
            val f = resolve(item.path) ?: return@filter false
            f.isFile && f.length() == item.size && runCatching { hash(f) }.getOrNull() == item.root
        }.map { it.first.key }
    }

    /** The file an index path points to, refusing anything that would leave [root]. */
    fun resolve(path: String): File? {
        val f = File(root, path)
        val base = root.canonicalFile
        val c = runCatching { f.canonicalFile }.getOrNull() ?: return null
        return c.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun map(): MutableMap<String, Item> {
        items?.let { return it }
        val loaded = runCatching {
            if (file.isFile) json.decodeFromString(serializer, file.readText()).toMutableMap() else null
        }.getOrNull() ?: HashMap()
        items = loaded
        return loaded
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, map()))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Item.serializer())

        fun blake3Hex(f: File): String = f.inputStream().buffered(256 * 1024).use { input ->
            val h = Blake3()
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                h.update(buf, 0, n)
            }
            h.hexDigest()
        }
    }
}
