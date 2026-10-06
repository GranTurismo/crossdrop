package com.crossdrop.core.vault

import com.crossdrop.core.vault.VaultFs.VaultError
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The built-in vault backend: a java.io.File tree under `filesDir/vault`,
 * jailed by canonical-path comparison. Upload staging is a hidden
 * `.staging` dir inside the jail.
 */
class FileVaultFs(rootDir: File) : VaultFs {

    /** Canonical jail root — created on first use. */
    val root: File = rootDir.canonicalFile.apply { mkdirs() }

    /** Upload staging area — lives inside the jail but is never listable. */
    val stagingDir: File get() = File(root, ".staging").apply { mkdirs() }

    private val rootPrefix = root.path + File.separator

    /**
     * Resolve a client-supplied relative path to a jailed [File].
     * `""`, `"/"`, `"."` all resolve to the vault root.
     */
    fun resolve(rawPath: String): File {
        val clean = sanitize(rawPath)
        val target = if (clean.isEmpty()) root else File(root, clean).canonicalFile
        if (target != root && !target.path.startsWith(rootPrefix)) {
            throw VaultError(403, "path escapes vault root")
        }
        return target
    }

    /** Relative jail path of [f], or "" for the root itself. */
    fun relative(f: File): String =
        if (f == root) "" else f.canonicalFile.path.removePrefix(rootPrefix)
            .replace(File.separatorChar, '/')

    /** Entry for [f] as exposed to clients (hidden files never reach here). */
    fun entryOf(f: File): VaultFs.Entry = VaultFs.Entry(
        name = f.name,
        size = if (f.isFile) f.length() else 0,
        isDir = f.isDirectory,
        mtime = f.lastModified() / 1000,   // seconds since epoch on the wire
        mime = if (f.isFile) mimeOf(f.name) else null,
    )

    override fun list(rawPath: String): List<VaultFs.Entry> {
        val dir = resolve(rawPath)
        if (!dir.exists()) throw VaultError(404, "not found")
        if (!dir.isDirectory) throw VaultError(400, "not a directory")
        return (dir.listFiles() ?: emptyArray())
            .filter { !it.isHidden && !it.name.startsWith('.') }
            .map(::entryOf)
            .sortedWith(compareByDescending<VaultFs.Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(rawPath: String): VaultFs.Entry {
        val f = resolve(rawPath)
        if (!f.exists() || f.name.startsWith('.')) throw VaultError(404, "not found")
        return entryOf(f)
    }

    override fun hasRoom(forBytes: Long): Boolean =
        root.usableSpace > forBytes + VaultFs.MIN_FREE

    override fun open(rawPath: String, offset: Long): InputStream {
        val f = resolve(rawPath)
        if (!f.isFile) throw VaultError(404, "not found")
        val raf = RandomAccessFile(f, "r")
        raf.seek(offset)
        return Channels.newInputStream(raf.channel)
    }

    override fun stageFile(id: Long): File = File(stagingDir, "$id.part")

    override fun commitStaged(stage: File, rawPath: String): String {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        var target = resolve(clean)
        target.parentFile?.mkdirs()                       // commitStaged creates parents
        jailCheckParent(target)
        if (target.exists()) target = dedupe(target)
        Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return relative(target)
    }

    override fun purgeStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------ write surface --

    override fun mkdir(rawPath: String): String {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(405, "cannot create root")
        val target = resolve(clean)
        if (target.exists()) throw VaultError(405, "already exists")
        jailCheckParent(target)
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) throw VaultError(409, "parent missing")
        if (!target.mkdir()) throw VaultError(500, "mkdir failed")
        return relative(target)
    }

    override fun putStream(rawPath: String, input: InputStream, length: Long): String {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        val target = resolve(clean)
        if (target.isDirectory) throw VaultError(409, "target is a directory")
        jailCheckParent(target)
        val pdir = target.parentFile
        if (pdir == null || !pdir.isDirectory) throw VaultError(409, "parent missing")
        if (!hasRoom(length)) throw VaultError(413, "insufficient storage")
        val tmp = File(stagingDir, "put-${System.nanoTime()}.tmp")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
                if (left != 0L) throw VaultError(400, "body truncated")
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        return relative(target)
    }

    override fun delete(rawPath: String) {
        val f = resolve(rawPath)
        if (f == root) throw VaultError(403, "cannot delete vault root")
        if (!f.exists()) throw VaultError(404, "not found")
        if (!f.deleteRecursively()) throw VaultError(500, "delete failed")
    }

    override fun move(src: String, dst: String, overwrite: Boolean) {
        val s = resolve(src)
        if (s == root) throw VaultError(403, "cannot move vault root")
        if (!s.exists()) throw VaultError(404, "not found")
        val d = resolve(dst)
        if (d.exists()) {
            if (!overwrite) throw VaultError(412, "destination exists")
            if (!d.deleteRecursively()) throw VaultError(500, "cannot replace destination")
        }
        jailCheckParent(d)
        val parent = d.parentFile
        if (parent == null || !parent.isDirectory) throw VaultError(409, "parent missing")
        if (!s.renameTo(d)) {
            // cross-filesystem or lock issues — fall back to copy+delete
            if (s.isDirectory) s.copyRecursively(d, true) else s.copyTo(d, true)
            if (!s.deleteRecursively()) throw VaultError(500, "move cleanup failed")
        }
    }

    override fun copy(src: String, dst: String, overwrite: Boolean) {
        val s = resolve(src)
        if (!s.exists()) throw VaultError(404, "not found")
        val d = resolve(dst)
        if (d.exists()) {
            if (!overwrite) throw VaultError(412, "destination exists")
            if (!d.deleteRecursively()) throw VaultError(500, "cannot replace destination")
        }
        jailCheckParent(d)
        val parent = d.parentFile
        if (parent == null || !parent.isDirectory) throw VaultError(409, "parent missing")
        if (s.isDirectory) s.copyRecursively(d, true) else s.copyTo(d, true)
    }

    /** Parent must stay inside the jail too (symlinked parents). */
    private fun jailCheckParent(target: File) {
        target.parentFile?.let { parent ->
            if (parent.canonicalFile != root && !parent.canonicalPath.startsWith(rootPrefix)) {
                throw VaultError(403, "target escapes vault root")
            }
        }
    }

    /** "a.jpg" → "a (2).jpg", "dir" → "dir (2)". */
    private fun dedupe(f: File): File {
        val base = f.name.substringBeforeLast('.', f.name)
        val ext = f.name.substringAfterLast('.', "").takeIf { it != f.name }
        var n = 2
        while (true) {
            val cand = File(f.parentFile, if (ext != null) "$base ($n).$ext" else "$base ($n)")
            if (!cand.exists()) return cand
            n++
        }
    }
}
