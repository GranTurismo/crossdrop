package com.crossdrop.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import com.crossdrop.core.CrossDrop
import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Peer
import com.crossdrop.core.discovery.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BLE beacon layer per spec: we advertise a 128-bit service UUID carrying the
 * rotating instance token as service data, and scan for the same. BLE sightings
 * carry no endpoint — they enrich the radar with RSSI-derived proximity, and
 * converge with NSD sightings on the same instanceId.
 *
 * Callers must hold BLUETOOTH_SCAN/ADVERTISE/CONNECT before [start]; all calls
 * are wrapped defensively so missing permission degrades silently to no peers.
 */
class BleDiscovery(
    private val context: Context,
    private val instanceId: String,
) : PeerDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        val manager = context.getSystemService<BluetoothManager>()
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "bluetooth unavailable/disabled — BLE layer dormant")
            return
        }
        scanner = adapter.bluetoothLeScanner
        advertiser = adapter.bluetoothLeAdvertiser
        startScan()
        startAdvertising()
        startExpiryLoop()
    }

    override fun stop() {
        started = false
        scanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        advertiseCallback?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        scanCallback = null; advertiseCallback = null
        _peers.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val s = scanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CrossDrop.BLE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onSighting(result)
            override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::onSighting)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }
        scanCallback = cb
        runCatching { s.startScan(listOf(filter), settings, cb) }
            .onFailure { Log.w(TAG, "startScan threw", it) }
    }

    private fun onSighting(result: ScanResult) {
        val data = result.scanRecord
            ?.getServiceData(ParcelUuid(CrossDrop.BLE_SERVICE_UUID)) ?: return
        val id = data.joinToString("") { "%02x".format(it) }
        if (id == instanceId) return
        _peers.value = _peers.value + (id to Peer(
            instanceId = id,
            name = result.scanRecord?.deviceName ?: "Nearby device",
            endpoint = null,
            sources = setOf(DiscoverySource.BLE),
            rssi = result.rssi,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val a = advertiser ?: run {
            Log.i(TAG, "no BLE advertiser on this device")
            return
        }
        val token = instanceId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(CrossDrop.BLE_SERVICE_UUID))
            .addServiceData(ParcelUuid(CrossDrop.BLE_SERVICE_UUID), token)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "BLE advertising up")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertise failed: $errorCode")
            }
        }
        advertiseCallback = cb
        runCatching { a.startAdvertising(settings, data, cb) }
            .onFailure { Log.w(TAG, "startAdvertising threw", it) }
    }

    /** BLE sightings fade fast — prune anything not re-heard in [STALE_MS]. */
    private fun startExpiryLoop() {
        scope.launch {
            while (isActive && started) {
                delay(2_000)
                val cutoff = System.currentTimeMillis() - STALE_MS
                _peers.value = _peers.value.filterValues { it.lastSeenMs >= cutoff }
            }
        }
    }

    private companion object {
        const val TAG = "BleDiscovery"
        const val STALE_MS = 8_000
    }
}
