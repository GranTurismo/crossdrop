package com.crossdrop.storage

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.FileNames
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.NameClaims
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.platform.AppDirs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest

/**
 * Received files: a `CrossDrop` folder inside the user's Downloads ([AppDirs.inbox]), so they are
 * ordinary files they can open, move or share from Finder / File Explorer. Files stay until the
 * user opens, reveals or deletes them; wiping is a zero-fill pass plus unlink.
 *
 * The resume pool and delta indexes live in [state], outside the visible folder.
 *
 * A folder sent by another device arrives as a folder (`<inbox>/<folder>/<sub>/<file>`). CrossDrop only ever
 * looks inside, lists, moves or wipes the folders it created itself: it remembers their names in [state]
 * ("received folders"), so a folder of the person's own that happens to sit in the same place is never touched.
 *
 * The folder can be changed while the app runs ([relocate]); only [dir] and [delta] follow it, so callers
 * must read them each time instead of holding on to them.
 */
class InboxStore(initial: File = AppDirs.inbox, private val state: File = initial, custom: Boolean = false) {

    @Volatile private var root: File = initial

    /** True when the user picked this folder in Settings (the default is `<Downloads>/CrossDrop`). */
    @Volatile var isCustom: Boolean = custom
        private set

    /** Folders [switchTo] left, and whether each may have its files carried over ([moveFrom]). */
    private val movableFrom = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Two different incoming files never get the same place (names are cleaned, so different originals can collide). */
    private val claims = NameClaims()

    @Volatile private var deltaStore: com.crossdrop.core.delta.FileDeltaStore? = null

    val dir: File
        get() = root.apply { mkdirs() }

    /** v2 delta/resume plumbing: staging pool + baseline indexes (in [state]) for files in the inbox. */
    @Volatile var delta: com.crossdrop.core.delta.DeltaReceiver = deltaFor(dir)
        private set

    private fun deltaFor(folder: File): com.crossdrop.core.delta.DeltaReceiver {
        lateinit var store: com.crossdrop.core.delta.FileDeltaStore
        store = com.crossdrop.core.delta.FileDeltaStore(folder, state) { meta -> placeFor(folder, meta) { store.knows(it) } }
        deltaStore = store
        return store
    }

    /**
     * Where [meta] goes below [folder] (`/`-separated). The inbox may be a folder the person already keeps things in,
     * and the sender chooses the names, so two things must never happen: a received folder merging into one of the
     * person's own (which a wipe would then treat as received), and a received file replacing one of theirs (the delta
     * path writes over a same-named file it takes for an earlier copy). Only folders CrossDrop created, and files it
     * assembled itself ([ours]), are reused; anything else makes the newcomer `name (1)`. The answer is the same every
     * time it is asked during one transfer, because the first asker creates (and registers) what it points to.
     */
    private fun placeFor(folder: File, meta: FileMeta, ours: (File) -> Boolean): String {
        val parts = Folders.relativePath(meta, ::sanitize).split('/').toMutableList()
        if (parts.size > 1) { parts[0] = ownedFolder(folder, parts[0]); folders.add(folder, parts[0]) }
        val original = "${folder.path}\u0000${meta.dir.orEmpty()}\u0000${meta.name}"
        val rel = claims.claim(original, parts.joinToString("/")) { File(folder, it).let { f -> f.exists() && !ours(f) } }
        if (parts.size == 1) loose.add(folder, rel)
        return rel
    }

    /** [top] if it is free or ours; else the first `top (n)` that is. */
    private fun ownedFolder(folder: File, top: String): String {
        var name = top
        var n = 1
        while (File(folder, name).exists() && !folders.has(folder, name)) name = "$top (${n++})"
        return name
    }

    /**
     * Receive into [to] from now on and return the folder that was in use. Quick: the finished files
     * left behind stay there (and drop out of [list]) until [moveFrom] takes them along. Anything
     * unfinished is left alone, so call it between transfers.
     */
    @Synchronized
    fun switchTo(to: File, custom: Boolean = true): File {
        val from = dir
        val target = to.canonicalFile
        // Only CrossDrop's own folder can be emptied into the new one: a folder the person picked may hold their own
        // files (Documents…), and loose files can't be told apart from what was received there.
        movableFrom[from.canonicalPath] = !isCustom
        isCustom = custom
        if (target == from.canonicalFile) return from
        target.mkdirs()
        root = target
        delta = deltaFor(target)
        return from
    }

