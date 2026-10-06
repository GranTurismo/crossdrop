package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.FileVaultFs
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
 * Owns the hosted LAN vault: a `VaultServer` over a [com.crossdrop.core.vault.VaultFs]
 * backend — the built-in `filesDir/vault` directory or a user-picked SAF
 * folder — an NSD `_crossdrop-vault._tcp.` registration, and the dashboard
 * StateFlow. The vault id is a fresh 16-hex token per run — restarting the
 * drive makes the old beacon unresolvable, and nothing persistent ever
 * touches the wire.
 *
 * While hosting, a high-perf WifiLock + partial WakeLock keep the LAN
 * responsive with the screen off (a drive must answer while pocketed).
 * `TransferService` supplies the foreground-service umbrella.
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
        val rootLabel: String,      // "CrossDrop vault" or the picked folder's name
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Current vault token — discovery filters our own beacon with this. */
    @Volatile var vaultId: String? = null
        private set

    /** What users put in `filesDir/vault` is what the LAN sees — nothing else. */
    private val vaultRoot: File get() = File(context.filesDir, "vault")

    /**
     * Host the drive. [treeUri] = a persisted SAF folder the user picked;
     * null → the built-in vault dir.
     */
    /** Returns false when the folder couldn't be opened or the port refused. */
    fun start(writable: Boolean, usePin: Boolean, label: String? = null,
              treeUri: Uri? = null): Boolean {
        stop()
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val pin = if (usePin) "%04d".format(SecureRandom().nextInt(10_000)) else null

        val fs = if (treeUri != null) {
            runCatching { SafVaultFs(context, treeUri, File(context.cacheDir, "drive-staging")) }
                .getOrElse {
                    Log.w(TAG, "cannot open picked folder", it); return false
                }
        } else FileVaultFs(vaultRoot)

        val rootLabel = if (treeUri != null) {
            DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Shared folder"
        } else "CrossDrop vault"
        val name = label?.takeIf { it.isNotBlank() } ?: "${deviceName()} Drive"

        val srv = VaultServer(
            fs = fs,
            identity = identity,
            deviceName = deviceName,
            writable = writable,
            pin = pin,
            instanceId = id,
        ) { refresh() }
        // Port 8080 is the friendly "drive address" for Finder/Explorer;
        // another service squatting on it → ephemeral fallback.
        runCatching { srv.start(PREFERRED_PORT) }.onFailure {
            Log.w(TAG, "vault failed to bind", it); return false
        }
        server = srv
        vaultId = id

        wifiLock = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "crossdrop-drive")
            .apply { setReferenceCounted(false); acquire() }
        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crossdrop:drive")
            .apply { acquire() }

        val ip = LocalIp.wifiIpv4()
        val url = if (ip != null) "http://$ip:${srv.port}/" else null
        _state.value = State(
            port = srv.port,
            url = url ?: "http://<no-wifi>:${srv.port}/",
            vaultName = name,
            rootLabel = rootLabel,
            writable = writable,
            pin = pin,
        )
        advertiser.start(id, name, srv.port, writable, pin != null)
        Log.i(TAG, "vault up at $url (mode=${if (writable) "rw" else "ro"}," +
            " auth=${if (pin != null) "pin" else "open"}, root=$rootLabel)")
        if (pin != null && com.crossdrop.BuildConfig.DEBUG) {
            Log.i(TAG, "vault pin=$pin")   // debug-only: lets adb flows unlock
        }

        pollJob = scope.launch { while (isActive) { delay(1_000); refresh() } }
        return true
    }

    private fun refresh() {
        val srv = server ?: return
        _state.value = _state.value?.copy(
            clients = srv.connectedPeers.toList(),
            bytesOut = srv.bytesOut.get(),
            bytesIn = srv.bytesIn.get(),
        )
    }

    /** Kill drive: drop the listener, every client, NSD, locks, staging. */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        advertiser.stop()
        server?.close(); server = null
        vaultId = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        _state.value = null
        Log.i(TAG, "vault stopped")
    }

    private companion object {
        const val TAG = "VaultHost"
        const val PREFERRED_PORT = 8080
    }
}
