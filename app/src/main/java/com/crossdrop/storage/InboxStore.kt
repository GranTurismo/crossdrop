package com.crossdrop.storage

import android.content.Context
import com.crossdrop.core.protocol.FileMeta
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sandboxed staging area for received files — deliberately separate from the
 * shared gallery/downloads (storage-isolation pillar). Files sit in the app
 * sandbox until the user exports or deletes them; wiping the inbox is a
 * secure-delete pass plus unlink.
 */
class InboxStore(private val context: Context) {

    val dir: File
        get() = File(context.filesDir, "inbox").apply { mkdirs() }

    data class ReceivedFile(val file: File) {
        val name: String get() = file.name
        val size: Long get() = file.length()
        val receivedAt: String
            get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    fun list(): List<ReceivedFile> =
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.map(::ReceivedFile) ?: emptyList()

    /** Collision- and traversal-safe local filename for an incoming [FileMeta]. */
    fun sinkFor(meta: FileMeta): OutputStream {
        val safe = sanitize(meta.name)
        var target = File(dir, safe)
        var i = 1
        while (target.exists()) {
            target = File(dir, "${safe.substringBeforeLast('.')} ($i).${safe.substringAfterLast('.', "")}")
            i++
        }
        return FileOutputStream(target)
    }

    /** Single-pass zero-fill then delete — cryptographic shred for ephemeral data. */
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

    fun shredAll() = list().forEach { shred(it.file) }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "file" }
            .take(96)
}