    /**
     * Move the finished files of [old] (the result of [switchTo]) and the folders CrossDrop received there
     * into the current folder, numbering a name that is already taken. Can be slow across drives. Returns
     * how many files moved.
     */
    fun moveFrom(old: File): Int {
        if (!old.isDirectory || old.canonicalFile == dir.canonicalFile) return 0
        if (movableFrom.remove(old.canonicalPath) != true) return 0
        var moved = 0
        for (f in old.listFiles().orEmpty()) {
            if (!f.isFile || Files.isSymbolicLink(f.toPath()) || f.name.startsWith(".") || f.isHidden || f.name.endsWith(".part")) continue
            // Names are kept as they are (they are not from a peer): only a clash gets a number.
            val to = freeFile(f.name)
            if (runCatching { Files.move(f.toPath(), to.toPath()) }.isSuccess) { moved++; loose.add(dir, to.name) }
        }
        for (top in folders.names(old)) {
            val src = File(old, top)
            if (!src.isDirectory) continue
            // Merge into a folder of ours with the same name; never into someone's own folder.
            val here = File(dir, top)
            val name = if (!here.exists() || folders.has(dir, top)) top else freeIn(dir, top).name
            moved += moveTree(src, File(dir, name))
            folders.add(dir, name)
            folders.remove(old, top)
        }
        return moved
    }

    private fun moveTree(src: File, dst: File, depth: Int = 0): Int {
        if (depth > Folders.MAX_DEPTH) return 0
        dst.mkdirs()
        var n = 0
        for (c in src.listFiles().orEmpty()) {
            if (c.name.endsWith(".part") || Files.isSymbolicLink(c.toPath())) continue
            if (c.isDirectory) n += moveTree(c, File(dst, c.name), depth + 1)
            else if (c.isFile && runCatching { Files.move(c.toPath(), freeIn(dst, c.name).toPath()) }.isSuccess) n++
        }
        src.delete()      // only succeeds once empty
        return n
    }

    /** [label] is what the list shows: the file name, or `<folder>/<sub>/<name>` for a file in a received folder. */
    data class ReceivedFile(val file: File, val label: String = file.name) {
        val name: String get() = label
        val size: Long get() = file.length()
        val receivedAt: String
            get() = SimpleDateFormat("HH:mm", com.crossdrop.core.i18n.I18n.locale()).format(Date(file.lastModified()))
    }

    /**
     * What CrossDrop received, newest first: the loose files and every file in the folders it created. In CrossDrop's own
     * folder every loose file counts; in a folder the person picked (Documents…) only the ones CrossDrop received do, so
     * their own files are never listed, counted or wiped. Links are never followed.
     */
    fun list(): List<ReceivedFile> {
        val root = dir
        val out = ArrayList<ReceivedFile>()
        val receivedLoose = if (isCustom) loose.names(root).toSet() else null
        root.listFiles()?.filter {
            it.isFile && isShown(it) && !Files.isSymbolicLink(it.toPath()) && (receivedLoose == null || it.name in receivedLoose)
        }?.mapTo(out) { ReceivedFile(it) }
        for (top in folders.names(root)) File(root, top).takeIf { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }?.let { walk(it, root, 0, out) }
        return out.sortedByDescending { it.file.lastModified() }
    }

    private fun isShown(f: File) = !f.name.startsWith(".") && !f.isHidden && !f.name.endsWith(".part")

    private fun walk(folder: File, root: File, depth: Int, out: MutableList<ReceivedFile>) {
        if (depth > Folders.MAX_DEPTH) return
        for (c in folder.listFiles().orEmpty()) {
            if (!isShown(c) || Files.isSymbolicLink(c.toPath())) continue
            if (c.isDirectory) walk(c, root, depth + 1, out)
            else if (c.isFile) out += ReceivedFile(c, c.relativeTo(root).invariantSeparatorsPath)
        }
    }

    /** Collision- and traversal-safe local filename for an incoming [FileMeta]. */
    fun sinkFor(meta: FileMeta): OutputStream = FileOutputStream(uniqueFile(meta))

    /** Fresh path in the inbox for [name] — `name (1).ext` on collision. */
    fun uniqueFile(name: String): File = freeFile(sanitize(name)).also { loose.add(dir, it.name) }

    /**
     * Fresh path for an incoming file: loose, or inside its folder (`<folder>/<sub>/<name>`, every segment
     * cleaned, folders created and remembered as ours). A name already taken gets `(1)`.
     */
    fun uniqueFile(meta: FileMeta): File {
        val parts = Folders.relativePath(meta, ::sanitize).split('/').toMutableList()
        if (parts.size == 1) return freeFile(parts[0]).also { loose.add(dir, it.name) }
        parts[0] = ownedFolder(dir, parts[0])          // never into a folder of the person's own
        folders.add(dir, parts[0])
        val parent = File(dir, parts.dropLast(1).joinToString("/")).also { it.mkdirs() }
        return freeIn(parent, parts.last())
    }

    /** [safe] as it is, or `safe (1).ext` when the inbox already has that name. */
    private fun freeFile(safe: String): File = freeIn(dir, safe)

    private fun freeIn(parent: File, safe: String): File {
        var target = File(parent, safe)
        var i = 1
        // "name (1).ext", or "name (1)" when there is no extension — never a
        // trailing dot, which Windows would silently drop and alias.
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        while (target.exists()) {
            target = File(parent, "$stem ($i)$ext")
            i++
        }
        return target
    }

    // ------------------------------------------------------- what we received --

