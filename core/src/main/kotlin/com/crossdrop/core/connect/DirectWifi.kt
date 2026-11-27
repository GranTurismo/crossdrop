package com.crossdrop.core.connect

import com.crossdrop.core.Endpoint

/**
 * The CrossDrop Wi-Fi: the network an Android phone makes for "Connect directly" (a Wi-Fi Direct group). Its name
 * and password are the same on every phone, so another device running CrossDrop joins it in one tap without a code,
 * and a laptop without CrossDrop joins it by picking the name and typing a short password.
 *
 * The password is public (it is in this open-source code); it only keeps the network from being open. What crosses
 * it is protected the usual way: transfers are end-to-end encrypted, each offer is accepted on the receiving device,
 * and the network only lives while someone is connecting (`DirectLink` stops it after 30 minutes at most).
 *
 * The rules here are shared by every platform (Swift twin: `CrossDropCore/DirectWifi.swift`); keep them in step.
 */
object DirectWifi {
    /** Android requires a Wi-Fi Direct network's name to start with "DIRECT-" and two letters or digits. */
    const val SSID = "DIRECT-CD-CrossDrop"
    const val PASSWORD = "crossdrop123"

    /** The address Android gives the phone that owns a Wi-Fi Direct group (and hands out the rest of its /24). */
    const val HOST = "192.168.49.1"

    /**
     * While the network is on, the phone that made it also listens here (besides its usual listener), so a device
     * that joined without a code knows where to announce itself.
     */
    const val PORT = 47474

    /** Whether [ssid] is a CrossDrop Wi-Fi (Android reports names in quotes on some versions). */
    fun isCrossDropWifi(ssid: String?): Boolean = ssid?.trim()?.removeSurrounding("\"") == SSID

    /** What a one-tap join uses: the network, and where the phone that made it listens. */
    fun joinCode(host: String = HOST): JoinCode = JoinCode(SSID, PASSWORD, "WPA", host = Endpoint(host, PORT))

    /**
     * Where the phone that made the network most likely is, from this device's own address on it: the group owner
     * is the `.1` of the /24 it hands out (192.168.49.1 on Android; a maker may pick another subnet). Without a
     * usable private IPv4 address, [HOST].
     */
    fun hostFor(localIpv4: String?): String {
        val parts = localIpv4?.trim()?.split('.')?.takeIf { it.size == 4 } ?: return HOST
        val bytes = parts.map { it.toIntOrNull()?.takeIf { b -> b in 0..255 } ?: return HOST }
        val private = bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168)
        if (!private || bytes[3] == 1) return HOST
        return "${bytes[0]}.${bytes[1]}.${bytes[2]}.1"
    }

    /** Whether [localIpv4] is on Android's Wi-Fi Direct subnet, i.e. this device is on a CrossDrop Wi-Fi now. */
    fun onGroupSubnet(localIpv4: String?): Boolean =
        localIpv4 != null && localIpv4.startsWith("192.168.49.") && localIpv4 != HOST
}
