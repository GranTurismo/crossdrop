package com.crossdrop.core

import com.crossdrop.core.backup.BackupIndex
import com.crossdrop.core.backup.BackupLedger
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.VerifyItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** "My devices", the phone's backup ledger, the computer's backup index and announced peers. */
class BackupStoresTest {

    private fun tmp(): File = Files.createTempDirectory("cd-backup").toFile()

    @Test
    fun `pairings survive a restart and can be removed`() {
        val dir = tmp()
        val f = File(dir, "pairings.json")
        val key = ByteArray(32) { it.toByte() }
        PairingStore(f).add("00112233445566778899aabbccddeeff", "MacBook", key, now = 5)
        val again = PairingStore(f)
        val d = again.devices.value.single()
        assertEquals("MacBook", d.name)
        assertArrayEquals(key, again.secrets().single().key)
        again.seen(d.id, "Gogita's MacBook", now = 9)
        assertEquals("Gogita's MacBook", PairingStore(f).get(d.id)!!.name)
        again.remove(d.id)
        assertTrue(PairingStore(f).devices.value.isEmpty())
        assertEquals(32, PairingStore.newId().length)
        dir.deleteRecursively()
    }

    @Test
    fun `the ledger remembers confirmed items and freed ones, and compacts`() {
        val dir = tmp()
        val f = File(dir, "ledger.log")
        val l = BackupLedger(f)
        l.record(listOf(BackupLedger.Entry("p:1", 10, "aa", 1), BackupLedger.Entry("p:2", 20, "bb", 2)))
        l.markFreed(listOf("p:1", "unknown"), now = 7)
        val again = BackupLedger(f)
        assertTrue(again.has("p:1", 10))
        assertFalse("an edited photo has another size", again.has("p:1", 11))
        assertTrue(again.get("p:1")!!.freed)
        assertFalse(again.get("p:2")!!.freed)
        // backed up again later: no longer counted as freed
        again.record(listOf(BackupLedger.Entry("p:1", 10, "aa", 9)))
        assertFalse(BackupLedger(f).get("p:1")!!.freed)
        again.compact()
        assertEquals(setOf("p:1", "p:2"), BackupLedger(f).all().map { it.key }.toSet())
        // keys that would break the file are refused
        again.record(listOf(BackupLedger.Entry("bad\tkey", 1, "r", 1)))
        assertNull(BackupLedger(f).get("bad\tkey"))
        again.clear()
        assertTrue(BackupLedger(f).all().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `verify re-reads files and drops anything changed, moved or outside the backup`() {
        val dir = tmp()
        val root = File(dir, "Backup").apply { mkdirs() }
        val photo = File(root, "2026/11/IMG_1.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(5_000) { 7 }) }
        val video = File(root, "2026/11/VID_1.mp4").apply { writeBytes(ByteArray(9_000) { 3 }) }
        val gone = File(root, "2026/10/IMG_0.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(10) { 1 }) }
        val outside = File(dir, "secret.txt").apply { writeBytes(ByteArray(4) { 9 }) }
        val index = BackupIndex(File(dir, "index.json"), root)
        fun item(f: File, path: String) = BackupIndex.Item(path, f.length(), BackupIndex.blake3Hex(f), 1)
        index.put(mapOf(
            "p:1" to item(photo, "2026/11/IMG_1.jpg"),
            "v:1" to item(video, "2026/11/VID_1.mp4"),
            "p:0" to item(gone, "2026/10/IMG_0.jpg"),
            "x" to item(outside, "../secret.txt"),
        ))
        gone.delete()
        video.writeBytes(ByteArray(9_000) { 4 })   // same size, other content: edited on the computer

        val again = BackupIndex(File(dir, "index.json"), root)
        val asked = listOf("p:1", "v:1", "p:0", "x").map { k ->
            val it = again.get(k)!!
            VerifyItem(k, it.root, it.size)
        } + VerifyItem("p:9", "00", 1)
        assertEquals(listOf("p:1"), again.verify(asked))
        // the phone's idea of the file must match too
        assertEquals(emptyList<String>(), again.verify(listOf(VerifyItem("p:1", "ff", 5_000))))
        assertEquals("p:1", again.findContent(BackupIndex.blake3Hex(photo), 5_000)?.let { "p:1" })
        dir.deleteRecursively()
    }

    @Test
    fun `announced peers expire, are capped and refuse junk`() {
        var now = 1_000L
        val a = AnnouncedPeers(clock = { now }, ttlMs = 60_000, max = 3)
        a.add("0123456789abcdef", "Pixel", Endpoint("10.0.0.2", 4000))
        a.add("not hex!", "x", Endpoint("10.0.0.3", 4000))
        a.add("0123456789abcdee", "x", Endpoint("10.0.0.3", 0))
        assertEquals(setOf("0123456789abcdef"), a.peers.value.keys)
        val p = a.peers.value.values.single()
        assertTrue(p.probeable)
        assertEquals(Endpoint("10.0.0.2", 4000), p.endpoint)
        for (i in 1..4) { now += 1; a.add("00000000000000%02x".format(i), "n$i", Endpoint("10.0.0.$i", 4000)) }
        assertEquals(3, a.peers.value.size)
        assertFalse("the oldest made room", "0123456789abcdef" in a.peers.value)
        now += 61_000
        a.prune()
        assertTrue(a.peers.value.isEmpty())
    }
}
