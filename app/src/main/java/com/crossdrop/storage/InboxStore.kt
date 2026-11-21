package com.crossdrop.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.FileNames
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.NameClaims
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
 * The person can pick another folder instead ([setFolder], a Storage Access Framework tree — Documents,
 * a memory card, any folder Android lets an app write to): [publish] then copies into it. Files already
 * in `Download/CrossDrop/` stay listed, so switching never hides what was received before.
 *
 * [list] shows all of it: the published files, the chosen folder and anything still in the private [dir]
 * (a publish that failed, or a card kept for later), so a file never disappears from the inbox.
 */
class InboxStore(private val context: Context) {

    /** Private scratch space: receives in flight, the resume pool, delta indexes. Finished files leave it. */
    val dir: File
        get() = File(context.filesDir, "inbox").apply { mkdirs() }

    /**
     * v2 delta/resume plumbing: staging pool + baseline indexes under the private dir. Once a file has
     * been published it is no longer a local baseline, so a re-send of the same name transfers in full.
     */
    private val store: com.crossdrop.core.delta.FileDeltaStore = com.crossdrop.core.delta.FileDeltaStore(dir, dir, ::placeFor)
    val delta: com.crossdrop.core.delta.DeltaReceiver get() = store

    /** Two different incoming files never get the same place (names are cleaned, so different originals can collide). */
    private val claims = NameClaims()

    /** Where [meta] is assembled in the private dir. Same original, same answer; a different one that cleans to it gets `name (1)`. */
    private fun placeFor(meta: FileMeta): String =
        claims.claim("${meta.dir.orEmpty()}\u0000${meta.name}", Folders.relativePath(meta, ::leaf, ::segment)) { rel ->
            File(dir, rel).let { it.exists() && !store.knows(it) }
        }

