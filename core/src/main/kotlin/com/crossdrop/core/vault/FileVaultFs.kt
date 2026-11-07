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
 * `.staging` dir inside the jail unless a separate staging root is given.
 */
class FileVaultFs(
    rootDir: File,
    private val stagingRoot: File? = null,
    /**
     * Also enforce Windows' naming rules (see [VaultFs.isWindowsUnsafeSegment]) and
     * defeat 8.3 short-name aliases of hidden entries. On by default exactly when
     * the host path separator is `\`; a parameter so the rules are testable anywhere.
     */
    private val windowsNames: Boolean = File.separatorChar == '\\',
) : VaultFs {

    /** Canonical jail root — created on first use. */
    val root: File = rootDir.canonicalFile.apply { mkdirs() }

    /**
     * Upload staging area. By default a hidden dir inside the jail (never
     * listable); hosts that share a user's own folder pass [stagingRoot] so
     * nothing but the user's files ever appears in it.
     */
    val stagingDir: File get() = (stagingRoot ?: File(root, ".staging")).apply { mkdirs() }

    // A filesystem/drive root ("/" or "C:\") already ends in the separator.
    private val rootPrefix = if (root.path.endsWith(File.separator)) root.path else root.path + File.separator

    override fun sanitize(rawPath: String): String {
        val clean = VaultFs.sanitizePath(rawPath)
        if (windowsNames) {
            clean.split('/').firstOrNull { VaultFs.isWindowsUnsafeSegment(it) }?.let {
                throw VaultError(400, "\"$it\" is not a valid file name on a Windows host")
            }
        }
        return clean
    }

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
        // Win32 canonicalization expands 8.3 short names, so `STAGIN~1` reaches the
        // hidden `.staging` dir that the dot-segment rule on the raw path never saw.
        // Re-check hidden-ness on what the path actually resolved to.
        if (windowsNames && target != root &&
            target.path.removePrefix(rootPrefix).split(File.separatorChar).any { it.startsWith(".") }
        ) {
            throw VaultError(403, "hidden path")
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
        if (d == s) {
            if (renameCaseOnly(s, src, dst)) return
            throw VaultError(400, "same source and destination")
        }
        if (d.path.startsWith(s.path + File.separator))
            throw VaultError(409, "cannot move a folder into itself")
        // overwrite=true would delete the destination first — never the root or an ancestor of the source.
        if (d == root) throw VaultError(403, "cannot replace vault root")
        if (s.path.startsWith(d.path + File.separator))
            throw VaultError(409, "destination contains the source")
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
        if (d == s) throw VaultError(400, "same source and destination")
        if (d.path.startsWith(s.path + File.separator))
            throw VaultError(409, "cannot copy a folder into itself")
        if (d == root) throw VaultError(403, "cannot replace vault root")
        if (s.path.startsWith(d.path + File.separator))
            throw VaultError(409, "destination contains the source")
        if (d.exists()) {
            if (!overwrite) throw VaultError(412, "destination exists")
            if (!d.deleteRecursively()) throw VaultError(500, "cannot replace destination")
        }
        jailCheckParent(d)
        val parent = d.parentFile
        if (parent == null || !parent.isDirectory) throw VaultError(409, "parent missing")
        if (s.isDirectory) s.copyRecursively(d, true) else s.copyTo(d, true)
    }

    /**
     * On a case-insensitive filesystem (NTFS, default APFS) canonicalization maps `photo.jpg` and
     * `Photo.jpg` to one File, so a case-only rename looks like "same source and destination" even
     * though it is a legitimate rename. Renaming a file onto itself is a no-op there, so go through
     * a temporary sibling name. Returns false when [src]→[dst] is not a case-only rename.
     */
    private fun renameCaseOnly(s: File, src: String, dst: String): Boolean {
        val from = sanitize(src)
        val to = sanitize(dst)
        val newName = to.substringAfterLast('/')
        if (from == to || !from.equals(to, ignoreCase = true) || newName == s.name) return false
        val parent = s.parentFile ?: return false
        val tmp = File(parent, ".rename-" + java.lang.Long.toHexString(System.nanoTime()))
        if (!s.renameTo(tmp)) throw VaultError(500, "rename failed")
        if (!tmp.renameTo(File(parent, newName))) {
            tmp.renameTo(s)                      // put it back; never leave the file under a temp name
            throw VaultError(500, "rename failed")
        }
        return true
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
