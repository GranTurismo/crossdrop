package com.crossdrop.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best IPv4 to advertise and to put inside QR/drive URLs: the Wi-Fi or Ethernet
 * interface — never loopback, link-local, or the virtual interfaces (VPN
 * tunnels, AirDrop `awdl`, VM bridges, Docker, Hyper-V/WSL switches).
 *
 * Interface naming differs per OS, so both the interface name (`en0`, `wlan0`,
 * `eth3`) and its display name (empty on macOS/Linux; "Intel(R) Wi-Fi 6 AX201",
 * "Hyper-V Virtual Ethernet Adapter" on Windows) are consulted.
 */
object LocalIp {
    /** One usable-looking address on one interface, decoupled from `java.net` for testing. */
    data class Candidate(val name: String, val displayName: String, val address: Inet4Address)

    private val virtualPrefixes = listOf(
        "utun", "awdl", "llw", "bridge", "vmnet", "vboxnet", "docker", "veth",
        "gif", "stf", "ap", "anpi", "ipsec", "ppp", "tun", "tap",
    )

    /**
     * Substrings of Windows adapter descriptions that mark non-LAN adapters.
     * "virtual" already covers the *host-side* Hyper-V / VMware / VirtualBox
     * adapters; the guest-side NIC inside a VM ("Microsoft Hyper-V Network
     * Adapter", "vmxnet3 Ethernet Adapter") is deliberately NOT matched, or
     * CrossDrop would see no network at all inside a Windows VM.
     */
    private val virtualDisplayHints = listOf(
        "virtual", "vethernet", "wsl", "docker",
        "tap-", "tunnel", "wireguard", "vpn", "tailscale", "zerotier", "hamachi",
        "loopback", "bluetooth", "pseudo", "teredo", "isatap", "miniport", "npcap",
    )

    private val lanNamePrefixes = listOf("en", "wlan", "eth", "wireless", "ethernet")
    private val lanDisplayHints = listOf("wi-fi", "wifi", "wireless", "802.11", "ethernet", "gbe", "lan")

    internal fun isVirtual(name: String, displayName: String): Boolean {
        val n = name.lowercase()
        if (virtualPrefixes.any { n.startsWith(it) }) return true
        val d = displayName.lowercase()
        return virtualDisplayHints.any { it in d }
    }

    private fun looksLikeLan(c: Candidate): Boolean {
        val n = c.name.lowercase()
        val d = c.displayName.lowercase()
        return lanNamePrefixes.any { n.startsWith(it) } || lanDisplayHints.any { it in d }
    }

    /** Pick the address to advertise; preference order is LAN-looking + private range, any private range, anything. */
    internal fun choose(candidates: List<Candidate>): String? {
        val real = candidates.filterNot { isVirtual(it.name, it.displayName) }
            .filter { !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
        return (real.firstOrNull { looksLikeLan(it) && it.address.isSiteLocalAddress }
            ?: real.firstOrNull { it.address.isSiteLocalAddress }
            ?: real.firstOrNull())?.address?.hostAddress
    }

    fun wifiIpv4(): String? {
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { i ->
                    i.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .map { Candidate(i.name, i.displayName.orEmpty(), it) }
                }
        }.getOrDefault(emptyList())
        return choose(all)
    }
}
