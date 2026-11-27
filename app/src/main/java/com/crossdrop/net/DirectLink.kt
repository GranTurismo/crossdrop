package com.crossdrop.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.crossdrop.core.connect.DirectLinkFailure
import com.crossdrop.core.connect.DirectLinkMachine
import com.crossdrop.core.connect.DirectLinkState
import com.crossdrop.core.connect.DirectWifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * "Connect directly": this phone makes a temporary Wi-Fi network for devices that share no Wi-Fi with it.
 *
 * First choice is the **CrossDrop Wi-Fi** ([DirectWifi]): a Wi-Fi Direct group with the same name and password on
 * every phone, so CrossDrop elsewhere joins it in one tap and a laptop joins it by typing a short password. Wi-Fi
 * Direct runs beside the phone's own Wi-Fi on most phones, so the phone usually keeps its internet. When the phone
 * can't (Wi-Fi switched off, no Wi-Fi Direct, another app's Wi-Fi Direct connection running, the maker refuses), it
 * falls back to Android's local-only hotspot, whose name and password the system picks: the code and the typed
 * password still work, one-tap joining doesn't ([DirectLinkState.On.oneTap] is false).
 *
 * **Threading**: everything here runs on the main looper (the Wi-Fi Direct callbacks arrive there, and every job is
 * launched on `Dispatchers.Main`), so no field is shared between threads. **Requests**: the phone answers whenever it
 * likes, or never; [DirectLinkMachine] tags every request, each job checks it still owns its request before acting,
 * and every job of a request is cancelled when a newer one starts or the link stops.
 *
 * **Only our group is ever removed**: a removal first asks the system which group is up and removes it only when it
 * is the CrossDrop Wi-Fi this phone owns (or the group this run made under a name of the maker's choosing) and no
 * newer request wants it. A group can outlive the app (killed while hosting): a marker in prefs makes the next launch
 * remove it ([cleanUpLeftover]).
 */
class DirectLink(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Our transfer listener and per-launch id: CrossDrop's scanner reads them from the code. */
    private val listenPort: () -> Int = { 0 },
    private val instanceId: String? = null,
) {

    private val machine = DirectLinkMachine()
    val state: StateFlow<DirectLinkState> = machine.state

    /** Null on a device without Wi-Fi (Wi-Fi is an optional feature in the manifest): no network to make there. */
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val p2p = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.applicationContext.getSharedPreferences("direct_link", Context.MODE_PRIVATE)

    // ---- main looper only
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    /** One Wi-Fi Direct channel for the whole run of the app (made on first use, again if the system drops it). */
    private var channel: WifiP2pManager.Channel? = null
    /** The group this run made when the phone ignored our name: removable as ours too. */
    private var ownGroupName: String? = null
    /** Every job of the current request (timeout, waiting for the group, watcher, address, auto-stop). */
    private val jobs = mutableListOf<Job>()
    /** The second removal that follows a Stop (a group still forming can come up right after the first). */
    private var pendingRemove: Runnable? = null

    /** The runtime permission still needed to create the network, or null when we have it. */
    fun missingPermission(): String? {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return p.takeIf { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    /** Before Android 13 the system refuses to create either network while Location is switched off. */
    private fun locationBlocked(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !lm.isLocationEnabled
    }

    fun start() = onMain {
        val wifi = wifi ?: run { machine.refuse(DirectLinkFailure.OTHER); return@onMain }
        if (missingPermission() != null) { machine.refuse(DirectLinkFailure.PERMISSION); return@onMain }
        if (locationBlocked()) { machine.refuse(DirectLinkFailure.LOCATION_OFF); return@onMain }
        val request = machine.begin() ?: return@onMain
        cancelJobs()
        // A Stop just before this start may still have its follow-up removal queued: it must not hit the new group.
        pendingRemove?.let(main::removeCallbacks); pendingRemove = null
        // Some phones never answer; don't leave "Setting up…" on screen forever. Whatever comes up late is undone.
        launchJob {
            delay(START_TIMEOUT)
            if (machine.timedOut(request)) { cancelJobs(); removeOurGroup(retry = true) }
        }
        // Wi-Fi Direct needs Wi-Fi switched on (it doesn't have to be connected anywhere); the hotspot doesn't.
        if (p2p != null && wifi.isWifiEnabled) startGroup(request)
        else startHotspot(request, wifiWasOff = !wifi.isWifiEnabled)
    }

    /** Stops the network, or cancels a start that hasn't answered yet (its late answer is undone on arrival). */
    fun stop() = onMain {
        cancelJobs()
        machine.stop()
        LocalIp.directLinkAddress = null
        reservation?.let { runCatching { it.close() } }
        reservation = null
        removeOurGroup(retry = true)
    }

    /**
     * At launch: a CrossDrop Wi-Fi this app made may have outlived its process (killed while hosting; the auto-stop died
     * with it). Remove it, so a network with a public password never runs unattended. Only when the last run left the
     * marker, so an ordinary app start never touches Wi-Fi Direct.
     */
    fun cleanUpLeftover() = onMain {
        if (!prefs.getBoolean(KEY_GROUP_UP, false) || missingPermission() != null) return@onMain
        Log.i(TAG, "removing a CrossDrop Wi-Fi left from an earlier run, if it is still up")
        removeOurGroup(retry = false)
    }

    // ---- the CrossDrop Wi-Fi (Wi-Fi Direct group)

    private fun channel(): WifiP2pManager.Channel? {
        channel?.let { return it }
        val made = runCatching {
            p2p?.initialize(context.applicationContext, Looper.getMainLooper()) {
                // The system dropped us (Wi-Fi Direct restarted): a group we had is gone with it.
                Log.w(TAG, "Wi-Fi Direct channel lost")
                main.post {
                    channel = null
                    if (machine.state.value is DirectLinkState.On && reservation == null) groupLost(request = null)
                }
            }
        }.getOrNull()
        channel = made
        return made
    }

    @SuppressLint("MissingPermission")
    private fun startGroup(request: Int) {
        if (!machine.pending(request)) return
        val p2p = p2p
        val ch = channel()
        if (p2p == null || ch == null) { startHotspot(request); return }
        try {
            p2p.requestGroupInfo(ch) { existing ->
                when {
                    !machine.pending(request) -> Unit
                    existing == null -> createGroup(request, ch, WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    // Ours, left over (a Stop still removing it, or the app was closed with it up): start afresh.
                    existing.isGroupOwner && isOurs(existing) ->
                        p2p.removeGroup(ch, after { createGroup(request, ch, WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) })
                    // Another Wi-Fi Direct connection is running (Quick Share, a printer…): leave it alone.
                    else -> { Log.i(TAG, "another Wi-Fi Direct group is up: using the hotspot"); startHotspot(request) }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "requestGroupInfo refused: ${e.message}")
            startHotspot(request)
        }
    }

    /**
     * Asks for the group. 2.4 GHz first (every laptop and phone can see it); if the phone can't host there right
     * now (its own Wi-Fi on another channel and no second radio), let it choose; then the hotspot.
     */
    @SuppressLint("MissingPermission")
    private fun createGroup(request: Int, ch: WifiP2pManager.Channel, band: Int) {
        if (!machine.pending(request)) return
        val p2p = p2p ?: return startHotspot(request)
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(DirectWifi.SSID)
                .setPassphrase(DirectWifi.PASSWORD)
                .setGroupOperatingBand(band)
                .enablePersistentMode(false)
                .build()
        }.getOrElse { Log.w(TAG, "group config refused", it); return startHotspot(request) }
        try {
            p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    markGroupUp(true)
                    // A stale "yes" (stopped or timed out meanwhile): undo it, unless a newer request is under way.
                    if (machine.pending(request)) launchJob { awaitGroup(request, ch) } else removeOurGroup(retry = true)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup(band=$band) failed: ${reasonName(reason)}")
                    if (band == WifiP2pConfig.GROUP_OWNER_BAND_2GHZ && reason != WifiP2pManager.P2P_UNSUPPORTED) {
                        createGroup(request, ch, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    } else {
                        startHotspot(request)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "createGroup refused: ${e.message}")
            startHotspot(request)
        }
    }

    /** The group forms a moment after "success": wait for it, then show it (or undo it if the request went stale). */
    private suspend fun awaitGroup(request: Int, ch: WifiP2pManager.Channel) {
        repeat(GROUP_WAIT_TRIES) {
            if (!machine.pending(request)) { removeOurGroup(retry = true); return }
            val g = (groupInfo(ch) as? GroupInfo.Up)?.group
            val name = g?.networkName
            if (g != null && g.isGroupOwner && name != null) {
                val ours = DirectWifi.isCrossDropWifi(name) && g.passphrase == DirectWifi.PASSWORD
                // A maker that ignores the name we asked for still made a working network: show what it made.
                if (!ours) ownGroupName = name
                val on = if (ours) DirectLinkState.On(DirectWifi.SSID, DirectWifi.PASSWORD, "WPA", oneTap = true)
                else DirectLinkState.On(name, g.passphrase, "WPA")
                if (!machine.started(request, on)) { removeOurGroup(retry = true); return }
                Log.i(TAG, "CrossDrop Wi-Fi is on (${g.`interface`}, ${if (ours) "one-tap" else "own name"})")
                running(request)
                val iface = g.`interface`
                launchJob { findAddress(request) { iface?.let(::ipv4Of) ?: groupIpv4() } }
                launchJob { watchGroup(request, ch) }
                return
            }
            delay(GROUP_WAIT_STEP_MS)
        }
        // Said yes but never formed one: undo it and use the hotspot instead.
        Log.w(TAG, "group never came up: using the hotspot")
        removeOurGroup(retry = true)
        startHotspot(request)
    }

    /** While on, check now and then that the group still exists (Wi-Fi switched off, the system ended it). */
    private suspend fun watchGroup(request: Int, ch: WifiP2pManager.Channel) {
        var misses = 0
        while (machine.owns(request)) {
            delay(WATCH_MS)
            when (val info = groupInfo(ch)) {
                is GroupInfo.Up -> misses = if (info.group.isGroupOwner && isOurs(info.group)) 0 else misses + 1
                GroupInfo.None -> misses++
                GroupInfo.Unknown -> Unit          // a slow or refused answer says nothing either way
            }
            if (misses >= 2) {
                Log.i(TAG, "CrossDrop Wi-Fi ended by the system")
                groupLost(request)
                return
            }
        }
    }

    /** The system ended our group (or it vanished): show Off, and remove it in case it is somehow still there. */
    private fun groupLost(request: Int?) {
        if (request != null && !machine.owns(request)) return
        cancelJobs()
        LocalIp.directLinkAddress = null
        if (request != null) machine.stoppedBySystem(request) else machine.stop()
        removeOurGroup(retry = false)
    }

    private sealed interface GroupInfo {
        data class Up(val group: WifiP2pGroup) : GroupInfo
        data object None : GroupInfo
        /** No answer in time, or refused: unknown. */
        data object Unknown : GroupInfo
    }

    @SuppressLint("MissingPermission")
    private suspend fun groupInfo(ch: WifiP2pManager.Channel): GroupInfo = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine<GroupInfo> { cont ->
            val p2p = p2p
            if (p2p == null) {
                cont.resume(GroupInfo.Unknown)
            } else {
                try {
                    p2p.requestGroupInfo(ch) { g -> if (cont.isActive) cont.resume(if (g == null) GroupInfo.None else GroupInfo.Up(g)) }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(GroupInfo.Unknown)
                }
            }
        }
    } ?: GroupInfo.Unknown

    private fun isOurs(g: WifiP2pGroup): Boolean {
        val name = g.networkName ?: return false
        return DirectWifi.isCrossDropWifi(name) || (ownGroupName != null && name == ownGroupName)
    }

    /**
     * Removes the group that is up, but only if it is ours (the CrossDrop Wi-Fi, owned by this phone) and no newer
     * request wants it: never another app's Wi-Fi Direct connection, whatever the timing. [retry] asks once more a
     * moment later (a group still forming can come up right after the first ask); a new start cancels that.
     */
    @SuppressLint("MissingPermission")
    private fun removeOurGroup(retry: Boolean) {
        val p2p = p2p ?: return
        val removeIfOurs = object : Runnable {
            override fun run() {
                if (pendingRemove === this) pendingRemove = null
                val ch = channel() ?: return
                runCatching {
                    p2p.requestGroupInfo(ch) { g ->
                        when {
                            g == null -> if (machine.idle()) markGroupUp(false)
                            !g.isGroupOwner || !isOurs(g) -> Unit
                            // A newer request starting or running uses (or replaces) the group itself.
                            !machine.idle() -> Unit
                            else -> p2p.removeGroup(ch, after { markGroupUp(false) })
                        }
                    }
                }.onFailure { Log.w(TAG, "removing the group failed: ${it.message}") }
            }
        }
        removeIfOurs.run()
        if (retry) {
            pendingRemove?.let(main::removeCallbacks)
            pendingRemove = removeIfOurs
            main.postDelayed(removeIfOurs, 2_000)
        }
    }

    private fun markGroupUp(up: Boolean) = prefs.edit { putBoolean(KEY_GROUP_UP, up) }

    private fun after(next: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = next()
        override fun onFailure(reason: Int) = next()
    }

    // ---- fallback: Android's local-only hotspot (name and password picked by the system)

    @SuppressLint("MissingPermission")
    private fun startHotspot(request: Int, wifiWasOff: Boolean = false) {
        if (!machine.pending(request)) return
        val wifi = wifi ?: run { machine.failed(request, DirectLinkFailure.OTHER); return }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    val on = credentials(r)?.copy(wifiWasOff = wifiWasOff)
                    if (on == null) { r.close(); machine.failed(request, DirectLinkFailure.OTHER); return }
                    // A stale answer (timed out, stopped, replaced): hand the hotspot straight back.
                    if (!machine.started(request, on)) { r.close(); return }
                    reservation?.let { runCatching { it.close() } }
                    reservation = r
                    running(request)
                    launchJob { findAddress(request, ::hotspotIpv4) }
                }

                override fun onStopped() {
                    if (!machine.owns(request)) return
                    machine.stoppedBySystem(request)
                    reservation = null
                    cancelJobs()
                    LocalIp.directLinkAddress = null
                }

                override fun onFailed(reason: Int) {
                    machine.failed(
                        request,
                        when (reason) {
                            ERROR_NO_CHANNEL -> DirectLinkFailure.NO_CHANNEL
                            ERROR_INCOMPATIBLE_MODE -> DirectLinkFailure.BUSY
                            ERROR_TETHERING_DISALLOWED -> DirectLinkFailure.NOT_ALLOWED
                            else -> if (locationBlocked()) DirectLinkFailure.LOCATION_OFF else DirectLinkFailure.OTHER
                        },
                    )
                }
            }, main)
        } catch (_: SecurityException) {
            machine.failed(request, DirectLinkFailure.PERMISSION)
        } catch (_: Exception) {
            machine.failed(request, DirectLinkFailure.OTHER)
        }
    }

    /** A network is up: it must not run (and drain the battery) forever. */
    private fun running(request: Int) {
        launchJob { delay(AUTO_STOP); if (machine.owns(request)) stop() }
    }

    /**
     * The network's interface gets its address a moment after the system says "started". Once it has one, the code
     * also carries it (with our listener port), so CrossDrop on the other device connects straight here instead of
     * hoping discovery works there, and "Send to anyone" links point at this network while it is on.
     */
    private suspend fun findAddress(request: Int, address: () -> String?) {
        repeat(20) {
            if (!machine.owns(request)) return
            val ip = address()
            val port = listenPort()
            if (ip != null) LocalIp.directLinkAddress = ip
            if (ip != null && port > 0 && instanceId != null) {
                machine.located(request, ip, port, instanceId)
                return
            }
            delay(500)
        }
    }

    private fun ipv4Of(iface: String): String? = runCatching {
        NetworkInterface.getByName(iface)?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** Our address on a Wi-Fi Direct group interface (`p2p0`, `p2p-wlan0-0`), when the system didn't name it. */
    private fun groupIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.name.startsWith("p2p") }
            .firstNotNullOfOrNull { i ->
                i.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
            }
    }.getOrNull()

    /**
     * Our IPv4 on the hotspot: a private address on an interface that isn't one of the networks we use as a client
     * (our own Wi-Fi, mobile data) nor a Wi-Fi Direct group (another app's may be up: that's why we fell back). Makers
     * name it ap0, swlan0, wlan1… From Android 15 the hotspot is itself listed as a network, marked LOCAL_NETWORK; it
     * must not count as a client network.
     */
    @Suppress("DEPRECATION")
    private fun hotspotIpv4(): String? = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val clientIfaces = cm.allNetworks.filter { n ->
            Build.VERSION.SDK_INT < 35 ||
                cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK) != true
        }.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name !in clientIfaces && !it.name.startsWith("p2p") }
            .flatMap { i ->
                i.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }.map { i.name to it.hostAddress }
            }
        candidates.firstOrNull { (n, _) -> n.startsWith("ap") || n.startsWith("swlan") }?.second
            ?: candidates.firstOrNull { (n, _) -> n.startsWith("wlan") }?.second
    }.getOrNull()

    /** Debug builds only: shows a state without a network (emulators can't make either kind). */
    fun forceStateForDebug(s: DirectLinkState) = machine.force(s)

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): DirectLinkState.On? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = c.ssid ?: return null
            val security = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> "nopass"
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "SAE"
                else -> "WPA"      // WPA2, and WPA3 transition (phones accept both)
            }
            return DirectLinkState.On(ssid, c.passphrase, security)
        }
        val c = r.wifiConfiguration ?: return null
        val ssid = c.SSID?.trim('"') ?: return null
        val key = c.preSharedKey?.trim('"')
        return DirectLinkState.On(ssid, key, if (key.isNullOrEmpty()) "nopass" else "WPA")
    }

    // ---- main-looper plumbing

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** A job of the current request, on the main looper; cancelled with the rest by [cancelJobs]. */
    private fun launchJob(block: suspend CoroutineScope.() -> Unit) {
        jobs.removeAll { !it.isActive }
        jobs += scope.launch(Dispatchers.Main.immediate, block = block)
    }

    private fun cancelJobs() {
        val running = jobs.toList()
        jobs.clear()
        running.forEach { it.cancel() }
    }

    private fun reasonName(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> reason.toString()
    }

    private companion object {
        const val TAG = "DirectLink"
        const val KEY_GROUP_UP = "group_up"
        /** Room for a Wi-Fi Direct attempt (and its retry) plus the hotspot after it. */
        val START_TIMEOUT = 25.seconds

        /** A forgotten network shouldn't run (and drain the battery) forever. */
        val AUTO_STOP = 30.minutes
        const val GROUP_WAIT_TRIES = 20
        const val GROUP_WAIT_STEP_MS = 500L
        const val WATCH_MS = 5_000L
        const val ERROR_NO_CHANNEL = WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
        const val ERROR_INCOMPATIBLE_MODE = WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
        const val ERROR_TETHERING_DISALLOWED = WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED
    }
}
