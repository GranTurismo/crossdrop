package com.crossdrop.core.delta

import com.crossdrop.core.protocol.FileMeta
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Filesystem [DeltaReceiver]: resumability + delta baselines on plain files.
 *
 * Layout under [inboxDir]:
 *   `<file>`            assembled received files
 *
 * and under [stateDir] (the inbox itself unless told otherwise):
 *   `.staging/<b3>.bin` received-but-not-yet-assembled chunks, hash-named —
 *                       survives process death, so a re-offer resumes
 *   `.idx/<file>.idx`   persisted chunk indexes so re-shares of an already-
 *                       received file delta without re-chunking on this side
 *
 * An inbox the user can browse (Downloads) passes its own [stateDir]: dot
 * folders are only hidden on Unix, and bookkeeping doesn't belong beside
 * someone's files anyway.
 *
 * [nameFor] lets the caller inject its own sanitize/dedupe naming; the
 * index is keyed off the resolved filename so a file received under any
 * deduped name still serves as a baseline for its own meta.
 */
class FileDeltaStore(
    private val inboxDir: File,
    private val stateDir: File = inboxDir,
    // Default strips path separators — raw meta.name would escape inboxDir.
    private val nameFor: (FileMeta) -> String = {
        it.name.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "file" }
    },
) : DeltaReceiver {

    private val pool get() = File(stateDir, ".staging")
    private val idxDir get() = File(stateDir, ".idx")

    override fun stagingPool(): File = pool.apply { mkdirs() }

    // The directory may not exist yet (a fresh store on first use, or the sub-folder of a received folder):
    // the .part beside the target needs it. [nameFor] may return a `/`-separated path for a file inside a folder.
    override fun targetFor(meta: FileMeta): File =
        File(inboxDir.apply { mkdirs() }, nameFor(meta)).also { it.parentFile?.mkdirs() }

    override fun baselineFor(meta: FileMeta): Baseline? {
        val file = File(inboxDir, nameFor(meta))
        if (!file.isFile) return null
        val idx = indexFileFor(file)
        val indexed = readIndex(idx, file) ?: run {
            val built = chunkFile(file)
            writeIndex(idx, file, built.chunks, built.root)
            built
        }
        return Baseline(file, indexed.chunks, indexed.root)
    }

    // Looks only: creating the folders of an offer that may still be cancelled (or hostile) is not a side effect of asking.
    override fun canPush(files: List<FileMeta>): Boolean =
        (pool.listFiles() ?: emptyArray()).none { it.name.endsWith(".bin") } &&
            files.none { File(inboxDir, nameFor(it)).exists() }

    override fun publishIndex(file: File, chunks: List<ChunkEntry>, root: String) {
        // The index is a cache for the next transfer: failing to write it must never fail the one that just finished.
        runCatching { writeIndex(indexFileFor(file), file, chunks, root) }
    }

    override fun invalidate(meta: FileMeta) {
        indexFileFor(File(inboxDir, nameFor(meta))).delete()
    }

    /** Clear pooled chunks (e.g. on inbox wipe). */
    fun clearPool() {
        pool.listFiles()?.forEach { it.delete() }
    }

    /** Bytes currently held in the staging pool (diagnostics). */
    fun poolBytes(): Long = pool.listFiles()?.sumOf { it.length() } ?: 0

    // ------------------------------------------------------------ internals --

    /**
     * The index of [file]. Keyed by its path inside the inbox, so two files with the same name in different
     * folders don't share one (a flat file keeps its plain `<name>.idx`). `$` stands for `/`: a received
     * name can't contain it, so no file name can collide with a folder path.
     */
    private fun indexFileFor(file: File): File {
        val rel = runCatching { file.relativeTo(inboxDir).invariantSeparatorsPath }.getOrNull()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("..") } ?: file.name
        val key = rel.replace('/', '$')
        // A deep path would make a file name past what file systems allow (255 bytes): long ones are named by their hash.
        val name = if (key.toByteArray().size <= MAX_INDEX_NAME) key
            else "h-" + java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(idxDir, "$name.idx")
    }

    /**
     * True when this store assembled [file] itself and it is still exactly that: its index exists and matches the file's
     * size and time. A file that is merely sitting where we would write (the person's own, one they edited since, an old
     * index left behind by a deleted file) is not ours and must never be replaced.
     */
    fun knows(file: File): Boolean = file.isFile && readIndex(indexFileFor(file), file) != null

    /** Drop the index of [file] (it was deleted, moved or published elsewhere). */
    fun forget(file: File) { indexFileFor(file).delete() }

    class Indexed(val chunks: List<ChunkEntry>, val root: String)

    /** FastCDC-split [file] into BLAKE3-keyed entries + whole-file root —
     *  one streaming pass feeding both hashers. */
    fun chunkFile(file: File): Indexed {
        val out = ArrayList<ChunkEntry>()
        val hasher = Blake3()
        file.inputStream().buffered().use { input ->
            FastCdc.chunks(input).forEach { (bytes, _) ->
                out += ChunkEntry(bytes.size, Blake3.hash(bytes))
                hasher.update(bytes)
            }
        }
        return Indexed(out, hasher.hexDigest())
    }

    /**
     * .idx format: `CDX3` | 32B file-root | u64 fileLen | u64 mtime |
     * u32 count | (u32 len | 32B hash)* — validated against the file's current
     * size/mtime, so editing the file just invalidates the cache.
     */
    private fun readIndex(idx: File, file: File): Indexed? {
        if (!idx.isFile) return null
        return runCatching {
            DataInputStream(idx.inputStream().buffered()).use { dis ->
                if (dis.readInt() != IDX_MAGIC) return null
                val root = ByteArray(32).also { dis.readFully(it) }
                if (dis.readLong() != file.length() || dis.readLong() != file.lastModified()) return null
                val count = dis.readInt()
                // Every non-last chunk is ≥ MIN — a bogus count can't OOM us.
                if (count < 0 || count > file.length() / FastCdc.MIN + 1) return null
                val chunks = List(count) {
                    val len = dis.readInt()
                    val hash = ByteArray(32).also { dis.readFully(it) }
                    ChunkEntry(len, hash)
                }
                Indexed(chunks, root.joinToString("") { "%02x".format(it) })
            }
        }.getOrNull()?.takeIf { it.chunks.sumOf { e -> e.len.toLong() } == file.length() }
    }

    private fun writeIndex(idx: File, file: File, chunks: List<ChunkEntry>, root: String) {
        idxDir.mkdirs()
        val tmp = File(idxDir, "${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { dos ->
            dos.writeInt(IDX_MAGIC)
            dos.write(root.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            dos.writeLong(file.length()); dos.writeLong(file.lastModified())
            dos.writeInt(chunks.size)
            for (c in chunks) { dos.writeInt(c.len); dos.write(c.hash) }
        }
        // renameTo fails on Windows when the destination exists.
        java.nio.file.Files.move(tmp.toPath(), idx.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val IDX_MAGIC = 0x43445833    // "CDX3"
        /** Longest index name kept as the path itself; leaves room for ".idx" under the 255-byte file-name limit. */
        const val MAX_INDEX_NAME = 200
    }
}
