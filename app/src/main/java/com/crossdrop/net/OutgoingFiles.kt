package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.TooManyFilesException
import com.crossdrop.storage.SafTree
import com.crossdrop.core.transfer.OutgoingFile

/** Resolve a SAF [Uri] into a streamable [OutgoingFile]; null when unreadable. */
internal fun Context.outgoingFileFor(uri: Uri): OutgoingFile? = runCatching {
    var name = "file"
    var size = -1L
    contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                ?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                ?.let { if (!c.isNull(it)) size = c.getLong(it) }
        }
    }
    val meta = FileMeta(name, size.coerceAtLeast(0), contentResolver.getType(uri))
    OutgoingFile(meta) {
        contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException("cannot open $uri")
    }
}.getOrNull()

/**
 * Everything inside the folder [tree] (from `OpenDocumentTree`) as streamable files that remember where they sit
 * ([FileMeta.dir]), so the receiver rebuilds the tree. The OS's own litter stays behind and empty folders aren't
 * sent. Throws [TooManyFilesException] past [Folders.MAX_FILES]: half a folder would be worse than none.
 */
internal fun Context.outgoingFolder(tree: Uri): List<OutgoingFile> {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootName = runCatching { DocumentFile.fromTreeUri(this, tree)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Folder"
    val out = ArrayList<OutgoingFile>()
    fun walk(parentId: String, rel: String, depth: Int) {
        // the picked folder is one path segment itself: below it only MAX_DEPTH - 1 more fit what a receiver rebuilds
        if (depth > Folders.MAX_DEPTH - 1) return
        for (c in SafTree.children(this, tree, parentId).sortedBy { it.name }) {
            if (Folders.isJunk(c.name)) continue
            if (c.isDir) { walk(c.id, if (rel.isEmpty()) c.name else "$rel/${c.name}", depth + 1); continue }
            if (out.size >= Folders.MAX_FILES) throw TooManyFilesException(Folders.MAX_FILES)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.id)
            out += OutgoingFile(FileMeta(c.name, c.size, c.mime, dir = Folders.dirOf(rootName, rel))) {
                contentResolver.openInputStream(doc) ?: throw java.io.FileNotFoundException("cannot open ${c.name}")
            }
        }
    }
    walk(rootId, "", 0)
    return out
}
