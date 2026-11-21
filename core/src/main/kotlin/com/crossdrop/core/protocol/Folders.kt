package com.crossdrop.core.protocol

/** A folder holds more files than one offer carries ([Folders.MAX_FILES]). */
class TooManyFilesException(val limit: Int) : Exception("more than $limit files")

/**
 * Sending a whole folder: the files go as one offer, each [FileMeta.dir] saying which sub-folder it came
 * from, so the receiver rebuilds the same tree. Everything about the layout comes from the other device,
 * so it is treated as untrusted text.
 */
object Folders {
    /** Most files in one folder send. An offer lists every file (~200 bytes each) and must fit one frame. */
    const val MAX_FILES = 5_000

    /** Deepest nesting a receiver rebuilds; anything deeper is cut off at this level rather than refused. */
    const val MAX_DEPTH = 16

    /** Names no one wants carried along: the OS's own litter. */
    private val junk = setOf(".ds_store", "thumbs.db", "desktop.ini", ".localized")

    fun isJunk(name: String): Boolean = name.lowercase() in junk

    /**
     * Where a received file goes inside the inbox, `/`-separated: its folder segments, then its name. Folder
     * segments go through [cleanDir] and the name through [cleanName] (the platform's sanitizers: no separators,
     * no `..`, nothing the file system treats specially). A loose file is just its cleaned name.
     */
    fun relativePath(meta: FileMeta, cleanName: (String) -> String, cleanDir: (String) -> String = cleanName): String {
        val segments = meta.dir.orEmpty().split('/', '\\').filter { it.isNotBlank() }.take(MAX_DEPTH).map(cleanDir)
        return (segments + cleanName(meta.name)).joinToString("/")
    }

    /** The folder every file of an offer sits in (its first path segment), or null when files are loose or from several folders. */
    fun topFolder(files: List<FileMeta>): String? {
        if (files.isEmpty()) return null
        val tops = files.map { it.dir?.split('/', '\\')?.firstOrNull { s -> s.isNotBlank() } }
        val first = tops.first() ?: return null
        return first.takeIf { tops.all { it == first } }
    }

    /** The picked folder's own name plus the path from it to [file]'s parent, as [FileMeta.dir] wants it. */
    fun dirOf(rootName: String, relativeParent: String): String =
        if (relativeParent.isBlank()) rootName else "$rootName/${relativeParent.trim('/')}"
}
