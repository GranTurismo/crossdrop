package com.crossdrop.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.crossdrop.core.connect.DirectLinkFailure
import com.crossdrop.core.connect.DirectLinkMachine
import com.crossdrop.core.connect.DirectLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * "Connect directly": this phone creates a private, temporary Wi-Fi network (Android's local-only
 * hotspot: no internet is shared, the name and password are chosen by the system) so a device with no
 * shared Wi-Fi can join it by scanning a QR code. The network lives only while we hold the reservation:
 * [stop], the auto-stop timer, the system, or the process ending all tear it down.
 *
 * The phone answers a request whenever it likes, or never; [DirectLinkMachine] keeps stale answers
 * (after a timeout, a Stop, or a newer request) from changing anything.
 */
class DirectLink(private val context: Context, private val scope: CoroutineScope) {

    private val machine = DirectLinkMachine()
    val state: StateFlow<DirectLinkState> = machine.state

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var startTimeout: Job? = null
    private var autoStop: Job? = null

    /** The runtime permission still needed to create the network, or null when we have it. */
    fun missingPermission(): String? {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return p.takeIf { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    /** Before Android 13 the system refuses to create the network while Location is switched off. */
    private fun locationBlocked(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !lm.isLocationEnabled
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (missingPermission() != null) { machine.refuse(DirectLinkFailure.PERMISSION); return }
        if (locationBlocked()) { machine.refuse(DirectLinkFailure.LOCATION_OFF); return }
        val request = machine.begin() ?: return
        startTimeout?.cancel()
        // Some phones never answer; don't leave "Setting up…" on screen forever.
        startTimeout = scope.launch { delay(START_TIMEOUT); machine.timedOut(request) }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    val on = credentials(r)
                    if (on == null) { r.close(); machine.failed(request, DirectLinkFailure.OTHER); return }
                    // A stale answer (timed out, stopped, replaced): hand the hotspot straight back.
                    if (!machine.started(request, on)) { r.close(); return }
                    startTimeout?.cancel()
                    synchronized(this@DirectLink) { reservation = r }
                    autoStop?.cancel()
                    autoStop = scope.launch { delay(AUTO_STOP); stop() }
                }

                override fun onStopped() {
                    machine.stoppedBySystem(request)
                    synchronized(this@DirectLink) { if (machine.state.value !is DirectLinkState.On) reservation = null }
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
            }, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            machine.failed(request, DirectLinkFailure.PERMISSION)
        } catch (_: Exception) {
            machine.failed(request, DirectLinkFailure.OTHER)
        }
    }

    /** Stops the network, or cancels a start that hasn't answered yet (its late answer is closed on arrival). */
    fun stop() {
        startTimeout?.cancel(); startTimeout = null
        autoStop?.cancel(); autoStop = null
        machine.stop()
        val r = synchronized(this) { reservation.also { reservation = null } }
        runCatching { r?.close() }
    }

    /** Debug builds only: shows a state without a hotspot (emulators can't make one). */
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

    private companion object {
        val START_TIMEOUT = 20.seconds

        /** A forgotten network shouldn't run (and drain the battery) forever. */
        val AUTO_STOP = 30.minutes
        const val ERROR_NO_CHANNEL = WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
        const val ERROR_INCOMPATIBLE_MODE = WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
        const val ERROR_TETHERING_DISALLOWED = WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED
    }
}
