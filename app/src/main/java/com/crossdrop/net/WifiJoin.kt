package com.crossdrop.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.crossdrop.core.connect.JoinCode
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "Scan a code": joins the network in a scanned Wi-Fi code (another phone's private link, or any router's
 * code) from inside CrossDrop, and connects straight to the phone that made it.
 *
 * Android joins through an app request ([WifiNetworkSpecifier]): the system asks the person once, the
 * network carries no internet, and it lasts while we hold the request ([leave], or the app going away).
 * While joined the whole process is bound to that network, so every connection CrossDrop opens goes over
 * it. Then we announce ourselves to the host (ANNOUNCE) so it sees us too, and repeat that every 15 s so
 * both radars stay current.
 */
class WifiJoin(
    context: Context,
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val listenPort: () -> Int,
    /** Where the host shows up on our radar (the same list announcements land in). */
    private val known: AnnouncedPeers,
) {
    sealed interface State {
        data object Idle : State
        /** The system is asking the person / looking for the network. */
        data class Joining(val ssid: String) : State
        /** On the network; reaching the device that made the code. */
        data class Connecting(val ssid: String) : State
        /** On the network. [hostName] is set once the code's device answered. */
        data class Joined(val ssid: String, val hostName: String? = null) : State
        data class Failed(val ssid: String, val reason: Failure) : State
    }

    enum class Failure {
        /** Not found, not in range, or the person said no to the system's question. */
        NOT_JOINED,
        /** The network went away (the other phone stopped its link, or we moved out of range). */
        LOST,
        /** Android needs a permission first. */
        PERMISSION,
        /** WEP and other codes Android won't join from an app. */
        UNSUPPORTED,
    }

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val appContext = context.applicationContext
    private val _state = object {
        private val flow = MutableStateFlow<State>(State.Idle)
        var value: State
            get() = flow.value
            set(v) { if (flow.value != v) Log.i(TAG, "state: $v"); flow.value = v }
        val asFlow: StateFlow<State> get() = flow
    }
    val state: StateFlow<State> = _state.asFlow

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var bound: Network? = null
    private var hostId: String? = null
    private var talk: Job? = null

    /** The permission Android may want before an app joins a network, when we don't have it yet. */
    fun missingPermission(): String? {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return p.takeIf { ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED }
    }

    @Synchronized
    fun join(code: JoinCode) {
        leave()
        val ssid = code.ssid
        val spec = runCatching {
            WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                when (code.security) {
                    "nopass" -> Unit
                    "SAE" -> setWpa3Passphrase(code.password ?: "")
                    "WEP" -> throw IllegalArgumentException("WEP")
                    else -> setWpa2Passphrase(code.password ?: "")
                }
                if (code.hidden) setIsHiddenSsid(true)
            }.build()
        }.getOrElse {
            _state.value = State.Failed(ssid, Failure.UNSUPPORTED)
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onUp(this, network, code)
            override fun onUnavailable() = onGone(this, ssid, Failure.NOT_JOINED)
            override fun onLost(network: Network) = onGone(this, ssid, Failure.LOST)
        }
        callback = cb
        _state.value = State.Joining(ssid)
        try {
            cm.requestNetwork(request, cb, JOIN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "join refused: ${e.message}")
            callback = null
            _state.value = State.Failed(ssid, Failure.PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "join request failed", e)
            callback = null
            _state.value = State.Failed(ssid, Failure.NOT_JOINED)
        }
    }

    /** Leave the network (Android returns to the usual Wi-Fi) and forget the host. */
    @Synchronized
    fun leave() {
        talk?.cancel(); talk = null
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        if (bound != null) { runCatching { cm.bindProcessToNetwork(null) }; bound = null }
        hostId?.let(known::remove); hostId = null
        _state.value = State.Idle
    }

    /** Clears a failure shown to the person. */
    fun dismiss() { if (_state.value is State.Failed) _state.value = State.Idle }

    /** Debug builds: skip the Wi-Fi step (emulators can't join each other's hotspot) and connect + announce. */
    fun connectForDebug(code: JoinCode) {
        leave()
        _state.value = State.Connecting(code.ssid)
        talk = scope.launch { reachHost(code) }
    }

    @Synchronized
    private fun onUp(cb: ConnectivityManager.NetworkCallback, network: Network, code: JoinCode) {
        if (cb !== callback) return
        // Every socket the app opens from now on goes over this network (it has no internet, so the
        // system wouldn't route anything to it otherwise).
        runCatching { cm.bindProcessToNetwork(network) }
        bound = network
        _state.value = State.Connecting(code.ssid)
        talk?.cancel()
        talk = scope.launch { reachHost(code) }
    }

    @Synchronized
    private fun onGone(cb: ConnectivityManager.NetworkCallback, ssid: String, why: Failure) {
        if (cb !== callback) return
        talk?.cancel(); talk = null
        runCatching { cm.unregisterNetworkCallback(cb) }
        callback = null
        if (bound != null) { runCatching { cm.bindProcessToNetwork(null) }; bound = null }
        hostId?.let(known::remove); hostId = null
        _state.value = State.Failed(ssid, why)
    }

    /** Announce to the code's device until it answers, then keep it current. Without an address, discovery has to find it. */
    private suspend fun reachHost(code: JoinCode) {
        val host = code.host ?: run { _state.value = State.Joined(code.ssid); return }
        var first = true
        var misses = 0
        while (true) {
            val answer = runCatching {
                engine.control(host) { announce(listenPort()); peerName to peerInstanceId }
            }.onFailure { Log.i(TAG, "announce to $host: ${it.message}") }.getOrNull()
            if (answer != null) {
                val (name, id) = answer
                hostId = id
                known.add(id, name, host)
                misses = 0
                if (first || (_state.value as? State.Joined)?.hostName != name) _state.value = State.Joined(code.ssid, name)
                first = false
                delay(REANNOUNCE_MS)
            } else {
                misses++
                // The phone that made the code may need a moment after we joined; after that, keep being on
                // the network even if it doesn't answer (discovery may still find it). A host that stopped
                // answering is no longer named as linked.
                if ((first && misses == FIRST_TRIES) || (!first && misses == 2)) _state.value = State.Joined(code.ssid)
                delay(if (first && misses < FIRST_TRIES) 1_000 else REANNOUNCE_MS)
            }
        }
    }

    private companion object {
        const val TAG = "WifiJoin"
        /** The system's question plus finding the network; Android gives up on its own after this. */
        const val JOIN_TIMEOUT_MS = 60_000
        const val REANNOUNCE_MS = 15_000L
        const val FIRST_TRIES = 8
    }
}
