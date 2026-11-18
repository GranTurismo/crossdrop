package com.crossdrop.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import com.crossdrop.core.protocol.FileMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Received files live in `Download/CrossDrop/`, the public Downloads folder, so any file manager or
 * app can open them. Android won't let the transfer engine write there with plain file access (scoped
 * storage), so a receive is assembled in the app's private [dir] first — with the resume pool and delta
 * indexes — and [publish]ed into Downloads through MediaStore when it is whole. No permission is needed:
 * the app owns what it publishes, and can list, open, shred and delete it.
 *
 * [list] shows both: the published files and anything still in the private [dir] (a publish that
 * failed, or a card kept for later), so a file never disappears from the inbox.
 */
class InboxStore(private val context: Context) {

    /** Private scratch space: receives in flight, the resume pool, delta indexes. Finished files leave it. */
    val dir: File
        get() = File(context.filesDir, "inbox").apply { mkdirs() }

    /**
     * v2 delta/resume plumbing: staging pool + baseline indexes under the private dir. Once a file has
     * been published it is no longer a local baseline, so a re-send of the same name transfers in full.
     */
    val delta: com.crossdrop.core.delta.DeltaReceiver =
        com.crossdrop.core.delta.FileDeltaStore(dir) { meta -> sanitize(meta.name) }

    /** Bumps whenever files were added or removed — the UI refreshes its list and badges on it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** One inbox entry: a published file ([uri]) or one still in the private dir ([file]). */
    data class ReceivedFile(
        val name: String,
        val size: Long,
        val modifiedMs: Long,
        val uri: Uri? = null,
        val file: File? = null,
    ) {
        val receivedAt: String
            get() = SimpleDateFormat("HH:mm", com.crossdrop.core.i18n.I18n.locale()).format(Date(modifiedMs))
    }

    fun list(): List<ReceivedFile> =
        (published() + pending()).sortedByDescending { it.modifiedMs }

    private fun published(): List<ReceivedFile> = runCatching {
        val out = ArrayList<ReceivedFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)",
            arrayOf(RELATIVE_PATH, RELATIVE_PATH.trimEnd('/')),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += ReceivedFile(
                    name = c.getString(1) ?: continue,
                    size = c.getLong(2),
                    modifiedMs = c.getLong(3) * 1000,
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun pending(): List<ReceivedFile> =
        dir.listFiles()?.filter { isVisible(it) }
            ?.map { ReceivedFile(it.name, it.length(), it.lastModified(), file = it) } ?: emptyList()

    /** A finished file in the private dir — not bookkeeping, not an unfinished `<name>.<n>.part`. */
    private fun isVisible(f: File) = f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part")

    /** Collision- and traversal-safe new local file for an incoming [FileMeta]. */
    fun newFileFor(meta: FileMeta): File {
        val safe = sanitize(meta.name)
        var target = File(dir, safe)
        var i = 1
        while (target.exists()) {
            val ext = safe.substringAfterLast('.', "")
            target = File(dir, "${safe.substringBeforeLast('.')} ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
            i++
        }
        return target
    }

    fun sinkFor(meta: FileMeta): OutputStream = FileOutputStream(newFileFor(meta))

    /**
     * Move a finished [file] into `Download/CrossDrop/`. MediaStore numbers a name that is already
     * there ("a (1).pdf"). Returns the new URI, or null when it could not be published — the file then
     * stays in the private dir and keeps showing in [list].
     */
    fun publish(file: File): Uri? {
        if (!file.isFile) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            // Only a type we are sure of: a wrong one makes MediaStore rename the file to match it.
            mimeOf(file.name)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return null
        try {
            file.inputStream().use { src ->
                (resolver.openOutputStream(uri) ?: error("cannot open ${file.name}")).use { out ->
                    src.copyTo(out, 256 * 1024)
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }     // no half file in Downloads
            return null
        }
        file.delete()
        File(File(dir, ".idx"), "${file.name}.idx").delete()
        _version.value++
        return uri
    }

    /**
     * One-time move of what the inbox held before it was in Downloads (everything in the private dir
     * then) into Downloads. Repeats on the next launch if some file could not be moved.
     */
    fun migrateToDownloads(): Int {
        val prefs = context.getSharedPreferences("inbox", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED, false)) return 0
        // A file touched in the last minute may be a receive still in flight (the stream path writes under its
        // final name): leave it, and the next launch picks it up.
        val settled = System.currentTimeMillis() - 60_000
        val moved = dir.listFiles().orEmpty().filter { isVisible(it) && it.lastModified() < settled }
            .count { publish(it) != null }
        if (dir.listFiles().orEmpty().none(::isVisible)) prefs.edit { putBoolean(MIGRATED, true) }
        return moved
    }

    /** Overwrite with zeros, then delete. */
    fun shred(item: ReceivedFile) {
        item.uri?.let { uri ->
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { out -> zeroFill(out, item.size) }
                }
            }
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        item.file?.let { shred(it) }
        _version.value++
    }

    /** Single-pass zero-fill then delete — cryptographic shred for ephemeral data. */
    fun shred(file: File) {
        if (file.exists()) {
            runCatching { FileOutputStream(file).use { out -> zeroFill(out, file.length()) } }
            file.delete()
        }
    }

    fun shredAll() {
        list().forEach { shred(it) }
        File(dir, ".staging").listFiles()?.forEach { it.delete() }
        File(dir, ".idx").listFiles()?.forEach { it.delete() }
        _version.value++
    }

    private fun zeroFill(out: OutputStream, length: Long) {
        val zeros = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size.toLong()).toInt()
            out.write(zeros, 0, n)
            remaining -= n
        }
    }

    private fun mimeOf(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "file" }
            .take(96)

    private companion object {
        /** MediaStore's `RELATIVE_PATH` of the inbox (it stores the trailing slash). */
        const val RELATIVE_PATH = "Download/CrossDrop/"
        const val MIGRATED = "downloads_migrated"
    }
}
