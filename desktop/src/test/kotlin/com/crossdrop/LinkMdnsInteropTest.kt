package com.crossdrop

import com.crossdrop.core.Peer
import com.crossdrop.core.discovery.LinkMdns
import com.crossdrop.core.discovery.MdnsAdvert
import com.crossdrop.core.discovery.MulticastTransport
import com.crossdrop.net.LocalIp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * [LinkMdns] against JmDNS, the responder the desktop apps use (the same wire as Bonjour and Android's NSD), on this
 * machine's real network interface. It needs real multicast, so it runs only on request:
 * `JAVA_TOOL_OPTIONS=-Dcrossdrop.mdnsInterop=1 ./gradlew :desktop:test --tests '*LinkMdnsInteropTest*' --rerun -i`.
 */
class LinkMdnsInteropTest {
    private val type = "_crossdrop._tcp.local."

    @Test fun `our mDNS and JmDNS find each other on a real interface`() {
        assumeTrue("needs real multicast; run with -Dcrossdrop.mdnsInterop=1", System.getProperty("crossdrop.mdnsInterop") != null)
        val ip = LocalIp.wifiIpv4()
        assumeNotNull(ip)
        val addr = InetAddress.getByName(ip) as Inet4Address
        val iface = NetworkInterface.getByInetAddress(addr)
        val prefix = iface.interfaceAddresses.first { it.address == addr }.networkPrefixLength.toInt()
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        fun toInt(a: ByteArray) = a.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val onLink = { a: InetAddress -> a is Inet4Address && (toInt(a.address) and mask) == (toInt(addr.address) and mask) }

        val ourId = "1111aaaa2222bbbb"
        val jmId = "9999cccc8888dddd"

        // The other side: JmDNS, registered and browsing exactly as MdnsDiscovery does.
        val resolvedByJm = CopyOnWriteArrayList<ServiceInfo>()
        val jm = JmDNS.create(addr, "interop-jm")
        val mdns: LinkMdns
        var peers: Map<String, Peer> = emptyMap()
        try {
            jm.addServiceListener(type, object : ServiceListener {
                override fun serviceAdded(e: ServiceEvent) { if (e.name.contains(ourId)) e.dns.requestServiceInfo(e.type, e.name, true) }
                override fun serviceRemoved(e: ServiceEvent) {}
                override fun serviceResolved(e: ServiceEvent) { if (e.name.contains(ourId)) resolvedByJm += e.info }
            })
            jm.registerService(
                ServiceInfo.create(type, "crossdrop-$jmId", 51111, 0, 0, mapOf("id" to jmId, "name" to "JmDNS machine", "probe" to "1", "os" to "mac")),
            )

            // Our side: the engine on the same interface, with its own advert.
            mdns = LinkMdns(
                transport = MulticastTransport(iface), local = addr, onLink = onLink,
                advert = { MdnsAdvert(ourId, "Engine under test", 42222, "android") },
                onPeers = { peers = it },
                dropOwnAddress = false,           // JmDNS and the engine share this machine's address
            )
            mdns.start()

            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline && (peers[jmId] == null || resolvedByJm.isEmpty())) Thread.sleep(200)

            // Browser path: the engine found JmDNS's service with everything the radar needs.
            val found = peers[jmId]
            assertNotNull("the engine did not find the JmDNS service; peers=$peers", found)
            assertEquals("JmDNS machine", found!!.name)
            assertEquals(51111, found.endpoint!!.port)
            assertEquals("mac", found.platform)
            assertEquals(true, found.probeable)

            // Responder path: JmDNS resolved the engine's service with its port and text.
            assertNotNull("JmDNS did not resolve the engine's service", resolvedByJm.firstOrNull())
            val info = resolvedByJm.first()
            assertEquals(42222, info.port)
            assertEquals(ourId, info.getPropertyString("id"))
            assertEquals("Engine under test", info.getPropertyString("name"))
            assertEquals("android", info.getPropertyString("os"))
            assertEquals(ip, info.inet4Addresses.firstOrNull()?.hostAddress)
            mdns.stop()
        } finally {
            runCatching { jm.close() }
        }
    }
}
