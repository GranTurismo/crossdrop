package com.crossdrop.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best IPv4 to advertise and to put inside QR/drive URLs. On a Mac that is the
 * Wi-Fi or Ethernet interface (`en*`) — never loopback, link-local, or the
 * virtual interfaces (VPN tunnels, AirDrop `awdl`, VM bridges, Docker).
 */
object LocalIp {
    private val virtualPrefixes = listOf(
        "utun", "awdl", "llw", "bridge", "vmnet", "vboxnet", "docker", "veth",
        "gif", "stf", "ap", "anpi", "ipsec", "ppp", "tun", "tap",
    )

    fun wifiIpv4(): String? {
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filter { i -> virtualPrefixes.none { i.name.startsWith(it) } }
                .flatMap { i -> i.inetAddresses.toList().map { i.name to it } }
                .filter { (_, a) -> a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress }
        }.getOrDefault(emptyList())

        fun pick(pred: (String, Inet4Address) -> Boolean) =
            all.firstOrNull { (n, a) -> pred(n, a as Inet4Address) }?.second?.hostAddress

        return pick { n, a -> (n.startsWith("en") || n.startsWith("wlan") || n.startsWith("eth")) && a.isSiteLocalAddress }
            ?: pick { _, a -> a.isSiteLocalAddress }
            ?: pick { _, _ -> true }
    }
}
