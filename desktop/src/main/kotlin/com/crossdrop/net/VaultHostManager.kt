package com.crossdrop.net

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultServer
import com.crossdrop.discovery.VaultAdvertiser
import com.crossdrop.platform.AppDirs
import com.crossdrop.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * Owns the hosted LAN drive: a `VaultServer` over either the built-in vault
 * directory or a folder the user picked, a Bonjour `_crossdrop-vault._tcp.`
 * advert, and the dashboard StateFlow. The vault id is a fresh 16-hex token per
 * run — restarting the drive makes the old beacon unresolvable.
 *
 * Keeping the Mac awake while serving is [com.crossdrop.platform.SleepGuard]'s
 * job; the container toggles it from [state].
 */
class VaultHostManager(
    private val scope: CoroutineScope,
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    private val advertiser: VaultAdvertiser,
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
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Current vault token — discovery filters our own beacon with this. */
    @Volatile var vaultId: String? = null
        private set

    /**
     * Folders that must never be a drive root: the filesystem root, the home
     * folder itself (it holds dotfiles, keys, Library), and anything inside
     * Library or CrossDrop's own state. Sub-folders like Documents are fine.
     */
    fun isShareable(dir: File): Boolean {
        val f = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        if (!f.isDirectory || !f.canRead()) return false
        val home = File(System.getProperty("user.home")).canonicalFile
        if (f.parentFile == null || f == home || home.startsWith(f)) return false
        if (f.startsWith(File(home, "Library")) || f.startsWith(AppDirs.support.canonicalFile)) return false
        return true
    }

    /**
     * Host the drive. [folder] = a directory the user picked; null → the
     * built-in vault dir. Returns false when the folder is unusable or the
     * port could not be bound.
     */
    fun start(writable: Boolean, usePin: Boolean, label: String? = null,
              folder: File? = null): Boolean {
        stop()
        if (folder != null && !isShareable(folder)) {
            Log.w(TAG, "refusing to share ${folder.path}")
            return false
        }
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val pin = if (usePin) "%04d".format(SecureRandom().nextInt(10_000)) else null

        val fs = runCatching {
            if (folder != null) FileVaultFs(folder, stagingRoot = AppDirs.driveStaging)
            else FileVaultFs(AppDirs.vault)
        }.getOrElse { Log.w(TAG, "cannot open drive root", it); return false }

        val rootLabel = folder?.name ?: "CrossDrop vault"
        val name = label?.takeIf { it.isNotBlank() } ?: "${deviceName()} Drive"

        val srv = VaultServer(
            fs = fs,
            identity = identity,
            deviceName = deviceName,
            writable = writable,
            pin = pin,
            instanceId = id,
        ) { refresh() }
        // 8080 is the friendly "drive address" for Finder/Explorer;
        // something else squatting on it → ephemeral fallback.
        runCatching { srv.start(PREFERRED_PORT) }.onFailure {
            Log.w(TAG, "vault failed to bind", it); return false
        }
        server = srv
        vaultId = id

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

    /** Kill drive: drop the listener, every client, the Bonjour record, staging. */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        advertiser.stop()
        server?.close(); server = null
        vaultId = null
        _state.value = null
        Log.i(TAG, "vault stopped")
    }

    private companion object {
        const val TAG = "VaultHost"
        const val PREFERRED_PORT = 8080
    }
}
