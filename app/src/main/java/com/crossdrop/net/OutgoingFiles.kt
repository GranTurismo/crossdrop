package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.crossdrop.core.protocol.FileMeta
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
