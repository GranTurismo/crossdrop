package com.crossdrop.core

import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.core.vault.VaultFs.VaultError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Windows hosts must refuse names NTFS can't store or silently aliases, and must
 * not let 8.3 short names reach hidden entries. The rules are a constructor flag
 * so they are testable on any OS; other hosts keep their permissive behaviour.
 */
class WindowsNamesTest {

    private val isWindowsHost = File.separatorChar == '\\'

    private fun tmp() = Files.createTempDirectory("vfs-win").toFile().canonicalFile

    private fun expect(code: Int, block: () -> Unit) {
        try { block(); fail("expected VaultError $code") }
        catch (e: VaultError) { assertEquals(code, e.code) }
    }

    @Test
    fun `unsafe segments`() {
        listOf(
            "ab:stream", "foo.txt:hidden", "foo.txt::\$DATA",     // alternate data streams
            "CON", "con", "NUL.txt", "aux.tar.gz", "COM3", "LPT1.log", "PRN ",   // devices
            "CONIN$", "conout$", "COM\u00b9", "lpt\u00b2.txt",                    // console handles, superscript digits
            "trail.", "space ",                                    // silently stripped by Win32
            "what?", "star*", "pipe|x", "a<b", "a>b", "q\"q", "bell\u0007",
        ).forEach { assertTrue("'$it' should be unsafe", VaultFs.isWindowsUnsafeSegment(it)) }

        listOf(
            "a.txt", "file (1).txt", "console", "Com10", "LPT0", "résumé.pdf", "日本語.txt",
            "a.b.c", ".hidden-is-handled-elsewhere", "100%", "a&b", "a'b", "~tilde",
        ).forEach { assertFalse("'$it' should be fine", VaultFs.isWindowsUnsafeSegment(it)) }
    }

    @Test
    fun `device names are judged the way win32 does`() {
        listOf("CON", "nul", "Com1", "LPT9", "aux.txt", "NUL .log", "con.tar.gz", "CONIN$", "COM\u00b2")
            .forEach { assertTrue("'$it'", VaultFs.isWindowsDeviceName(it)) }
        listOf("console", "Com10", "COM0", "LPT", "nullable.txt", "my.con", "a.con.txt")
            .forEach { assertFalse("'$it'", VaultFs.isWindowsDeviceName(it)) }
    }

    @Test
    fun `the rejection names the offending segment`() {
        val fs = FileVaultFs(tmp(), windowsNames = true)
        try { fs.sanitize("docs/What?.pdf"); fail("expected rejection") }
        catch (e: VaultError) {
            assertEquals(400, e.code)
            assertTrue(e.message!!, e.message!!.contains("What?.pdf"))
        }
    }

    @Test
    fun `windows host rejects unsafe names anywhere in the path`() {
        val fs = FileVaultFs(tmp(), windowsNames = true)
        for (p in listOf("ab:c", "docs/ab:c", "CON", "docs/nul.txt", "x/COM3/y", "name.", "docs/sp ", "q?", "a*b")) {
            expect(400) { fs.resolve(p) }
            expect(400) { fs.sanitize(p) }
        }
        // and ordinary names pass untouched
        for (p in listOf("a.txt", "docs/sub/file (1).txt", "console", "Com10", "résumé.pdf")) {
            assertEquals(p, fs.sanitize(p))
            fs.resolve(p)
        }
    }

    @Test
    fun `other hosts keep their permissive behaviour`() {
        assumeFalse("on a real Windows host these names are genuinely invalid", isWindowsHost)
        val fs = FileVaultFs(tmp(), windowsNames = false)
        // a colon is a legal filename character on macOS/Linux/Android
        assertEquals("ab:c", fs.sanitize("ab:c"))
        fs.resolve("ab:c")
        fs.resolve("CON")
    }

    @Test
    fun `a short-name style alias of a hidden entry is refused after canonicalization`() {
        // Needs POSIX symlink canonicalization to stand in for Win32 8.3 expansion; on Windows
        // itself the real mechanism applies and this stand-in does not.
        assumeFalse(isWindowsHost)
        val root = tmp()
        File(root, ".secret").apply { mkdirs() }
        File(root, ".secret/k.txt").writeText("staged upload")
        // Win32 resolves `SECRET~1` to `.secret`; a symlink reproduces "a visible name that
        // canonicalizes into a hidden entry" without needing an NTFS volume.
        Files.createSymbolicLink(File(root, "STAGIN~1").toPath(), File(root, ".secret").toPath())

        expect(403) { FileVaultFs(root, windowsNames = true).resolve("STAGIN~1/k.txt") }
        expect(403) { FileVaultFs(root, windowsNames = true).resolve("STAGIN~1") }
        // the hidden entry itself is still rejected up front, as it always was
        expect(403) { FileVaultFs(root, windowsNames = true).resolve(".secret/k.txt") }
        // non-Windows behaviour unchanged (symlinked shares remain legitimate there)
        FileVaultFs(root, windowsNames = false).resolve("STAGIN~1/k.txt")
    }

    @Test
    fun `a case-only rename is a rename, never same-source-and-destination or a delete`() {
        val root = tmp()
        File(root, "photo.jpg").writeText("pixels")
        // Only a case-insensitive filesystem (NTFS, default APFS) collapses the two names — which is
        // exactly where the bug lived. On a case-sensitive one these are two different files.
        assumeTrue("needs a case-insensitive filesystem", File(root, "PHOTO.JPG").exists())
        val fs = FileVaultFs(root, windowsNames = true)

        fs.move("photo.jpg", "Photo.jpg", overwrite = false)
        assertEquals(listOf("Photo.jpg"), root.list()!!.toList())      // renamed, no temp name left behind
        assertEquals("pixels", File(root, "Photo.jpg").readText())

        // overwrite=true must never delete the very file being renamed
        fs.move("Photo.jpg", "PHOTO.jpg", overwrite = true)
        assertEquals(listOf("PHOTO.jpg"), root.list()!!.toList())
        assertEquals("pixels", File(root, "PHOTO.jpg").readText())

        // the same name again is still "same source and destination"
        expect(400) { fs.move("PHOTO.jpg", "PHOTO.jpg", overwrite = false) }
        assertEquals("pixels", File(root, "PHOTO.jpg").readText())

        // folders too
        File(root, "docs").mkdirs()
        fs.move("docs", "Docs", overwrite = false)
        assertTrue(root.list()!!.toList().contains("Docs"))
        assertFalse(root.list()!!.toList().contains("docs"))
    }

    @Test
    fun `a jail rooted at a filesystem root resolves its children`() {
        // "/" (like "C:\") already ends in the separator; the prefix check used to build "//"
        // and refuse every path. Read-only: nothing is created or written.
        val fs = FileVaultFs(File("/"), windowsNames = false)
        val tmp = fs.resolve("tmp")
        assertTrue(tmp.path.startsWith(fs.root.path))
        expect(403) { fs.resolve("../etc") }
    }
}
