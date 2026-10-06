package com.crossdrop.core.vault

import java.io.File
import java.io.InputStream
import java.text.Normalizer

/**
 * Sandboxed filesystem view for the LAN vault / "wireless drive". Every
 * inbound path — binary protocol, HTTP explorer, or WebDAV mount — passes
 * [sanitize] + backend resolution, which normalizes (Unicode NFC, forward
 * slashes), rejects traversal/hidden segments, and jails the result inside
 * the backend root. Anything outside is a `VaultError(403)`.
 *
 * Two backends exist: `FileVaultFs` (core, java.io.File — the built-in vault
 * directory) and `SafVaultFs` (app module, SAF DocumentFile — a folder the
 * user picked in the system picker).
 */
interface VaultFs {

    /** Wire-facing error: [code] maps to an HTTP status / VAULT_ERR code. */
    class VaultError(val code: Int, message: String) : Exception(message)

    data class Entry(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val mtime: Long,          // seconds since epoch
        val mime: String?,
    )

    /** Normalize + validate a client path; "" is the root. See [sanitizePath]. */
    fun sanitize(rawPath: String): String = sanitizePath(rawPath)

    /** List a directory — dirs first, then name; dotfiles never listable. */
    fun list(rawPath: String): List<Entry>

    /** Stat one path; 404 if missing or hidden. */
    fun stat(rawPath: String): Entry

    /** Free-space check before accepting an upload/PUT. */
    fun hasRoom(forBytes: Long): Boolean

    /**
     * Open [rawPath] (a file) positioned at [offset] — random access for
     * Range requests and pull streams. Caller closes the stream.
     */
    fun open(rawPath: String, offset: Long = 0): InputStream

    /** Fresh local staging file (always real storage — SAF stages in app cache). */
    fun stageFile(id: Long): File

    /**
     * Commit a staged file to [rawPath] inside the jail, creating parents.
     * Name collisions get "name (n).ext". Returns the final relative path.
     */
    fun commitStaged(stage: File, rawPath: String): String

    /** Wipe abandoned staging files. */
    fun purgeStaging()

    fun mimeOf(name: String): String = mimeTypeOf(name)

    // ------------------------------------------------------ write surface --
    // Used by WebDAV verbs; only reachable when the host runs read-write.

    /** Create a single directory; 409 if the parent doesn't exist, 405 if it does. */
    fun mkdir(rawPath: String): String

    /** Write [length] bytes from [input] to [rawPath], replacing any file there. */
    fun putStream(rawPath: String, input: InputStream, length: Long): String

    /** Delete a file or directory (recursive). 404 if missing. */
    fun delete(rawPath: String)

    /** Rename/move [src] to [dst]; [overwrite]=false → 412 when dst exists. */
    fun move(src: String, dst: String, overwrite: Boolean)

    /** Copy [src] to [dst] (recursive for dirs); same overwrite rule. */
    fun copy(src: String, dst: String, overwrite: Boolean)

    companion object {
        /**
         * Normalize + validate a relative path. Returns "" for the root.
         * Rejects: NULs, `..`, drive letters, hidden/dot segments.
         */
        fun sanitizePath(rawPath: String): String {
            if (rawPath.indexOf(0.toChar()) >= 0) throw VaultError(400, "null byte in path")
            var p = Normalizer.normalize(rawPath, Normalizer.Form.NFC)
                .replace('\\', '/')
            while (p.startsWith("/")) p = p.substring(1)
            if (p.matches(Regex("^[A-Za-z]:.*"))) throw VaultError(400, "absolute path")
            if (p == "." || p == "./") return ""
            val out = mutableListOf<String>()
            for (seg in p.split('/')) {
                when {
                    seg.isEmpty() || seg == "." -> {}
                    seg == ".." -> throw VaultError(403, "directory traversal")
                    seg.startsWith(".") -> throw VaultError(403, "hidden path")
                    else -> out += seg
                }
            }
            return out.joinToString("/")
        }

        fun mimeTypeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MIME[ext] ?: "application/octet-stream"
        }

        /** Keep at least 32 MB free — uploads stop before the disk is full. */
        const val MIN_FREE = 32L * 1024 * 1024

        val MIME = mapOf(
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
