package com.crossdrop.net

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.TooManyFilesException
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.vault.VaultFs
import java.io.File
import java.nio.file.Files

/** Wrap a local [File] as a streamable [OutgoingFile]; null when unreadable or a directory. */
internal fun File.toOutgoingFile(): OutgoingFile? =
    if (!isFile || !canRead()) null
    else OutgoingFile(FileMeta(name, length(), VaultFs.mimeTypeOf(name))) { inputStream() }

/**
 * Everything inside this folder as streamable files that remember where they sit ([FileMeta.dir]), so the
 * receiver rebuilds the tree. Links are not followed (a link must not pull in files from elsewhere), the OS's
 * own litter (`.DS_Store`, `Thumbs.db`) stays behind, and empty folders aren't sent. Throws
 * [TooManyFilesException] past [Folders.MAX_FILES]: half a folder would be worse than none.
 */
internal fun File.toOutgoingFolder(): List<OutgoingFile> {
    val rootName = name.ifBlank { "Folder" }
    val out = ArrayList<OutgoingFile>()
    fun walk(dir: File, rel: String, depth: Int) {
        // the picked folder is one path segment itself: below it only MAX_DEPTH - 1 more fit what a receiver rebuilds
        if (depth > Folders.MAX_DEPTH - 1) return
        for (c in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (Files.isSymbolicLink(c.toPath()) || Folders.isJunk(c.name)) continue
            if (c.isDirectory) walk(c, if (rel.isEmpty()) c.name else "$rel/${c.name}", depth + 1)
            else if (c.isFile && c.canRead()) {
                if (out.size >= Folders.MAX_FILES) throw TooManyFilesException(Folders.MAX_FILES)
                out += OutgoingFile(FileMeta(c.name, c.length(), VaultFs.mimeTypeOf(c.name), dir = Folders.dirOf(rootName, rel))) { c.inputStream() }
            }
        }
    }
    walk(this, "", 0)
    return out
}

/** Files and folders the person picked or dropped, as one flat list of streamable files. */
internal fun List<File>.toOutgoingFiles(): List<OutgoingFile> {
    val all = flatMap { if (it.isDirectory) it.toOutgoingFolder() else listOfNotNull(it.toOutgoingFile()) }
    if (all.size > Folders.MAX_FILES) throw TooManyFilesException(Folders.MAX_FILES)
    return all
}
