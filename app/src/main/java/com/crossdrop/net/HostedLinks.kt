package com.crossdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The network interfaces this phone *hosts*: its own Wi-Fi hotspot, Wi-Fi Direct group, USB or Bluetooth tethering.
 * Devices that join them are our guests; the phone is their router.
 *
 * The system's mDNS service does not run on these (see [com.crossdrop.core.discovery.LinkMdns]), so CrossDrop
 * runs its own there. An interface qualifies when it is up, can multicast, has a private IPv4 address, is named
 * like a tethering interface, and is not one of the networks this phone joined as a client (those are listed by
 * `ConnectivityManager`; on Android 15+ the hotspot is listed too, marked LOCAL_NETWORK, and stays eligible).
 */
class HostedLinks(context: Context) {
    data class Link(val iface: NetworkInterface, val address: Inet4Address, val prefix: Int) {
        val key get() = "${iface.name}/${address.hostAddress}/$prefix"
    }

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun find(): List<Link> {
        val client = runCatching {
            cm.allNetworks.filter { n ->
                Build.VERSION.SDK_INT < 35 ||
                    cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK) != true
            }.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        }.getOrDefault(emptySet())
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.name !in client && HOSTED_NAME.matches(it.name) }
                .flatMap { i ->
                    i.interfaceAddresses
                        .filter { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
                        .map { Link(i, it.address as Inet4Address, it.networkPrefixLength.toInt()) }
                }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** Android's own tethering interface patterns (wlan/softap/ap, Wi-Fi Direct, USB, Bluetooth) plus Samsung's swlan. */
        val HOSTED_NAME = Regex("^(wlan\\d+|softap\\d+|swlan\\d+|ap\\d+|ap_br_.+|p2p-.+|p2p\\d+|rndis\\d+|usb\\d+|bt-pan|eth\\d+)$")
    }
}
