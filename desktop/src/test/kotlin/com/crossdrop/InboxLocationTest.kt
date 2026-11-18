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
}
