package com.crossdrop.core

import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultFs.VaultError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** MOVE/COPY with overwrite must never delete the vault root or an ancestor of the source. */
class VaultFsGuardTest {

    private fun fs(): Pair<FileVaultFs, File> {
        val root = Files.createTempDirectory("vfs-guard").toFile().canonicalFile
        File(root, "docs/a").mkdirs()
        File(root, "docs/a/b.txt").writeText("keep me")
        File(root, "top.txt").writeText("top")
        return FileVaultFs(root) to root
    }

    private fun expect(code: Int, block: () -> Unit) {
        try { block(); fail("expected VaultError $code") }
        catch (e: VaultError) { assertEquals(code, e.code) }
    }

    @Test
    fun `move onto root is refused and nothing is deleted`() {
        val (vfs, root) = fs()
        expect(403) { vfs.move("/docs/a/b.txt", "/", true) }
        assertTrue(File(root, "docs/a/b.txt").exists())
        assertTrue(File(root, "top.txt").exists())
    }

    @Test
    fun `copy onto root is refused`() {
        val (vfs, root) = fs()
        expect(403) { vfs.copy("/top.txt", "/", true) }
        assertTrue(File(root, "docs/a/b.txt").exists())
    }

    @Test
    fun `move or copy onto an ancestor of the source is refused`() {
        val (vfs, root) = fs()
        expect(409) { vfs.move("/docs/a/b.txt", "/docs", true) }
        expect(409) { vfs.copy("/docs/a/b.txt", "/docs", true) }
        expect(409) { vfs.move("/docs/a", "/docs", true) }
        assertTrue(File(root, "docs/a/b.txt").exists())
    }
}
