package com.crossdrop.storage

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.platform.AppDirs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Staging area for received files, kept inside Application Support rather than
 * Downloads (storage-isolation pillar). Files stay here until the user opens,
 * reveals or deletes them; wiping is a zero-fill pass plus unlink.
 */
class InboxStore(private val root: File = AppDirs.inbox) {

    val dir: File
        get() = root.apply { mkdirs() }

    /** v2 delta/resume plumbing: staging pool + baseline indexes under the inbox. */
    val delta: com.crossdrop.core.delta.DeltaReceiver =
        com.crossdrop.core.delta.FileDeltaStore(dir) { meta -> sanitize(meta.name) }

    data class ReceivedFile(val file: File) {
        val name: String get() = file.name
        val size: Long get() = file.length()
        val receivedAt: String
            get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    fun list(): List<ReceivedFile> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }?.map(::ReceivedFile) ?: emptyList()

    /** Collision- and traversal-safe local filename for an incoming [FileMeta]. */
    fun sinkFor(meta: FileMeta): OutputStream = FileOutputStream(uniqueFile(meta.name))

    /** Fresh path in the inbox for [name] — `name (1).ext` on collision. */
    fun uniqueFile(name: String): File {
        val safe = sanitize(name)
        var target = File(dir, safe)
        var i = 1
        // "name (1).ext", or "name (1)" when there is no extension — never a
        // trailing dot, which Windows would silently drop and alias.
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        while (target.exists()) {
            target = File(dir, "$stem ($i)$ext")
            i++
        }
        return target
    }

    /** Single-pass zero-fill then delete. */
    fun shred(file: File) {
        if (file.exists()) {
            runCatching {
                FileOutputStream(file).use { out ->
                    val zeros = ByteArray(64 * 1024)
                    var remaining = file.length()
                    while (remaining > 0) {
                        val n = minOf(remaining, zeros.size.toLong()).toInt()
                        out.write(zeros, 0, n)
                        remaining -= n
                    }
                }
            }
            file.delete()
        }
    }

    fun shredAll() {
        list().forEach { shred(it.file) }
        File(dir, ".staging").listFiles()?.forEach { it.delete() }
        File(dir, ".idx").listFiles()?.forEach { it.delete() }
    }

    private fun sanitize(name: String): String = sanitizeName(name)

    companion object {
        /**
         * Local filename for a name chosen by a remote peer. Safe on every OS
         * the inbox can run on: no path separators, only `[A-Za-z0-9._ -]`
         * (so no `:` — an NTFS alternate data stream — and no wildcard or
         * shell characters), no trailing dots/spaces (Windows drops them, so
         * "a." would alias "a"), and no Windows device names (`CON`, `NUL`,
         * `COM1`… — with any extension — which open the device, not a file).
         */
        internal fun sanitizeName(name: String): String {
            fun tidy(s: String) = s.trimEnd('.', ' ').ifBlank { "file" }
            val s = tidy(
                tidy(
                    name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^A-Za-z0-9._ -]"), "_"),
                ).take(96),    // take() can expose a new trailing dot/space
            )
            return if (VaultFs.isWindowsDeviceName(s)) "_$s" else s
        }
    }
}
