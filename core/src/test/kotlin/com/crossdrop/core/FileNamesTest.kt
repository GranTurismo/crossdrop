package com.crossdrop.core

import com.crossdrop.core.protocol.FileNames
import com.crossdrop.core.protocol.NameClaims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun `names in every script keep their letters, so different names stay different`() {
        for (n in listOf("ბაღი.jpg", "ზღვა.jpg", "मेरी फ़ाइल.pdf", "تقرير 2026.docx", "گزارش.txt", "Мама.jpg", "Папа.jpg", "日本語.txt", "résumé.pdf", "🎉 party.png")) {
            assertEquals(n, FileNames.sanitize(n))
        }
        // these two used to come out as the same "____.jpg"
        assertNotEquals(FileNames.sanitize("ბაღი.jpg"), FileNames.sanitize("ზღვა.jpg"))
    }

    @Test fun `an NFD name from a Mac and its NFC twin are one name`() {
        assertEquals(FileNames.sanitize("é.txt"), FileNames.sanitize("e\u0301.txt"))
    }

    @Test fun `what is unsafe never survives`() {
        assertEquals("evil.txt", FileNames.sanitize("../../evil.txt"))
        assertEquals("a.txt", FileNames.sanitize("C:\\Users\\x\\a.txt"))
        assertEquals("doc_stream.txt", FileNames.sanitize("doc:stream.txt"))
        assertEquals("a_b_c_d_e.txt", FileNames.sanitize("a*b?c<d>e.txt"))
        assertEquals("file", FileNames.sanitize("..."))
        assertEquals("file", FileNames.sanitize(""))
        assertEquals("report.pdf", FileNames.sanitize("report.pdf. "))
        // an invisible right-to-left override can't disguise "txt.exe" as "exe.txt"
        val spoof = FileNames.sanitize("invoice\u202Etxt.exe")
        assertFalse(spoof, spoof.contains('\u202E'))
        assertEquals("a_b.txt", FileNames.sanitize("a\u0000b.txt"))
    }

    @Test fun `a long name is cut by bytes, not just characters, and not through a surrogate pair`() {
        val georgian = "ა".repeat(90) + ".txt"                       // 3 bytes each: 270+ bytes
        val s = FileNames.sanitize(georgian)
        assertTrue("${s.toByteArray().size} bytes", s.toByteArray().size <= FileNames.MAX_BYTES)
        assertTrue(s.length <= FileNames.MAX_CHARS)
        val emoji = FileNames.sanitize("🎉".repeat(80))
        assertTrue(emoji.toByteArray().size <= FileNames.MAX_BYTES)
        assertFalse("no lone surrogate", emoji.any { Character.isHighSurrogate(it) && emoji.indexOf(it) == emoji.lastIndex })
        assertEquals("a".repeat(95), FileNames.sanitize("a".repeat(95) + ".b"))
    }

    @Test fun `two originals that clean to the same place do not share it`() {
        val claims = NameClaims()
        val a = claims.claim("dir\u0000a:b.txt", "dir/a_b.txt")
        val b = claims.claim("dir\u0000a_b.txt", "dir/a_b.txt")
        assertEquals("dir/a_b.txt", a)
        assertEquals("dir/a_b (1).txt", b)
        // the same original always gets the same place back (its baseline for the next transfer)
        assertEquals(a, claims.claim("dir\u0000a:b.txt", "dir/a_b.txt"))
        assertEquals(b, claims.claim("dir\u0000a_b.txt", "dir/a_b.txt"))
        // a place the person's own file occupies is never given out
        assertEquals("c (1)", claims.claim("c", "c") { it == "c" })
    }
}
