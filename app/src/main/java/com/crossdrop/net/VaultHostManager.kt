package com.crossdrop.net

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.core.vault.VaultServer
import com.crossdrop.discovery.VaultAdvertiser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * Owns the hosted LAN vault: a `VaultServer` over `filesDir/vault`, an NSD
 * `_crossdrop-vault._tcp.` registration, and the dashboard StateFlow. The
 * vault id is a fresh 16-hex token per run — restarting the drive makes the
 * old beacon unresolvable, and nothing persistent ever touches the wire.
 */
class VaultHostManager(
    private val scope: CoroutineScope,
    private val context: Context,
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    private val nsd: NsdManager,
) {
    data class State(
        val port: Int,
        val url: String,
        val vaultName: String,
        val writable: Boolean,
        val pin: String?,           // null → open vault
        val clients: List<String> = emptyList(),
        val bytesOut: Long = 0,
        val bytesIn: Long = 0,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    val isHosting: Boolean get() = _state.value != null

    private var server: VaultServer? = null
    private val advertiser = VaultAdvertiser(nsd)
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Current vault token — discovery filters our own beacon with this. */
    @Volatile var vaultId: String? = null
        private set

    /** What users put in `filesDir/vault` is what the LAN sees — nothing else. */
    private val vaultRoot: File get() = File(context.filesDir, "vault")

    fun start(writable: Boolean, usePin: Boolean, label: String? = null) {
        stop()
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val pin = if (usePin) "%04d".format(SecureRandom().nextInt(10_000)) else null
        val name = label?.takeIf { it.isNotBlank() } ?: "${deviceName()} Drive"

        val srv = VaultServer(
            fs = VaultFs(vaultRoot),
            identity = identity,
            deviceName = deviceName,
            writable = writable,
            pin = pin,
            instanceId = id,
        ) { refresh() }
        runCatching { srv.start() }.onFailure {
            Log.w(TAG, "vault failed to bind", it); return
        }
        server = srv
        vaultId = id

        val ip = LocalIp.wifiIpv4()
        val url = if (ip != null) "http://$ip:${srv.port}/" else null
        _state.value = State(
            port = srv.port,
            url = url ?: "http://<no-wifi>:${srv.port}/",
            vaultName = name,
            writable = writable,
            pin = pin,
        )
        advertiser.start(id, name, srv.port, writable, pin != null)
        Log.i(TAG, "vault up at $url (mode=${if (writable) "rw" else "ro"}," +
            " auth=${if (pin != null) "pin" else "open"})")
        if (pin != null && com.crossdrop.BuildConfig.DEBUG) {
            Log.i(TAG, "vault pin=$pin")   // debug-only: lets adb flows unlock
        }

        pollJob = scope.launch { while (isActive) { delay(1_000); refresh() } }
    }

    private fun refresh() {
        val srv = server ?: return
        _state.value = _state.value?.copy(
            clients = srv.connectedPeers.toList(),
            bytesOut = srv.bytesOut.get(),
            bytesIn = srv.bytesIn.get(),
        )
    }

    /** Kill drive: drop the listener, every client, NSD, and purge staging. */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        advertiser.stop()
        server?.close(); server = null
        vaultId = null
        _state.value = null
        Log.i(TAG, "vault stopped")
    }

    private companion object { const val TAG = "VaultHost" }
}
