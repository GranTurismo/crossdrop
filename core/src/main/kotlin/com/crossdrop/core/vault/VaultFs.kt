package com.crossdrop.core.vault

import java.io.File
import java.text.Normalizer

/**
 * Sandboxed filesystem view for the LAN vault. Every inbound path — from the
 * binary protocol or the HTTP surface — passes [resolve], which normalizes
 * (Unicode NFC, forward slashes), rejects traversal/hidden segments, then
 * verifies the *canonical* target stays strictly inside [root]. Anything
 * outside is a `VaultError(403)`; nothing else touches the host filesystem.
 */
class VaultFs(rootDir: File) {

    /** Canonical jail root — created on first use. */
    val root: File = rootDir.canonicalFile.apply { mkdirs() }

    /** Upload staging area — lives inside the jail but is never listable. */
    val stagingDir: File get() = File(root, ".staging").apply { mkdirs() }

    private val rootPrefix = root.path + File.separator

    /** Wire-facing error: [code] maps to an HTTP status / VAULT_ERR code. */
    class VaultError(val code: Int, message: String) : Exception(message)

    data class Entry(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val mtime: Long,
        val mime: String?,
    )

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

    /**
     * Normalize + validate a relative path. Returns "" for the root.
     * Rejects: NULs, `..`, drive letters, UNC, hidden/dot segments.
     */
    fun sanitize(rawPath: String): String {
        if (rawPath.indexOf('\u0000') >= 0) throw VaultError(400, "null byte in path")
        // NFC collapses look-alike sequences (e.g. '．．' / combined dots).
        var p = Normalizer.normalize(rawPath, Normalizer.Form.NFC)
            .replace('\\', '/')
        // strip leading slashes/drive prefix up front
        while (p.startsWith("/")) p = p.substring(1)
        if (p.matches(Regex("^[A-Za-z]:.*"))) throw VaultError(400, "absolute path")
        if (p == "." || p == "./") return ""
        val out = mutableListOf<String>()
        for (seg in p.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}           // collapse
                seg == ".." -> throw VaultError(403, "directory traversal")
                seg.startsWith(".") -> throw VaultError(403, "hidden path")
                else -> out += seg
            }
        }
        return out.joinToString("/")
    }

    /** Relative jail path of [f], or "" for the root itself. */
    fun relative(f: File): String =
        if (f == root) "" else f.canonicalFile.path.removePrefix(rootPrefix)
            .replace(File.separatorChar, '/')

    /** Entry for [f] as exposed to clients (hidden files never reach here). */
    fun entryOf(f: File): Entry = Entry(
        name = f.name,
        size = if (f.isFile) f.length() else 0,
        isDir = f.isDirectory,
        mtime = f.lastModified() / 1000,   // seconds since epoch on the wire
        mime = if (f.isFile) mimeOf(f.name) else null,
    )

    /** List a directory — dirs first, then name; dotfiles and .staging hidden. */
    fun list(rawPath: String): List<Entry> {
        val dir = resolve(rawPath)
        if (!dir.exists()) throw VaultError(404, "not found")
        if (!dir.isDirectory) throw VaultError(400, "not a directory")
        return (dir.listFiles() ?: emptyArray())
            .filter { !it.isHidden && !it.name.startsWith('.') }
            .map(::entryOf)
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Stat one path. */
    fun stat(rawPath: String): Entry {
        val f = resolve(rawPath)
        if (!f.exists() || f.name.startsWith('.')) throw VaultError(404, "not found")
        return entryOf(f)
    }

    /** Free space check before accepting an upload. */
    fun hasRoom(forBytes: Long): Boolean = root.usableSpace > forBytes + MIN_FREE

    /** Fresh staging file for transfer [id]. Parent dir is guaranteed inside. */
    fun stageFile(id: Long): File = File(stagingDir, "$id.part")

    /**
     * Move a finished stage file to [rawPath] inside the jail, creating parent
     * dirs (each re-jailed). Name collisions get "name (n).ext". Returns the
     * final relative path.
     */
    fun commitStaged(stage: File, rawPath: String): String {
        val clean = sanitize(rawPath)
        if (clean.isEmpty()) throw VaultError(400, "empty target")
        var target = resolve(clean)
        target.parentFile?.mkdirs()
        target.parentFile?.let { parent ->
            if (parent.canonicalFile != root && !parent.canonicalPath.startsWith(rootPrefix)) {
                throw VaultError(403, "target escapes vault root")
            }
        }
        if (target.exists()) target = dedupe(target)
        java.nio.file.Files.move(
            stage.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        return relative(target)
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

    /** Wipe every abandoned upload staging file. */
    fun purgeStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ mime table

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME[ext] ?: "application/octet-stream"
    }

    companion object {
        /** Keep at least 32 MB free — uploads stop before the disk is full. */
        private const val MIN_FREE = 32L * 1024 * 1024

        private val MIME = mapOf(
            // images
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "avif" to "image/avif",
            "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
            "svg" to "image/svg+xml", "ico" to "image/x-icon",
            // video
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
            "webm" to "video/webm", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
            "3gp" to "video/3gpp", "ts" to "video/mp2t",
            // audio
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
            "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/opus",
            "wav" to "audio/wav", "wma" to "audio/x-ms-wma",
            // docs
            "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown",
            "html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "js" to "text/javascript", "json" to "application/json",
            "csv" to "text/csv", "xml" to "text/xml",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "epub" to "application/epub+zip",
            // archives / packages
            "zip" to "application/zip", "tar" to "application/x-tar",
            "gz" to "application/gzip", "7z" to "application/x-7z-compressed",
            "rar" to "application/vnd.rar", "bz2" to "application/x-bzip2",
            "apk" to "application/vnd.android.package-archive",
            "iso" to "application/x-iso9660-image",
            // fonts
            "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff",
            "woff2" to "font/woff2",
        )
    }
}
