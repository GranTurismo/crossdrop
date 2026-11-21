package com.crossdrop.core

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Folders
import com.crossdrop.core.protocol.decode
import com.crossdrop.core.protocol.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    private val clean: (String) -> String = { it.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').ifBlank { "_" } }

    @Test fun `a file in a folder keeps its place, a loose file does not gain one`() {
        assertEquals("Trip/Day 1/a.jpg", Folders.relativePath(FileMeta("a.jpg", 1, dir = "Trip/Day 1"), clean))
        assertEquals("a.jpg", Folders.relativePath(FileMeta("a.jpg", 1), clean))
        assertEquals("a.jpg", Folders.relativePath(FileMeta("a.jpg", 1, dir = ""), clean))
    }

    @Test fun `every segment is cleaned so nothing climbs out`() {
        val p = Folders.relativePath(FileMeta("x.txt", 1, dir = "../../etc\\..\\/abs//./y"), clean)
        val parts = p.split('/')
        assertTrue(parts.none { it == ".." || it == "." || it.isEmpty() })
        assertEquals("x.txt", parts.last())
    }

    @Test fun `nesting is cut at the depth limit instead of refused`() {
        val deep = (1..40).joinToString("/") { "d$it" }
        val parts = Folders.relativePath(FileMeta("f", 1, dir = deep), clean).split('/')
        assertEquals(Folders.MAX_DEPTH + 1, parts.size)
        assertEquals("f", parts.last())
    }

    @Test fun `the top folder is only named when every file shares it`() {
        fun f(dir: String?) = FileMeta("a", 1, dir = dir)
        assertEquals("Trip", Folders.topFolder(listOf(f("Trip"), f("Trip/Day 1"))))
        assertNull(Folders.topFolder(listOf(f("Trip"), f(null))))
        assertNull(Folders.topFolder(listOf(f("Trip"), f("Other"))))
        assertNull(Folders.topFolder(emptyList()))
    }

    @Test fun `the folder field is optional on the wire, so older peers lose nothing but the layout`() {
        val withDir = FileMeta("a.jpg", 5, dir = "Trip/Day 1").encode().toString(Charsets.UTF_8)
        assertTrue("\"dir\":\"Trip/Day 1\"" in withDir)
        // an older sender's JSON has no such key
        val old = """{"name":"a.jpg","size":5}""".toByteArray().decode<FileMeta>()
        assertNull(old.dir)
        // …and an older receiver's decoder (ignoreUnknownKeys) skips the key it does not know
        assertEquals("a.jpg", withDir.toByteArray().decode<FileMeta>().name)
    }

    @Test fun `junk names stay behind and dirOf joins the picked folder to the path below it`() {
        assertTrue(Folders.isJunk(".DS_Store") && Folders.isJunk("Thumbs.db") && Folders.isJunk("desktop.ini"))
        assertEquals("Trip", Folders.dirOf("Trip", ""))
        assertEquals("Trip/Day 1/Beach", Folders.dirOf("Trip", "Day 1/Beach"))
    }
}
