package com.crossdrop.core

import com.crossdrop.core.delta.FileDeltaStore
import com.crossdrop.core.protocol.FileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileDeltaStoreTest {
    private fun tmp() = Files.createTempDirectory("fds").toFile()
    private fun rel(m: FileMeta) = (m.dir?.let { "$it/" } ?: "") + m.name

    @Test fun `a file counts as ours only while it is exactly what we assembled`() {
        val inbox = tmp(); val state = tmp()
        val store = FileDeltaStore(inbox, state, ::rel)
        val mine = File(inbox, "report.pdf").apply { writeText("assembled by us") }
        assertFalse("no index: it is just sitting there", store.knows(mine))

        val ix = store.chunkFile(mine)
        store.publishIndex(mine, ix.chunks, ix.root)
        assertTrue(store.knows(mine))

        mine.writeText("edited by the person afterwards, so longer")
        assertFalse("changed since: not ours to replace", store.knows(mine))

        // an index left behind by a deleted file does not vouch for a different file of the same name
        mine.writeText("assembled by us"); mine.setLastModified(1_000_000_000_000L)
        store.publishIndex(mine, ix.chunks, ix.root)
        mine.delete()
        val theirs = File(inbox, "report.pdf").apply { writeText("the person's own report") }
        assertFalse(store.knows(theirs))
        store.forget(theirs)
        assertFalse(store.knows(theirs))
    }

    @Test fun `a deep folder gets an index with a name the file system accepts`() {
        val inbox = tmp(); val state = tmp()
        val store = FileDeltaStore(inbox, state, ::rel)
        val deep = (1..8).joinToString("/") { "d".repeat(90) + it }           // ~730 bytes joined: far past the 255 a file name may have
        val meta = FileMeta("file.bin", 4, dir = deep)
        val target = store.targetFor(meta).apply { writeText("data") }
        val ix = store.chunkFile(target)
        store.publishIndex(target, ix.chunks, ix.root)
        assertTrue("indexed, however long the path", store.knows(target))
        assertTrue(File(state, ".idx").list()!!.all { it.toByteArray().size <= 255 })
    }

    @Test fun `asking whether a push is possible creates nothing`() {
        val inbox = File(tmp(), "inbox-not-yet"); val state = tmp()
        val store = FileDeltaStore(inbox, state, ::rel)
        val offer = (1..50).map { FileMeta("f$it", 1, dir = "A/B$it/C") }
        assertTrue(store.canPush(offer))
        assertFalse("no folders were made just by asking", File(inbox, "A").exists())
        // …but writing one does
        store.targetFor(offer[0])
        assertTrue(File(inbox, "A/B1/C").isDirectory)
        assertEquals(1, File(inbox, "A").list()!!.size)
    }
}
