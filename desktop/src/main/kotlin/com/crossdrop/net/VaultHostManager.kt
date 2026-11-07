package com.crossdrop.net

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultServer
import com.crossdrop.discovery.VaultAdvertiser
import com.crossdrop.platform.AppDirs
import com.crossdrop.platform.Log
import com.crossdrop.platform.Platform
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
     * Folders that must never be a drive root: the filesystem/drive root, the
     * home folder itself (it holds dotfiles, keys, Library/AppData), and
     * anything inside Library, AppData, the OS directories (Windows, Program
     * Files, ProgramData) or CrossDrop's own state. Sub-folders like Documents
     * are fine.
     */
    fun isShareable(dir: File): Boolean {
        val f = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        if (!f.isDirectory || !f.canRead()) return false
        val home = File(System.getProperty("user.home")).canonicalFile
        return !isForbiddenRoot(f, home, AppDirs.support.canonicalFile, Platform.current, System::getenv)
    }

    companion object {
        private const val TAG = "VaultHost"
        private const val PREFERRED_PORT = 8080

        /** Per-drive system folders; matched (case-insensitively) directly under a drive root. */
        private val windowsDriveRootSystemDirs = setOf(
            "\$recycle.bin", "system volume information", "recovery", "windows.old", "config.msi",
            "\$windows.~bt", "\$windows.~ws", "\$winreagent",
        )

        /** Pure policy behind [isShareable]; [f] and [home] are already canonical. */
        internal fun isForbiddenRoot(
            f: File, home: File, support: File, platform: Platform, env: (String) -> String?,
        ): Boolean {
            if (f.parentFile == null || f == home || home.startsWith(f)) return true
            if (f.startsWith(support)) return true
            when (platform) {
                Platform.MAC -> if (f.startsWith(File(home, "Library"))) return true
                Platform.WINDOWS -> {
                    if (f.startsWith(File(home, "AppData"))) return true
                    // Other people's profiles (and Default / All Users) sit beside ours in C:\Users;
                    // Public is shared by design. Skipped when the profile is a drive's top folder.
                    val users = home.parentFile
                    if (users?.parentFile != null && f.startsWith(users) && !f.startsWith(home) &&
                        !f.relativeTo(users).invariantSeparatorsPath.substringBefore('/').equals("public", true)
                    ) return true
                    val osDirs = listOf("SystemRoot", "WINDIR", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData")
                        .mapNotNull { env(it)?.takeIf(String::isNotBlank) }
                        .mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }
                    if (osDirs.any { f.startsWith(it) }) return true
                    // `X:\$Recycle.Bin\…`, `X:\System Volume Information\…`, `X:\Windows.old\…`
                    var p: File? = f
                    while (p != null && p.parentFile?.parentFile != null) p = p.parentFile
                    if (p != null && p.name.lowercase() in windowsDriveRootSystemDirs) return true
                }
                Platform.OTHER -> Unit
            }
            return false
        }
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

}
