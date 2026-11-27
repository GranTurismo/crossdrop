package com.crossdrop.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best IPv4 to put inside a QR share URL: the Wi-Fi interface's address,
 * falling back to any site-local address (hotspot, USB tether) — never
 * loopback or carrier-grade NAT space.
 */
object LocalIp {
    /**
     * This phone's address on the network it made for "Connect directly", while that is on. It wins: the person
     * sharing is most likely sharing with whoever joined it, and on a Wi-Fi Direct group the phone's own Wi-Fi
     * (wlan0) usually stays connected too, which would otherwise be picked.
     */
    @Volatile var directLinkAddress: String? = null

    /**
     * [preferDirectLink]: "Send to anyone" (short-lived, most likely for whoever joined the link) says yes; the Wireless
     * Drive (its address is shown once and outlives the link) says no.
     */
    fun wifiIpv4(preferDirectLink: Boolean = true): String? {
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList().map { i.name to it } }
                .filter { (_, a) -> a is Inet4Address && !a.isLoopbackAddress }
        }.getOrDefault(emptyList())

        if (preferDirectLink) {
            directLinkAddress?.let { preferred -> if (all.any { (_, a) -> a.hostAddress == preferred }) return preferred }
        }

        fun pick(pred: (String, Inet4Address) -> Boolean) =
            all.firstOrNull { (n, a) -> pred(n, a as Inet4Address) }?.second?.hostAddress

        return pick { n, _ -> n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") }
            ?: pick { _, a -> a.isSiteLocalAddress }
            ?: pick { _, _ -> true }
    }
}
