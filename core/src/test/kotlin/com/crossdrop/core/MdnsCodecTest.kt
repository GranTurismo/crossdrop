package com.crossdrop.core

import com.crossdrop.core.discovery.ARecord
import com.crossdrop.core.discovery.MdnsCodec
import com.crossdrop.core.discovery.MdnsQuestion
import com.crossdrop.core.discovery.PtrRecord
import com.crossdrop.core.discovery.SrvRecord
import com.crossdrop.core.discovery.TxtRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Random

class MdnsCodecTest {
    private val ip = InetAddress.getByName("10.160.58.57") as Inet4Address
    private val service = "_crossdrop._tcp.local"
    private val instance = "crossdrop-7aa77eaf8778089b.$service"

    @Test fun `a query survives the round trip`() {
        val m = MdnsCodec.decode(MdnsCodec.encodeQuery(listOf(MdnsQuestion(service, MdnsCodec.TYPE_PTR), MdnsQuestion(instance, MdnsCodec.TYPE_ANY, unicast = true))))!!
        assertTrue(!m.response)
        assertEquals(listOf(MdnsQuestion(service, 12), MdnsQuestion(instance, 255, true)), m.questions)
    }

    @Test fun `a response with all four record kinds survives the round trip`() {
        val bytes = MdnsCodec.encodeResponse(
            answers = listOf(PtrRecord(service, 4500, instance)),
            additionals = listOf(
                SrvRecord(instance, 120, 41234, "crossdrop-7aa77eaf.local"),
                TxtRecord(instance, 120, linkedMapOf("id" to "7aa77eaf8778089b", "name" to "Gogita's phone ✓", "probe" to "1", "os" to "android")),
                ARecord("crossdrop-7aa77eaf.local", 120, ip),
            ),
            id = 77,
        )
        val m = MdnsCodec.decode(bytes)!!
        assertTrue(m.response)
        assertEquals(77, m.id)
        assertEquals(
            listOf(
                PtrRecord(service, 4500, instance),
                SrvRecord(instance, 120, 41234, "crossdrop-7aa77eaf.local"),
                TxtRecord(instance, 120, mapOf("id" to "7aa77eaf8778089b", "name" to "Gogita's phone ✓", "probe" to "1", "os" to "android")),
                ARecord("crossdrop-7aa77eaf.local", 120, ip),
            ),
            m.records,
        )
    }

    @Test fun `a goodbye keeps its zero ttl`() {
        val m = MdnsCodec.decode(MdnsCodec.encodeResponse(listOf(PtrRecord(service, 0, instance))))!!
        assertEquals(0L, (m.records.single() as PtrRecord).ttl)
    }

    @Test fun `compressed names, as other responders write them, are followed`() {
        // Header; one answer whose own name is "_crossdrop._tcp.local" written in full at offset 12;
        // its PTR target is the label "crossdrop-ab" followed by a pointer back to offset 12.
        val full = byteArrayOf(10) + "_crossdrop".toByteArray() + byteArrayOf(4) + "_tcp".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        val target = byteArrayOf(12) + "crossdrop-ab".toByteArray() + byteArrayOf(0xC0.toByte(), 12)
        val header = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0)
        val fixed = byteArrayOf(0, 12, 0, 1, 0, 0, 0x11, 0x94.toByte(), 0, target.size.toByte())     // type PTR, class IN, ttl 4500, rdlength
        val m = MdnsCodec.decode(header + full + fixed + target)!!
        assertEquals(PtrRecord("_crossdrop._tcp.local", 4500, "crossdrop-ab._crossdrop._tcp.local"), m.records.single())
    }

    @Test fun `a name that points at itself is refused, not followed forever`() {
        val header = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0)
        assertNull(MdnsCodec.decode(header + byteArrayOf(0xC0.toByte(), 12, 0, 12, 0, 1)))
    }

    @Test fun `unknown record types and other classes are skipped without breaking the rest`() {
        val bytes = MdnsCodec.encodeResponse(listOf(PtrRecord(service, 100, instance)))
        // Tack on an AAAA record (type 28) after the PTR and bump the answer count.
        val aaaa = byteArrayOf(0) + byteArrayOf(0, 28, 0, 1, 0, 0, 0, 120, 0, 16) + ByteArray(16)
        val patched = (bytes + aaaa).also { it[7] = 2 }
        val m = MdnsCodec.decode(patched)!!
        assertEquals(1, m.records.size)
    }

    @Test fun `every truncation of a valid packet is handled without an exception`() {
        val bytes = MdnsCodec.encodeResponse(
            listOf(PtrRecord(service, 4500, instance)),
            listOf(SrvRecord(instance, 120, 1, "h.local"), TxtRecord(instance, 120, mapOf("id" to "x")), ARecord("h.local", 120, ip)),
        )
        for (n in 0 until bytes.size) MdnsCodec.decode(bytes.copyOf(n))        // null or a message; never throws
        assertNotNull(MdnsCodec.decode(bytes))
    }

    @Test fun `random bytes and bit-flipped packets never throw`() {
        val rnd = Random(42)
        val valid = MdnsCodec.encodeResponse(listOf(PtrRecord(service, 4500, instance)), listOf(TxtRecord(instance, 120, mapOf("id" to "x", "name" to "y"))))
        repeat(5_000) {
            val junk = ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) }
            MdnsCodec.decode(junk)
            val flipped = valid.copyOf().also { b -> repeat(1 + rnd.nextInt(4)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() } }
            MdnsCodec.decode(flipped)
        }
    }

    @Test fun `a packet claiming an absurd number of records is refused`() {
        assertNull(MdnsCodec.decode(byteArrayOf(0, 0, 0x84.toByte(), 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)))
    }

    @Test fun `a text record entry longer than the limit is cut, not corrupted`() {
        val long = "x".repeat(400)
        val m = MdnsCodec.decode(MdnsCodec.encodeResponse(listOf(TxtRecord(instance, 120, mapOf("name" to long)))))!!
        val v = (m.records.single() as TxtRecord).entries["name"]!!
        assertTrue(v.length in 200..255)
    }
}
