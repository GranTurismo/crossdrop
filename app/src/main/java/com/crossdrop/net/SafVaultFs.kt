package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.core.vault.VaultFs.VaultError
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * SAF backend for the LAN drive: a user-picked `DocumentFile` tree shared
 * over the same jail contract as [FileVaultFs]. Path resolution walks the
 * tree segment-by-segment (no filesystem paths ever reach the wire), and the
 * same sanitize rules (NFC, no `..`, no dot-segments) apply first.
 *
 * Reads use `ParcelFileDescriptor` → positioned `FileInputStream` for true
 * random access (Range/206). Uploads stage to app cache, then stream into
 * the tree — SAF has no atomic move, so a torn PUT may leave a partial file.
 */
class SafVaultFs(
    private val context: Context,
    treeUri: Uri,
    private val stagingRoot: File,
) : VaultFs {

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw VaultError(500, "cannot open picked folder")

    private val stagingDir: File get() = File(stagingRoot, "saf-stage").apply { mkdirs() }

    /** Display name of the picked folder, for the host screen. */
    val rootName: String get() = root.name ?: "Shared folder"

    // --------------------------------------------------------- resolution --

    /** Walk the tree for [rawPath]; null when any segment is missing. */
    private fun doc(rawPath: String): DocumentFile? {
        var d: DocumentFile? = root
        for (seg in sanitize(rawPath).split('/').filter { it.isNotEmpty() }) {
            d = d?.findFile(seg) ?: return null
        }
        return d
    }

    private fun must(rawPath: String): DocumentFile =
        doc(rawPath) ?: throw VaultError(404, "not found")

    /** Parent dir doc + leaf name for create-style ops. */
    private fun parentOf(rawPath: String): Pair<DocumentFile, String> {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        val leaf = clean.substringAfterLast('/')
        val parentPath = clean.substringBeforeLast('/', "")
        return (doc(parentPath) ?: throw VaultError(409, "parent missing")) to leaf
    }

    private fun entryOf(d: DocumentFile): VaultFs.Entry = VaultFs.Entry(
        name = d.name ?: "?",
        size = if (d.isFile) d.length().coerceAtLeast(0) else 0,
        isDir = d.isDirectory,
        mtime = d.lastModified() / 1000,
        mime = if (d.isFile) (d.type ?: mimeOf(d.name ?: "")) else null,
    )

    // ------------------------------------------------------------ reads --

    override fun list(rawPath: String): List<VaultFs.Entry> {
        val d = must(rawPath)
        if (!d.isDirectory) throw VaultError(400, "not a directory")
        return d.listFiles()
            .filter { it.name != null && !it.name!!.startsWith(".") && it.exists() }
            .map(::entryOf)
            .sortedWith(compareByDescending<VaultFs.Entry> { it.isDir }
                .thenBy { it.name.lowercase() })
    }

    override fun stat(rawPath: String): VaultFs.Entry {
        val d = must(rawPath)
        if (d.name?.startsWith(".") == true) throw VaultError(404, "not found")
        return entryOf(d)
    }

    /** Staging lives on internal storage — that is the space that matters. */
    override fun hasRoom(forBytes: Long): Boolean =
        StatFs(context.cacheDir.path).availableBytes > forBytes + VaultFs.MIN_FREE

    override fun open(rawPath: String, offset: Long): InputStream {
        val d = must(rawPath)
        if (!d.isFile) throw VaultError(404, "not found")
        val pfd = context.contentResolver.openFileDescriptor(d.uri, "r")
            ?: throw VaultError(500, "cannot open file")
        val fis = FileInputStream(pfd.fileDescriptor)
        fis.channel.position(offset)
        // closing the stream must also drop the ParcelFileDescriptor
        return object : java.io.FilterInputStream(fis) {
            override fun close() {
                runCatching { super.close() }
                runCatching { pfd.close() }
            }
        }
    }

    // ----------------------------------------------------------- staging --

    override fun stageFile(id: Long): File = File(stagingDir, "$id.part")

    override fun commitStaged(stage: File, rawPath: String): String {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        // walk/create parent dirs like FileVaultFs.commitStaged does
        val dirPath = clean.substringBeforeLast('/', "")
        val leaf = clean.substringAfterLast('/')
        var parent = root
        if (dirPath.isNotEmpty()) {
            for (seg in dirPath.split('/')) {
                val child = parent.findFile(seg)
                parent = when {
                    child == null -> parent.createDirectory(seg)
                        ?: throw VaultError(500, "cannot create folder")
                    child.isDirectory -> child
                    else -> throw VaultError(409, "name in use")
                }
            }
        }
        var target = parent.findFile(leaf)
        if (target != null) {
            // dedupe: leaf → "leaf (n).ext"
            val base = leaf.substringBeforeLast('.', leaf)
            val ext = leaf.substringAfterLast('.', "").takeIf { it != leaf }
            var n = 2
            while (target != null) {
                val cand = if (ext != null) "$base ($n).$ext" else "$base ($n)"
                target = parent.findFile(cand)
                if (target == null) {
                    target = parent.createFile(mimeOf(cand), cand)
                        ?: throw VaultError(500, "cannot create file")
                    break
                }
                n++
            }
        } else {
            target = parent.createFile(mimeOf(leaf), leaf)
                ?: throw VaultError(500, "cannot create file")
        }
        stage.inputStream().use { src ->
            (context.contentResolver.openOutputStream(target.uri, "wt")
                ?: throw VaultError(500, "cannot write file")).use { out ->
                src.copyTo(out)
            }
        }
        stage.delete()
        val finalName = target.name ?: leaf
        return if (dirPath.isEmpty()) finalName else "$dirPath/$finalName"
    }

    override fun purgeStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------ write surface --

    override fun mkdir(rawPath: String): String {
        val (parent, leaf) = parentOf(rawPath)
        if (parent.findFile(leaf) != null) throw VaultError(405, "already exists")
        parent.createDirectory(leaf) ?: throw VaultError(500, "mkdir failed")
        return sanitize(rawPath)
    }

    override fun putStream(rawPath: String, input: InputStream, length: Long): String {
        val (parent, leaf) = parentOf(rawPath)
        if (!hasRoom(length)) throw VaultError(413, "insufficient storage")
        val existing = parent.findFile(leaf)
        if (existing?.isDirectory == true) throw VaultError(409, "target is a directory")
        // Stage in app cache first: SAF "wt" truncates the target up front, so a
        // torn PUT must never touch the document — commit replaces it whole.
        val stage = File(stagingDir, "put-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(stage).use { out ->
                val buf = ByteArray(64 * 1024); var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
                if (left != 0L) throw VaultError(400, "body truncated")
            }
            val target = existing ?: parent.createFile(mimeOf(leaf), leaf)
                ?: throw VaultError(500, "cannot create file")
            stage.inputStream().use { src ->
                (context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: throw VaultError(500, "cannot write file")).use { out ->
                    src.copyTo(out)
                }
            }
        } finally {
            stage.delete()
        }
        return sanitize(rawPath)
    }

    override fun delete(rawPath: String) {
        val d = must(rawPath)
        if (d.uri == root.uri) throw VaultError(403, "cannot delete drive root")
        deleteTree(d)
    }

    private fun deleteTree(d: DocumentFile) {
        if (d.isDirectory) d.listFiles().forEach(::deleteTree)
        if (!d.delete()) throw VaultError(500, "delete failed")
    }

    override fun move(src: String, dst: String, overwrite: Boolean) {
        val srcClean = sanitize(src)
        val dstClean = sanitize(dst)
        if (dstClean == srcClean || dstClean.startsWith("$srcClean/"))
            throw VaultError(409, "cannot move a folder into itself")
        // overwrite deletes the destination first — never the root or an ancestor of the source
        if (dstClean.isEmpty()) throw VaultError(403, "cannot replace drive root")
        if (srcClean.startsWith("$dstClean/")) throw VaultError(409, "destination contains the source")
        val s = must(src)
        if (s.uri == root.uri) throw VaultError(403, "cannot move drive root")
        val (dstParent, dstLeaf) = parentOf(dst)
        dstParent.findFile(dstLeaf)?.let { existing ->
            if (!overwrite) throw VaultError(412, "destination exists")
            deleteTree(existing)
        }
        // cheap path: same-parent rename; cross-parent → copy + delete
        val sParent = parentOf(src).first
        if (sParent.uri == dstParent.uri && s.renameTo(dstLeaf)) return
        copyTree(s, dstParent, dstLeaf)
        deleteTree(s)
    }

    override fun copy(src: String, dst: String, overwrite: Boolean) {
        val srcClean = sanitize(src)
        val dstClean = sanitize(dst)
        if (dstClean == srcClean || dstClean.startsWith("$srcClean/"))
            throw VaultError(409, "cannot copy a folder into itself")
        if (dstClean.isEmpty()) throw VaultError(403, "cannot replace drive root")
        if (srcClean.startsWith("$dstClean/")) throw VaultError(409, "destination contains the source")
        val s = must(src)
        val (dstParent, dstLeaf) = parentOf(dst)
        dstParent.findFile(dstLeaf)?.let { existing ->
            if (!overwrite) throw VaultError(412, "destination exists")
            deleteTree(existing)
        }
        copyTree(s, dstParent, dstLeaf)
    }

    private fun copyTree(src: DocumentFile, parent: DocumentFile, name: String) {
        if (src.isDirectory) {
            val dir = parent.createDirectory(name) ?: throw VaultError(500, "cannot create folder")
            src.listFiles().forEach { c -> c.name?.let { copyTree(c, dir, it) } }
        } else {
            val dst = parent.createFile(mimeOf(name), name)
                ?: throw VaultError(500, "cannot create file")
            context.contentResolver.openInputStream(src.uri)!!.use { `in` ->
                context.contentResolver.openOutputStream(dst.uri, "wt")!!.use { out ->
                    `in`.copyTo(out)
                }
            }
        }
    }
}
