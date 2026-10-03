package com.crossdrop.di

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import com.crossdrop.core.CrossDrop
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.PeerDiscovery
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.discovery.BleDiscovery
import com.crossdrop.discovery.NsdDiscovery
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom
import java.util.UUID

/**
 * Hand-rolled service locator — no DI framework, keeps the binary lean.
 * Everything here lives for the process lifetime.
 */
class AppContainer(private val app: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Rotating per-launch identity token shared across NSD TXT + BLE service data. */
    val instanceId: String = run {
        val bytes = ByteArray(CrossDrop.INSTANCE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val prefs = app.getSharedPreferences("crossdrop", Context.MODE_PRIVATE)

    private val _deviceName = MutableStateFlow(loadDeviceName())
    val deviceName: StateFlow<String> = _deviceName

    fun setDeviceName(name: String) {
        val clean = name.trim().ifBlank { return }
        prefs.edit().putString("device_name", clean).apply()
        _deviceName.value = clean
    }

    private fun loadDeviceName(): String =
        prefs.getString("device_name", null)
            ?: runCatching {
                Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    val identity = SessionIdentity()
    val engine = TransferEngine(identity, deviceName::value, instanceId)
    val inbox = InboxStore(app)

    val server = TransferServer(scope, engine, onBound = ::onListenerBound)
    val coordinator = TransferCoordinator(scope, app, engine, server, inbox)

    private val discoveries: List<PeerDiscovery> = buildList {
        add(NsdDiscovery(
            app.getSystemService<NsdManager>()!!,
            app.applicationContext.getSystemService<WifiManager>(),
            deviceName::value,
            instanceId,
        ))
        add(BleDiscovery(app, instanceId))
    }

    /** All sightings merged by instanceId — a peer seen on BLE + NSD is one node. */
    val peers: StateFlow<Map<String, Peer>> = run {
        val flows = discoveries.map { it.peers }
        combine(flows) { arrays ->
            val merged = linkedMapOf<String, Peer>()
            for (map in arrays) for ((id, p) in map) {
                val existing = merged[id]
                merged[id] = if (existing == null) p else p.copy(
                    endpoint = p.endpoint ?: existing.endpoint,
                    sources = p.sources + existing.sources,
                    rssi = p.rssi ?: existing.rssi,
                    lastSeenMs = maxOf(p.lastSeenMs, existing.lastSeenMs),
                )
            }
            merged
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Context accessor for places (Root UI) that need it for intents/services. */
    fun contextForService(): Context = app

    /** Restart discovery — e.g. after permissions are granted post-launch. */
    fun restartDiscovery() {
        discoveries.forEach { it.stop(); it.start() }
    }

    fun start() {
        runCatching { server.start() }   // listener comes up first so we know the port…
        discoveries.forEach { runCatching { it.start() } }
        coordinator.start()
    }

    /** Called by [TransferServer] once the listener socket is bound. */
    fun onListenerBound(port: Int) {
        discoveries.filterIsInstance<NsdDiscovery>().forEach { it.advertisedPort = port }
    }
}