    /** Bumps whenever files were added or removed — the UI refreshes its list and badges on it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val prefs get() = context.getSharedPreferences("inbox", Context.MODE_PRIVATE)

    /** The folder picked in Settings, or null for `Download/CrossDrop/`. Only trusted while Android still lets us write to it. */
    private val tree: DocumentFile?
        get() = prefs.getString(TREE, null)?.let { saved ->
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(saved)) }.getOrNull()
                ?.takeIf { it.isDirectory && it.canWrite() }
        }

    /** True while receives go to a folder the person picked. */
    val isCustom: Boolean get() = tree != null

    /**
     * Where received files go, for Settings: `Documents/Received`, or the plain `Download/CrossDrop` when
     * nothing was picked. A picked folder that has since gone (card removed, folder deleted) reads as the
     * default, which is where receives go meanwhile.
     */
    fun folderLabel(): String {
        val picked = tree ?: return RELATIVE_PATH.trimEnd('/')
        val id = runCatching { DocumentsContract.getTreeDocumentId(picked.uri) }.getOrNull()
        return id?.substringAfter(':', "")?.ifBlank { null } ?: picked.name ?: RELATIVE_PATH.trimEnd('/')
    }

    /**
     * Receive into [uri] (a tree from `OpenDocumentTree`) from now on; null = back to `Download/CrossDrop/`.
     * Returns false, and changes nothing, when Android won't let us write there. Take the persistable
     * permission before calling.
     */
    fun setFolder(uri: Uri?): Boolean {
        if (uri == null) {
            prefs.edit { remove(TREE) }
        } else {
            val ok = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                ?.let { it.isDirectory && it.canWrite() } == true
            if (!ok) return false
            prefs.edit { putString(TREE, uri.toString()) }
        }
        _version.value++
        return true
    }

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
        (published() + picked() + pending()).sortedByDescending { it.modifiedMs }

    /**
     * The files in the picked folder, plus those inside the folders CrossDrop received there (and only those:
     * the person's own folders in the same place are never looked into, so a wipe can't reach them).
     */
    private fun picked(): List<ReceivedFile> {
        val root = tree?.uri ?: return emptyList()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return emptyList()
        val ours = treeFolders(root)
        val looseOurs = treeLoose(root)
        val out = ArrayList<ReceivedFile>()
        fun walk(parentId: String, label: String, depth: Int) {
            for (r in SafTree.children(context, root, parentId)) {
                if (r.name.startsWith(".")) continue
                val path = if (label.isEmpty()) r.name else "$label/${r.name}"
                if (r.isDir) {
                    if (depth < Folders.MAX_DEPTH && (depth > 0 || r.name in ours)) walk(r.id, path, depth + 1)
                } else if (depth > 0 || r.name in looseOurs) {
                    // At the top of a folder the person picked, only the files CrossDrop received count: their own are never listed or wiped.
                    out += ReceivedFile(path, r.size, r.modifiedMs, uri = DocumentsContract.buildDocumentUriUsingTree(root, r.id))
                }
            }
        }
        walk(rootId, "", 0)
        return out
    }

    /** Names of the folders CrossDrop created at the top of the picked [root] (kept in prefs: a received folder is ours, nothing else is). */
    private fun treeFolders(root: Uri): Set<String> =
        prefs.getStringSet(TREE_FOLDERS, emptySet()).orEmpty().filter { it.startsWith("$root\n") }.map { it.substringAfter('\n') }.toSet()

    private fun noteTreeFolder(root: Uri, name: String) {
        val all = prefs.getStringSet(TREE_FOLDERS, emptySet()).orEmpty().toMutableSet()
        if (all.add("$root\n$name")) prefs.edit { putStringSet(TREE_FOLDERS, all) }
    }

    /** Names of the loose files CrossDrop received at the top of the picked [root]. */
    private fun treeLoose(root: Uri): Set<String> =
        prefs.getStringSet(TREE_LOOSE, emptySet()).orEmpty().filter { it.startsWith("$root\n") }.map { it.substringAfter('\n') }.toSet()

    private fun noteTreeLoose(root: Uri, name: String) {
        val all = prefs.getStringSet(TREE_LOOSE, emptySet()).orEmpty().toMutableSet()
        if (all.add("$root\n$name")) prefs.edit { putStringSet(TREE_LOOSE, all) }
    }

    private fun published(): List<ReceivedFile> = runCatching {
        val out = ArrayList<ReceivedFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            // The inbox itself and the folders received into it (`Download/CrossDrop/<folder>/…`). Without
            // storage permission MediaStore only ever shows what this app put there.
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(RELATIVE_PATH, RELATIVE_PATH.trimEnd('/'), "$RELATIVE_PATH%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val under = c.getString(4).orEmpty().removePrefix(RELATIVE_PATH)
                out += ReceivedFile(
                    name = under + name,
                    size = c.getLong(2),
                    modifiedMs = c.getLong(3) * 1000,
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    /** Everything in the private dir that is a finished file, nested folders included (it only ever holds our own receives). */
    private fun pending(): List<ReceivedFile> {
        val out = ArrayList<ReceivedFile>()
        fun walk(d: File, depth: Int) {
            if (depth > Folders.MAX_DEPTH) return
            for (f in d.listFiles().orEmpty()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) walk(f, depth + 1)
                else if (isVisible(f)) out += ReceivedFile(f.relativeTo(dir).invariantSeparatorsPath, f.length(), f.lastModified(), file = f)
            }
        }
        walk(dir, 0)
        return out
    }

    /** A finished file in the private dir — not bookkeeping, not an unfinished `<name>.<n>.part`. */
    private fun isVisible(f: File) = f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part")

    /**
     * Collision- and traversal-safe new local file for an incoming [FileMeta]; a file of a received folder
     * lands in its sub-folder (`<folder>/<sub>/<name>`, every segment cleaned, created here).
     */
    fun newFileFor(meta: FileMeta): File {
        val rel = Folders.relativePath(meta, ::leaf, ::segment)
        val first = File(dir, rel).also { it.parentFile?.mkdirs() }
        val parent = first.parentFile ?: dir
        val safe = first.name
        var target = first
        var i = 1
        while (target.exists()) {
            val ext = safe.substringAfterLast('.', "")
            target = File(parent, "${safe.substringBeforeLast('.')} ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
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
        // A file of a received folder keeps its place: `<folder>/<sub>` below the inbox.
        val sub = runCatching { file.relativeTo(dir).invariantSeparatorsPath }.getOrNull()
            ?.takeIf { !it.startsWith("..") }?.substringBeforeLast('/', "").orEmpty()
        tree?.let { folder -> return publishTo(folder, file, sub) }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            // Only a type we are sure of: a wrong one makes MediaStore rename the file to match it.
            mimeOf(file.name)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (sub.isEmpty()) RELATIVE_PATH else "$RELATIVE_PATH$sub/")
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
        finished(file)
        return uri
    }

    /** A file left the private dir for its final place: drop its resume index and any folder it leaves empty. */
    private fun finished(file: File) {
        store.forget(file)
        file.delete()
        var p = file.parentFile
        while (p != null && p != dir && p.startsWith(dir) && p.isDirectory && p.list().isNullOrEmpty()) { p.delete(); p = p.parentFile }
        _version.value++
    }

    /**
     * Copy a finished [file] into the picked [folder] (inside its sub-folder [sub] when it belongs to a received
     * folder; those are created and remembered as ours). A name already taken there is numbered ("a (1).pdf"),
     * never overwritten. Null when the copy failed: no half file is left behind, and [file] stays in the
     * private dir (and in [list]) so it can be tried again.
     */
    private fun publishTo(folder: DocumentFile, file: File, sub: String): Uri? {
        val segs = sub.split('/').filter { it.isNotEmpty() }.toMutableList()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(folder.uri) }.getOrNull() ?: return null
        // One listing per folder level (`DocumentFile.findFile` asks the provider once per child, so a big folder would
        // make every published file cost a query per entry).
        var kids = SafTree.children(context, folder.uri, rootId)
        // The sender chooses the folder's name; it never merges into a folder of the person's own that has the same
        // name (a wipe would then treat that folder as received). Only folders CrossDrop created are reused.
        if (segs.isNotEmpty()) segs[0] = ownedFolderName(kids, treeFolders(folder.uri), segs[0])
        val parent = runCatching {
            var cur = folder
            for ((i, seg) in segs.withIndex()) {
                val existing = kids.firstOrNull { it.name == seg && it.isDir }
                if (existing != null) {
                    cur = DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(folder.uri, existing.id)) ?: error("cannot open $seg")
                    kids = SafTree.children(context, folder.uri, existing.id)
                } else {
                    cur = cur.createDirectory(seg) ?: error("cannot create $seg")
                    kids = emptyList()
                }
                if (i == 0) noteTreeFolder(folder.uri, seg)      // ours from the moment it exists, whatever happens next
            }
            cur
        }.getOrNull() ?: return null
        val taken = kids.mapTo(HashSet()) { it.name }
        val stem = file.name.substringBeforeLast('.', file.name)
        val ext = file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var name = file.name
        var i = 1
        while (name in taken) { name = "$stem ($i)$ext"; i++ }
        // An unknown type goes as plain bytes: a wrong guess makes some providers rename the file to fit it.
        val target = runCatching { parent.createFile(mimeOf(name) ?: "application/octet-stream", name) }.getOrNull()
            ?: return null
        try {
            file.inputStream().use { src ->
                (context.contentResolver.openOutputStream(target.uri) ?: error("cannot open $name")).use { out ->
                    src.copyTo(out, 256 * 1024)
                }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            return null
        }
        if (segs.isEmpty()) noteTreeLoose(folder.uri, target.name ?: name)       // received here: ours to list, and to wipe
        finished(file)
        return target.uri
    }

    /** [top] if the picked folder's [kids] have nothing by that name or it is a folder we created; else the first `top (n)` that qualifies. */
    private fun ownedFolderName(kids: List<SafTree.Child>, ours: Set<String>, top: String): String {
        val names = kids.mapTo(HashSet()) { it.name }
        var name = top
        var n = 1
        while (name in names && name !in ours) name = "$top (${n++})"
        return name
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
            runCatching {
                // MediaStore rows are deleted through the resolver; a picked folder's documents through the provider.
                if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
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

    /** A file name from a peer: letters of every script kept, nothing unsafe, never just dots ([FileNames.sanitize]). */
    private fun leaf(name: String): String = FileNames.sanitize(name)

    /** A folder name from a peer: like [leaf] (never `..`, never empty, no dots or spaces at the end). */
    private fun segment(name: String): String = FileNames.sanitize(name)

    private companion object {
        /** MediaStore's `RELATIVE_PATH` of the inbox (it stores the trailing slash). */
        const val RELATIVE_PATH = "Download/CrossDrop/"
        const val MIGRATED = "downloads_migrated"
        const val TREE = "inbox_tree"
        const val TREE_FOLDERS = "inbox_tree_folders"
        const val TREE_LOOSE = "inbox_tree_loose"
    }
}
