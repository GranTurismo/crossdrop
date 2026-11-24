package com.crossdrop.core.discovery

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * The real [MdnsTransport]: one multicast socket on port 5353 joined to 224.0.0.251 on [iface] only, so
 * queries leave through that interface and only its group traffic is wanted. The port is shared
 * (SO_REUSEADDR) with the system's own mDNS where there is one. A wildcard-bound socket can still be handed
 * datagrams from other interfaces that someone joined the group on; [LinkMdns] drops those by sender address.
 */
class MulticastTransport(private val iface: NetworkInterface) : MdnsTransport {
    private val group = InetAddress.getByName("224.0.0.251")
    private val groupAddress = InetSocketAddress(group, LinkMdns.MDNS_PORT)

    @Volatile private var socket: MulticastSocket? = null
    private var reader: Thread? = null

    override fun open(onPacket: (ByteArray, InetSocketAddress) -> Unit) {
        val s = MulticastSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(LinkMdns.MDNS_PORT))
            s.networkInterface = iface
            s.timeToLive = 255
            s.joinGroup(groupAddress, iface)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        reader = Thread({
            val buf = ByteArray(MdnsCodec.MAX_PACKET)
            val packet = DatagramPacket(buf, buf.size)
            while (!s.isClosed) {
                try {
                    packet.length = buf.size
                    s.receive(packet)
                    val from = packet.socketAddress as? InetSocketAddress ?: continue
                    onPacket(buf.copyOf(packet.length), from)
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "mdns-${iface.name}").apply { isDaemon = true; start() }
    }

    override fun sendMulticast(data: ByteArray) {
        socket?.send(DatagramPacket(data, data.size, groupAddress))
    }

    override fun sendUnicast(data: ByteArray, to: InetSocketAddress) {
        socket?.send(DatagramPacket(data, data.size, to))
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        runCatching { s.leaveGroup(groupAddress, iface) }
        runCatching { s.close() }
    }
}
