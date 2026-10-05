package com.crossdrop.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best IPv4 to put inside a QR share URL: the Wi-Fi interface's address,
 * falling back to any site-local address (hotspot, USB tether) — never
 * loopback or carrier-grade NAT space.
 */
object LocalIp {
    fun wifiIpv4(): String? {
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList().map { i.name to it } }
                .filter { (_, a) -> a is Inet4Address && !a.isLoopbackAddress }
        }.getOrDefault(emptyList())

        fun pick(pred: (String, Inet4Address) -> Boolean) =
            all.firstOrNull { (n, a) -> pred(n, a as Inet4Address) }?.second?.hostAddress

        return pick { n, _ -> n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") }
            ?: pick { _, a -> a.isSiteLocalAddress }
            ?: pick { _, _ -> true }
    }
}
