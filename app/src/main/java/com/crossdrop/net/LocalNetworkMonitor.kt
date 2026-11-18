package com.crossdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface

/**
 * Whether this device is on a network other devices could share: Wi-Fi or Ethernet, or a hotspot of its
 * own. Mobile data alone doesn't count; phones on it can't see each other. A network with no internet
 * still counts, because that is exactly what a local-only link looks like.
 */
class LocalNetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _hasNetwork = MutableStateFlow(compute())
    val hasNetwork: StateFlow<Boolean> = _hasNetwork

    init {
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        }
    }

    /** Also call when our own hotspot starts or stops: it is an interface, not a [Network] callbacks report. */
    fun refresh() { _hasNetwork.value = compute() }

    @Suppress("DEPRECATION")
    private fun compute(): Boolean {
        val shared = runCatching {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
        }.getOrDefault(false)
        return shared || hotspotUp()
    }

    private fun hotspotUp(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { i ->
            i.isUp && (i.name.startsWith("ap") || i.name.startsWith("swlan")) &&
                i.inetAddresses.toList().any { it is java.net.Inet4Address }
        }
    }.getOrDefault(false)
}
