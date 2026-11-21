package com.crossdrop.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Reading a Storage Access Framework tree cheaply: one provider query per folder, not one per attribute per file. */
object SafTree {
    /** One child of a document, as its provider reports it. [mime] is null when the provider didn't say. */
    class Child(val id: String, val name: String, val size: Long, val modifiedMs: Long, val mime: String?, val isDir: Boolean)

    /** The children of [parentDocId] in the tree [treeUri]; empty when the folder can't be read. */
    fun children(context: Context, treeUri: Uri, parentDocId: String): List<Child> = runCatching {
        val out = ArrayList<Child>()
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(4)
                out += Child(
                    id = c.getString(0) ?: continue, name = c.getString(1) ?: continue,
                    size = if (c.isNull(2)) 0 else c.getLong(2),
                    modifiedMs = if (c.isNull(3)) 0 else c.getLong(3),
                    mime = mime?.takeIf { it != DocumentsContract.Document.MIME_TYPE_DIR && it != "application/octet-stream" },
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        out
    }.getOrDefault(emptyList())
}
