package com.crossdrop

import com.crossdrop.platform.AppDirs
import com.crossdrop.platform.Platform
import com.crossdrop.storage.InboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The inbox lives in `<Downloads>/CrossDrop`; the old Application Support inbox moves across once. */
class InboxLocationTest {

    private fun tmp(prefix: String) = Files.createTempDirectory(prefix).toFile()

    // ------------------------------------------------------------ Downloads --

    @Test
    fun `mac and other systems use home Downloads`() {
        val home = File("/h/u")
        assertEquals(File(home, "Downloads"), AppDirs.downloadsDir(Platform.MAC, home))
        assertEquals(File(home, "Downloads"), AppDirs.downloadsDir(Platform.OTHER, home))
    }

    @Test
    fun `windows follows a moved Downloads folder from the registry`() {
        val home = File("C:/Users/u")
        val env = mapOf("USERPROFILE" to "C:\\Users\\u")::get
        // the default: %USERPROFILE%\Downloads
        assertEquals(
            File("C:\\Users\\u\\Downloads"),
            AppDirs.downloadsDir(Platform.WINDOWS, home, env) { "%USERPROFILE%\\Downloads" },
        )
        // redirected to another drive
        assertEquals(File("D:\\Stuff\\Downloads"),
            AppDirs.downloadsDir(Platform.WINDOWS, home, env) { "D:\\Stuff\\Downloads" })
        // the lookup failed, or names a variable we can't expand: never a folder called "%X%"
        assertEquals(File(home, "Downloads"), AppDirs.downloadsDir(Platform.WINDOWS, home, env) { null })
        assertEquals(File(home, "Downloads"), AppDirs.downloadsDir(Platform.WINDOWS, home, env) { "%NOPE%\\Downloads" })
        assertEquals(File(home, "Downloads"), AppDirs.downloadsDir(Platform.WINDOWS, home, env) { "   " })
    }

    @Test
    fun `inbox is a CrossDrop folder inside Downloads, or the fallback when that fails`() {
        val downloads = tmp("dl")
        val fallback = File(tmp("support"), "inbox")
        val inbox = AppDirs.inboxIn(downloads, fallback)
        assertEquals(File(downloads, "CrossDrop"), inbox)
        assertTrue(inbox.isDirectory)
        assertFalse(fallback.exists())

        // Downloads is a plain file → nothing can be created under it → the private fallback
        val blocked = File(tmp("blocked"), "Downloads").apply { writeText("not a folder") }
        assertEquals(fallback, AppDirs.inboxIn(blocked, fallback))
        assertTrue(fallback.isDirectory)
    }

    // ------------------------------------------------------------ migration --

    @Test
    fun `migration moves the old inbox into Downloads and keeps its resume state`() {
        val legacy = tmp("legacy")
        val visible = tmp("visible")
        val state = tmp("state")
        File(legacy, "photo.jpg").writeText("photo")
        File(legacy, "notes.txt").writeText("notes")
        File(legacy, ".staging").mkdirs(); File(legacy, ".staging/abc.bin").writeText("chunk")
        File(legacy, ".idx").mkdirs()
        File(legacy, ".idx/photo.jpg.idx").writeText("idx")
        File(legacy, ".idx/notes.txt.idx").writeText("legacy-notes-idx")
        File(visible, "notes.txt").writeText("already here")          // a name clash…
        File(state, ".idx").mkdirs(); File(state, ".idx/notes.txt.idx").writeText("existing-notes-idx")   // …with its own index

        val inbox = InboxStore(visible, state)
        assertEquals(2, inbox.migrateFrom(legacy))

        assertEquals("photo", File(visible, "photo.jpg").readText())
        assertEquals("already here", File(visible, "notes.txt").readText())   // untouched
        assertEquals("notes", File(visible, "notes (1).txt").readText())      // numbered, not overwritten
        assertEquals("chunk", File(state, ".staging/abc.bin").readText())
        assertEquals("idx", File(state, ".idx/photo.jpg.idx").readText())
        // The renumbered file's index follows it; the file that was already here keeps its own.
        assertEquals("legacy-notes-idx", File(state, ".idx/notes (1).txt.idx").readText())
        assertEquals("existing-notes-idx", File(state, ".idx/notes.txt.idx").readText())
        assertFalse("the emptied old folder is removed", legacy.exists())
        assertTrue("no bookkeeping in the visible folder", visible.list()!!.none { it.startsWith(".") })

        // Cheap and harmless on every later launch.
        assertEquals(0, inbox.migrateFrom(legacy))
        assertEquals(0, inbox.migrateFrom(visible))
    }

