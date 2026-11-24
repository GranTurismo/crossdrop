package com.crossdrop.core

import com.crossdrop.core.discovery.ARecord
import com.crossdrop.core.discovery.LinkMdns
import com.crossdrop.core.discovery.MdnsAdvert
import com.crossdrop.core.discovery.MdnsCodec
import com.crossdrop.core.discovery.MdnsQuestion
import com.crossdrop.core.discovery.MdnsTransport
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
import java.net.InetSocketAddress

/** [LinkMdns] on an in-memory network segment: two or three devices, a fake clock, no sockets and no threads. */
class LinkMdnsTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    /** One shared segment: everything a node multicasts reaches every node, itself included (as multicast loopback does). */
    private class Segment {
        val nodes = mutableListOf<Node>()
        inner class Node(val address: Inet4Address) : MdnsTransport {
            var rx: ((ByteArray, InetSocketAddress) -> Unit)? = null
            val unicastsSent = mutableListOf<Pair<ByteArray, InetSocketAddress>>()
            val multicastsSent = mutableListOf<ByteArray>()
            init { nodes += this }
            override fun open(onPacket: (ByteArray, InetSocketAddress) -> Unit) { rx = onPacket }
            override fun sendMulticast(data: ByteArray) {
                multicastsSent += data
                nodes.forEach { it.rx?.invoke(data, InetSocketAddress(address, 5353)) }
            }
            override fun sendUnicast(data: ByteArray, to: InetSocketAddress) {
                unicastsSent += data to to
                nodes.firstOrNull { it.address == to.address }?.rx?.invoke(data, InetSocketAddress(address, 5353))
            }
            override fun close() { rx = null }
        }
    }

    private class Device(
        seg: Segment, val addr: String, private val advert: MdnsAdvert?, val clock: Clock, subnet: String = "10.160.58.",
    ) {
        class Clock { var now = 1_000_000L }
        val node = seg.Node(InetAddress.getByName(addr) as Inet4Address)
        var peers: Map<String, Peer> = emptyMap()
        val mdns = LinkMdns(
            transport = node,
            local = node.address,
            onLink = { it.hostAddress!!.startsWith(subnet) },
            advert = { advert },
            onPeers = { peers = it },
            clock = { clock.now },
        )
        fun start() = mdns.start(autoTick = false)
    }

    private fun advert(id: String, name: String, port: Int, os: String) = MdnsAdvert(id, name, port, os)

    /** Advance the shared clock in half-second steps, ticking every device each time, like the real timer. */
    private fun run(seconds: Int, clock: Device.Clock, vararg devices: Device) {
        repeat(seconds * 2) {
            clock.now += 500
            devices.forEach { it.mdns.tick() }
        }
    }

    @Test fun `two devices on a link find each other with name, address, port and platform`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        val mac = Device(seg, "10.160.58.120", advert("bbbb444455556666", "MacBook", 58999, "mac"), clock)
        phone.start(); mac.start()
        run(3, clock, phone, mac)

        val seenByPhone = phone.peers["bbbb444455556666"]
        assertNotNull(seenByPhone)
        assertEquals("MacBook", seenByPhone!!.name)
        assertEquals(Endpoint("10.160.58.120", 58999), seenByPhone.endpoint)
        assertEquals("mac", seenByPhone.platform)
        assertTrue(seenByPhone.probeable)
        assertEquals(setOf(DiscoverySource.NSD), seenByPhone.sources)
        assertEquals(Endpoint("10.160.58.57", 40001), mac.peers["aaaa111122223333"]!!.endpoint)
    }

    @Test fun `a device never lists itself`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        run(5, clock, phone)
        assertTrue(phone.peers.isEmpty())
    }

    @Test fun `a device that leaves says goodbye and disappears at once`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        val mac = Device(seg, "10.160.58.120", advert("bbbb444455556666", "MacBook", 58999, "mac"), clock)
        phone.start(); mac.start()
        run(3, clock, phone, mac)
        assertTrue(phone.peers.containsKey("bbbb444455556666"))
        mac.mdns.stop()
        assertTrue(phone.peers.isEmpty())
    }

    @Test fun `a device that just vanishes is dropped once it has been silent too long`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        val mac = Device(seg, "10.160.58.120", advert("bbbb444455556666", "MacBook", 58999, "mac"), clock)
        phone.start(); mac.start()
        run(3, clock, phone, mac)
        assertTrue(phone.peers.containsKey("bbbb444455556666"))
        seg.nodes.remove(mac.node)             // gone without a word (lid shut, Wi-Fi off)
        run(120, clock, phone)
        assertTrue(phone.peers.isEmpty())
    }

    @Test fun `a peer that keeps answering our queries stays`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        val mac = Device(seg, "10.160.58.120", advert("bbbb444455556666", "MacBook", 58999, "mac"), clock)
        phone.start(); mac.start()
        run(300, clock, phone, mac)
        assertTrue(phone.peers.containsKey("bbbb444455556666"))
    }

    @Test fun `packets from outside this interface's subnet are ignored`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        // The same segment object stands in for a wildcard socket that also hears the phone's other Wi-Fi.
        val other = Device(seg, "10.119.40.30", advert("cccc777788889999", "Elsewhere", 1234, "mac"), clock, subnet = "10.119.40.")
        phone.start(); other.start()
        run(3, clock, phone, other)
        assertNull(phone.peers["cccc777788889999"])
    }

    @Test fun `a legacy resolver asking from its own port gets a direct answer carrying its query id`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        val query = MdnsCodec.encodeQuery(listOf(MdnsQuestion(LinkMdns.SERVICE, MdnsCodec.TYPE_PTR))).also { it[0] = 0x12; it[1] = 0x34 }
        phone.mdns.handle(query, InetSocketAddress(ip("10.160.58.120"), 40000))
        val (bytes, to) = phone.node.unicastsSent.single()
        assertEquals(InetSocketAddress(ip("10.160.58.120"), 40000), to)
        val m = MdnsCodec.decode(bytes)!!
        assertEquals(0x1234, m.id)
        assertTrue(m.records.any { it is PtrRecord && it.target == "crossdrop-aaaa111122223333._crossdrop._tcp.local" })
        assertTrue(m.records.any { it is SrvRecord && it.port == 40001 })
        assertTrue(m.records.any { it is TxtRecord && it.entries["id"] == "aaaa111122223333" && it.entries["os"] == "android" })
        assertTrue(m.records.any { it is ARecord && it.address == ip("10.160.58.57") })
    }

    @Test fun `a question for our service from a normal mDNS port is answered on the multicast group`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        val before = phone.node.multicastsSent.size
        phone.mdns.handle(MdnsCodec.encodeQuery(listOf(MdnsQuestion(LinkMdns.SERVICE, MdnsCodec.TYPE_PTR))), InetSocketAddress(ip("10.160.58.120"), 5353))
        assertEquals(before + 1, phone.node.multicastsSent.size)
        assertTrue(phone.node.unicastsSent.isEmpty())
    }

    @Test fun `a asker that already holds a fresh copy of our record gets nothing`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        // Hand-build a query that carries our own PTR as a known answer.
        val q = MdnsCodec.encodeQuery(listOf(MdnsQuestion(LinkMdns.SERVICE, MdnsCodec.TYPE_PTR)))
        val known = MdnsCodec.encodeResponse(listOf(PtrRecord(LinkMdns.SERVICE, 4000, "crossdrop-aaaa111122223333._crossdrop._tcp.local")))
        val withKnown = q.copyOf().also { it[7] = 1 } + known.copyOfRange(12, known.size)      // answer count 1 + the record
        val before = phone.node.multicastsSent.size
        phone.mdns.handle(withKnown, InetSocketAddress(ip("10.160.58.120"), 5353))
        assertEquals(before, phone.node.multicastsSent.size)
    }

    @Test fun `with no listener port yet there is nothing to say and nobody is answered`() {
        val seg = Segment(); val clock = Device.Clock()
        val quiet = Device(seg, "10.160.58.57", null, clock)
        quiet.start()
        quiet.mdns.handle(MdnsCodec.encodeQuery(listOf(MdnsQuestion(LinkMdns.SERVICE, MdnsCodec.TYPE_PTR))), InetSocketAddress(ip("10.160.58.120"), 5353))
        run(5, clock, quiet)
        // It still browses (queries), but never announces or answers with a record of its own.
        assertTrue(quiet.node.multicastsSent.all { MdnsCodec.decode(it)!!.records.isEmpty() })
    }

    @Test fun `a device heard only through its service pointer is completed by asking for the rest`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        val instance = "crossdrop-bbbb444455556666._crossdrop._tcp.local"
        val before = phone.node.multicastsSent.size
        phone.mdns.handle(MdnsCodec.encodeResponse(listOf(PtrRecord(LinkMdns.SERVICE, 4500, instance))), InetSocketAddress(ip("10.160.58.120"), 5353))
        assertTrue(phone.peers.isEmpty())                       // no port yet
        val ask = MdnsCodec.decode(phone.node.multicastsSent[before])!!
        assertEquals(listOf(MdnsQuestion(instance, MdnsCodec.TYPE_ANY)), ask.questions)
        phone.mdns.handle(
            MdnsCodec.encodeResponse(
                listOf(SrvRecord(instance, 120, 58999, "mac.local"), TxtRecord(instance, 120, mapOf("id" to "bbbb444455556666", "name" to "MacBook", "probe" to "1"))),
                listOf(ARecord("mac.local", 120, ip("10.160.58.120"))),
            ),
            InetSocketAddress(ip("10.160.58.120"), 5353),
        )
        assertEquals(Endpoint("10.160.58.120", 58999), phone.peers["bbbb444455556666"]!!.endpoint)
    }

    @Test fun `garbage on the group is ignored`() {
        val seg = Segment(); val clock = Device.Clock()
        val phone = Device(seg, "10.160.58.57", advert("aaaa111122223333", "Honor", 40001, "android"), clock)
        phone.start()
        phone.mdns.handle(ByteArray(40) { it.toByte() }, InetSocketAddress(ip("10.160.58.120"), 5353))
        phone.mdns.handle(ByteArray(3), InetSocketAddress(ip("10.160.58.120"), 5353))
        assertTrue(phone.peers.isEmpty())
    }

    @Test fun `a name or port change is announced again`() {
        val seg = Segment(); val clock = Device.Clock()
        var current = advert("aaaa111122223333", "Honor", 0, "android")
        val node = seg.Node(ip("10.160.58.57"))
        val mdns = LinkMdns(node, node.address, { true }, { current.takeIf { it.port != 0 } }, { }, { clock.now })
        mdns.start(autoTick = false)
        run(3, clock, Device(seg, "10.160.58.99", null, clock).also { it.start() }.let { _ -> Device(seg, "10.160.58.98", null, clock) })
        val quiet = node.multicastsSent.count { MdnsCodec.decode(it)!!.records.isNotEmpty() }
        assertEquals(0, quiet)
        current = advert("aaaa111122223333", "Honor", 40001, "android")
        repeat(4) { clock.now += 500; mdns.tick() }
        val announced = node.multicastsSent.filter { MdnsCodec.decode(it)!!.records.isNotEmpty() }
        assertTrue(announced.size >= 2)
        assertTrue(announced.all { m -> MdnsCodec.decode(m)!!.records.any { it is SrvRecord && it.port == 40001 } })
    }
}