    /**
     * A set of names, per inbox folder, kept in [state] (`.crossdrop-<prefix>-<hash of the inbox path>.txt`, one per line): which
     * folders CrossDrop created ([folders]) and which loose files it received ([loose]). Nothing else in the inbox is
     * ever listed (in a picked folder), moved or wiped.
     */
    private inner class Registry(private val prefix: String) {
        private val lock = Any()
        private var cache: Pair<File, LinkedHashSet<String>>? = null

        private fun file(root: File): File {
            val id = MessageDigest.getInstance("SHA-1").digest(root.absoluteFile.path.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(10)
            return File(state, ".crossdrop-$prefix-$id.txt")      // hidden: the state folder may be the inbox itself
        }

        private fun set(root: File): LinkedHashSet<String> =
            cache?.takeIf { it.first == root }?.second ?: LinkedHashSet<String>().also { names ->
                runCatching { file(root).readLines().filter { it.isNotBlank() }.toCollection(names) }
                cache = root to names
            }

        private fun save(root: File) = runCatching { state.mkdirs(); file(root).writeText(set(root).joinToString("\n")) }

        /** A copy, safe to walk while receives add to the real set. */
        fun names(root: File): List<String> = synchronized(lock) { set(root).toList() }
        fun has(root: File, name: String): Boolean = synchronized(lock) { name in set(root) }
        fun add(root: File, name: String) = synchronized(lock) { if (set(root).add(name)) save(root) }
        fun remove(root: File, name: String) = synchronized(lock) { if (set(root).remove(name)) save(root) }
    }

    private val folders = Registry("folders")
    private val loose = Registry("files")

    /** After deleting a file: remove the folders it leaves empty (never the inbox itself) and forget the ones that are gone. */
    private fun pruneEmptyFolders(from: File?) {
        val root = dir.toPath()
        var p = from?.toPath()
        while (p != null && p != root && p.startsWith(root)) {
            val f = p.toFile()
            if (f.isDirectory && f.list().isNullOrEmpty()) f.delete() else break
            p = p.parent
        }
        folders.names(dir).filter { !File(dir, it).exists() }.forEach { folders.remove(dir, it) }
    }

    /** Single-pass zero-fill then delete. A link is only removed: what it points at is not ours to overwrite. */
    fun shred(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { file.delete(); return }
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
            deltaStore?.forget(file)           // its resume index would otherwise vouch for whatever is saved here next
            file.delete()
            if (file.parentFile == dir) loose.remove(dir, file.name)
            pruneEmptyFolders(file.parentFile)
        }
    }

    fun shredAll() {
        list().forEach { shred(it.file) }
        File(state, ".staging").listFiles()?.forEach { it.delete() }
        File(state, ".idx").listFiles()?.forEach { it.delete() }
    }

    /**
     * One-time move of the pre-Downloads inbox ([from], inside Application Support) into this one:
     * visible files keep their names (numbered on a clash), the resume pool and indexes follow into
     * [state]. Returns how many files moved; an empty or missing [from] costs nothing, so it is safe
     * to call on every launch.
     */
    fun migrateFrom(from: File): Int {
        if (!from.isDirectory || from.canonicalFile == dir.canonicalFile) return 0
        var moved = 0
        val newName = HashMap<String, String>()          // old name -> name it got here (numbered on a clash)
        for (f in from.listFiles().orEmpty()) {
            if (!f.isFile || f.name.startsWith(".")) continue
            val to = uniqueFile(f.name)
            if (runCatching { Files.move(f.toPath(), to.toPath()) }.isSuccess) { moved++; newName[f.name] = to.name }
        }
        for (sub in listOf(".staging", ".idx")) {
            val src = File(from, sub)
            if (!src.isDirectory) continue
            val dst = File(state, sub).apply { mkdirs() }
            for (item in src.listFiles().orEmpty()) {
                // An index is `<file>.idx`: it follows its file, so a renumbered file never overwrites
                // (or inherits) the index of the one that was already here.
                val target = if (sub == ".idx" && item.name.endsWith(".idx"))
                    newName[item.name.removeSuffix(".idx")]?.let { "$it.idx" } ?: item.name
                else item.name
                runCatching { Files.move(item.toPath(), File(dst, target).toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            src.delete()
        }
        from.delete()    // only succeeds once empty
        return moved
    }

    private fun sanitize(name: String): String = sanitizeName(name)

    companion object {
        /**
         * Local filename for a name chosen by a remote peer. Safe on every OS the inbox can run on: letters of every
         * script are kept ([FileNames.sanitize]), but no path separators, no `:` (an NTFS alternate data stream), no
         * wildcard or shell characters, no trailing dots/spaces (Windows drops them, so "a." would alias "a"), and no
         * Windows device names (`CON`, `NUL`, `COM1`… — with any extension — which open the device, not a file).
         */
        internal fun sanitizeName(name: String): String {
            val s = FileNames.sanitize(name)
            return if (VaultFs.isWindowsDeviceName(s)) "_$s" else s
        }
    }
}