    @Test
    fun `received-files list ignores hidden files and wiping leaves the user's other folders alone`() {
        val visible = tmp("visible")
        val state = tmp("state")
        val inbox = InboxStore(visible, state)
        File(visible, "a.bin").writeText("a")
        File(visible, ".hidden").writeText("h")
        File(visible, "sub").mkdirs(); File(visible, "sub/keep.txt").writeText("k")
        File(state, ".staging").mkdirs(); File(state, ".staging/x.bin").writeText("x")

        assertEquals(listOf("a.bin"), inbox.list().map { it.name })
        inbox.shredAll()
        assertTrue(inbox.list().isEmpty())
        assertTrue(File(visible, "sub/keep.txt").exists())
        assertFalse(File(state, ".staging/x.bin").exists())
    }

    // ------------------------------------------------------- choosing a folder --

    @Test
    fun `switching the inbox folder sends new files there and carries the old ones across`() {
        val first = tmp("first")
        val second = tmp("second")
        val state = tmp("state")
        val inbox = InboxStore(first, state)
        val firstDelta = inbox.delta
        assertFalse(inbox.isCustom)
        File(first, "Résumé 2026.pdf").writeText("cv")          // not a peer's name: it must keep its spelling
        File(first, "clash.txt").writeText("new one")
        File(second, "clash.txt").writeText("already there")
        File(first, "half.7.part").writeText("unfinished")       // an unfinished receive stays behind
        File(first, ".hidden").writeText("h")
        File(first, "sub").mkdirs(); File(first, "sub/keep.txt").writeText("k")

        val old = inbox.switchTo(second)
        assertEquals(first.canonicalFile, old.canonicalFile)
        assertTrue(inbox.isCustom)
        assertEquals(second.canonicalFile, inbox.dir.canonicalFile)
        assertTrue("the resume/delta plumbing follows the folder", inbox.delta !== firstDelta)
        assertEquals(second.canonicalFile, inbox.uniqueFile("fresh.bin").parentFile.canonicalFile)

        assertEquals(2, inbox.moveFrom(old))
        assertEquals("cv", File(second, "Résumé 2026.pdf").readText())
        assertEquals("already there", File(second, "clash.txt").readText())     // never overwritten
        assertEquals("new one", File(second, "clash (1).txt").readText())
        assertTrue(File(first, "half.7.part").exists())
        assertTrue(File(first, ".hidden").exists())
        assertTrue(File(first, "sub/keep.txt").exists())
        // in a folder the person picked only what was received is listed: their own clash.txt is not
        assertEquals(setOf("Résumé 2026.pdf", "clash (1).txt"), inbox.list().map { it.name }.toSet())

        // Asking for the folder already in use is a no-op.
        assertEquals(second.canonicalFile, inbox.switchTo(second, custom = true).canonicalFile)
        assertEquals(0, inbox.moveFrom(second))
        // Going back to CrossDrop's own folder leaves a picked folder's files where they are (it may hold the person's own).
        val back = inbox.switchTo(first, custom = false)
        assertFalse(inbox.isCustom)
        assertEquals(0, inbox.moveFrom(back))
        assertTrue(File(second, "Résumé 2026.pdf").exists())
        assertFalse(inbox.list().any { it.name == "Résumé 2026.pdf" })
    }

    @Test
    fun `leaving the old files behind just drops them from the list`() {
        val first = tmp("first")
        val second = tmp("second")
        val inbox = InboxStore(first, tmp("state"))
        File(first, "a.bin").writeText("a")
        inbox.switchTo(second)
        assertTrue(inbox.list().isEmpty())
        assertTrue(File(first, "a.bin").exists())
        assertEquals(0, inbox.moveFrom(File(first, "no-such-folder")))
    }

    @Test
    fun `leaving a folder the person picked never moves the files that were already in it`() {
        val default = tmp("default"); val docs = tmp("docs")
        File(docs, "taxes.pdf").writeText("the person's own file")
        val inbox = InboxStore(default, tmp("state"))
        inbox.switchTo(docs, custom = true)
        File(docs, "from-a-friend.txt").writeText("received here")       // loose files can't be told apart from the person's own
        val old = inbox.switchTo(default, custom = false)
        assertEquals("nothing is carried out of a picked folder", 0, inbox.moveFrom(old))
        assertTrue(File(docs, "taxes.pdf").exists())
        assertTrue(File(docs, "from-a-friend.txt").exists())
        assertTrue(inbox.list().isEmpty())
        // going the other way (from CrossDrop's own folder into a picked one) still carries the files
        File(default, "mine.bin").writeText("received")
        val first = inbox.switchTo(docs, custom = true)
        assertEquals(1, inbox.moveFrom(first))
        assertTrue(File(docs, "mine.bin").exists())
    }
}
