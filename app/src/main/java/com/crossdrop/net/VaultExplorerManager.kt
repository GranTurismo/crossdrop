package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crossdrop.core.Peer
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.vault.VaultClient
import com.crossdrop.core.vault.VaultDirEntry
import com.crossdrop.core.vault.VaultFs.VaultError
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Client-side "mount" of a peer vault: owns the `VaultClient`, the browsing
 * state (path/crumbs/entries/selection), a download queue into the inbox, and
 * the loopback media bridge for streaming playback.
 */
class VaultExplorerManager(
    private val scope: CoroutineScope,
    private val context: Context,
    private val identity: SessionIdentity,
    private val deviceName: () -> String,
    private val instanceId: String,
    private val inbox: InboxStore,
    private val history: HistoryStore,
) {
    data class Job(
        val name: String,
        val received: Long = 0,
        val total: Long = 0,
        val done: Boolean = false,
        val failed: String? = null,
        val uploading: Boolean = false,
    )

    data class Ui(
        val peerName: String,
        val writable: Boolean,
        val path: String = "",                       // vault-relative cwd
        val entries: List<VaultDirEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** Vault wants a PIN and we haven't unlocked yet. */
        val pinRequired: Boolean = false,
        val pinFailed: Boolean = false,
        val selected: Set<String> = emptySet(),
        val jobs: List<Job> = emptyList(),
        val doneBytes: Long = 0,
    )

    private val _state = MutableStateFlow<Ui?>(null)
    val state: StateFlow<Ui?> = _state
    val isMounted: Boolean get() = _state.value != null

    private var watcher: CoroutineJob? = null

    /**
     * Android sets this while the app is in the background: nobody is looking at the list, so don't
     * keep the radio busy asking the host about it.
     */
    @Volatile var livePaused = false

    private var client: VaultClient? = null
    private var bridge: VaultMediaBridge? = null
    private var peer: Peer? = null
    private var pin: String? = null

    // ------------------------------------------------------------ mount --

    fun mount(peer: Peer, pin: String? = null) {
        if (peer.endpoint == null) return
        close()
        this.peer = peer
        this.pin = pin
        _state.value = Ui(
            peerName = peer.vault?.vaultName ?: peer.name,
            writable = peer.vault?.writable == true,
            loading = true,
        )
        scope.launch(Dispatchers.IO) {
            try {
                dial()
                refresh("")
                startWatching()
            } catch (e: VaultError) {
                if (e.code == 401) {
                    _state.value = _state.value?.copy(
                        loading = false,
                        pinRequired = true,
                        pinFailed = pin != null,
                    )
                } else {
                    _state.value = _state.value?.copy(
                        loading = false, error = e.message ?: tr("mount failed"))
                }
            } catch (e: Exception) {
                _state.value = _state.value?.copy(
                    loading = false, error = tr("unreachable: {0}", e.message))
            }
        }
    }

    /** Fresh VaultClient + connect + media bridge; client must not be live. */
    private suspend fun dial() {
        val ep = peer?.endpoint ?: throw IllegalStateException("no endpoint")
        runCatching { client?.close() }
        runCatching { bridge?.close() }
        val c = VaultClient(ep.host, ep.port, identity, deviceName(), instanceId)
        c.connect(pin)
        client = c
        bridge = VaultMediaBridge(c).also { it.start() }
    }

    /** Retry mount with a PIN after a `pinRequired` state. */
    fun unlock(pin: String) {
        if (peer == null) return
        this.pin = pin
        _state.value = _state.value?.copy(loading = true, pinRequired = false)
        scope.launch(Dispatchers.IO) {
            try {
                dial()
                refresh("")
                startWatching()
            } catch (e: VaultError) {
                _state.value = _state.value?.copy(
                    loading = false, pinRequired = true, pinFailed = true)
            } catch (e: Exception) {
                _state.value = _state.value?.copy(
                    loading = false, error = tr("unreachable: {0}", e.message))
            }
        }
    }

    // ------------------------------------------------------------- live --

    /**
     * Keep the folder on screen in step with the host: every [WATCH_MS] the current folder is listed
     * again and the screen only changes if something differs — a file added, renamed, grown or removed on
     * the host (or uploaded by another guest) shows up within a couple of seconds, with no pull-to-refresh.
     * One cheap listing per tick, on the connection we already have; it also keeps that connection alive
     * (hosts drop idle sockets). Ends with [close].
     */
    private fun startWatching() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCH_MS)
                if (!livePaused) watchOnce()
            }
        }
    }

    private suspend fun watchOnce() {
        val c = client ?: return
        val ui = _state.value ?: return
        if (ui.loading || ui.pinRequired) return
        val path = ui.path
        val fresh = try {
            c.ls(if (path.isEmpty()) "/" else path)
        } catch (e: VaultError) {
            return                      // e.g. the folder was just removed on the host: keep what is shown
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The socket died (network blip, host restarted): reconnect quietly; the next tick lists again.
            // Not after close(): `client` is already null then.
            if (client === c && currentCoroutineContext().isActive) {
                try { dial() } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (_: Exception) {}
            }
            return
        }
        _state.update { cur ->
            if (cur == null || cur.path != path || cur.loading || cur.entries == fresh) cur
            else {
                val keys = fresh.map { joinPath(path, it.name) }.toSet()
                cur.copy(entries = fresh, selected = cur.selected.filterTo(HashSet()) { it in keys }, error = null)
            }
        }
    }

    // ----------------------------------------------------------- browse --

    fun browse(path: String) = refresh(path)
    fun up() {
        val cur = _state.value?.path ?: return
        refresh(cur.substringBeforeLast('/', ""))
    }

    private fun refresh(path: String) {
        if (client == null) return
        _state.value = _state.value?.copy(loading = true, error = null, path = path)
        scope.launch(Dispatchers.IO) {
            var entries: List<VaultDirEntry>? = null
            try {
                entries = client!!.ls(if (path.isEmpty()) "/" else path)
            } catch (e: VaultError) {
                _state.value = _state.value?.copy(
                    loading = false, error = e.message)
            } catch (e: Exception) {
                // Transport died (idle socket / network blip) — re-dial once.
                try {
                    dial()
                    entries = client!!.ls(if (path.isEmpty()) "/" else path)
                } catch (e2: VaultError) {
                    _state.value = _state.value?.copy(
                        loading = false, error = e2.message)
                } catch (e2: Exception) {
                    _state.value = _state.value?.copy(
                        loading = false, error = tr("lost connection"))
                }
            }
            if (entries != null) _state.value = _state.value?.copy(
                loading = false, path = path, entries = entries,
                selected = emptySet())
        }
    }

    fun toggleSelect(e: VaultDirEntry) {
        val key = joinPath(_state.value?.path ?: "", e.name)
        val s = _state.value?.selected ?: return
        _state.value = _state.value?.copy(
            selected = if (key in s) s - key else s + key)
    }

    fun clearSelection() {
        _state.value = _state.value?.copy(selected = emptySet())
    }

    // --------------------------------------------------------- download --

    /** Queue downloads of [paths] (vault-relative) into the inbox. */
    fun download(paths: List<String>) {
        val c = client ?: return
        for (path in paths) {
            val name = path.substringAfterLast('/')
            scope.launch(Dispatchers.IO) {
                addJob(name, uploading = false)
                var dest: File? = null
                try {
                    val attr = c.stat(path)
                    dest = uniqueInbox(name)
                    FileOutputStream(dest).use { out ->
                        c.pull(path, out = out) { recv ->
                            updateJob(name) { it.copy(received = recv, total = attr.size) }
                        }
                    }
                    inbox.publish(dest)      // into Download/CrossDrop; stays listed in the inbox if that fails
                    updateJob(name) { it.copy(done = true, received = attr.size) }
                    historyAdd(name, 1, attr.size, "RECEIVING")
                } catch (e: Exception) {
                    runCatching { dest?.delete() }      // no half file left in the inbox
                    updateJob(name) { it.copy(failed = e.message ?: tr("failed")) }
                }
            }
        }
    }

    private fun uniqueInbox(name: String): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }.take(96)
        var f = File(inbox.dir, safe)
        var i = 1
        while (f.exists()) {
            f = File(inbox.dir,
                "${safe.substringBeforeLast('.')} ($i).${safe.substringAfterLast('.', "")}")
            i++
        }
        return f
    }

    // ----------------------------------------------------------- upload --

    fun upload(uris: List<Uri>) {
        val c = client ?: return
        val dir = _state.value?.path ?: ""
        for (uri in uris) {
            val of = context.outgoingFileFor(uri) ?: continue
            val name = of.meta.name
            scope.launch(Dispatchers.IO) {
                addJob(name, uploading = true)
                try {
                    val rel = joinPath(dir, name)
                    val done = c.push(rel, of.meta.size, of.meta.mime,
                        open = { of.open() }) { sent ->
                        updateJob(name) { it.copy(received = sent, total = of.meta.size) }
                    }
                    updateJob(name) { it.copy(done = true, received = of.meta.size) }
                    historyAdd(name, 1, of.meta.size, "SENDING")
                    refresh(dir)
                } catch (e: Exception) {
                    updateJob(name) { it.copy(failed = e.message ?: tr("failed")) }
                }
            }
        }
    }

    // ------------------------------------------------------------ media --

    /** Loopback stream URL for in-place playback; null if bridge isn't up. */
    fun streamUrl(path: String): String? = bridge?.urlFor(path)

    fun isMediaPath(name: String, mime: String?): Boolean =
        mime?.let { it.startsWith("video/") || it.startsWith("audio/") ||
            it.startsWith("image/") } == true

    // ------------------------------------------------------------ state --

    private fun joinPath(dir: String, name: String) =
        if (dir.isEmpty()) name else "$dir/$name"

    private fun addJob(name: String, uploading: Boolean) {
        val jobs = (_state.value?.jobs ?: emptyList())
            .filter { it.name != name } + Job(name, uploading = uploading)
        _state.value = _state.value?.copy(jobs = jobs)
    }

    private fun updateJob(name: String, f: (Job) -> Job) {
        val jobs = _state.value?.jobs?.map { if (it.name == name) f(it) else it }
            ?: return
        _state.value = _state.value?.copy(
            jobs = jobs,
            doneBytes = jobs.filter { it.done }.sumOf { it.total })
    }

    private fun historyAdd(name: String, files: Int, bytes: Long, dir: String) {
        val peerName = _state.value?.peerName ?: tr("vault")
        runCatching {
            history.add(HistoryStore.Entry(
                ts = System.currentTimeMillis(), direction = dir,
                peerName = peerName, summary = name, files = files,
                bytes = bytes, status = "COMPLETED"))
        }
    }

    fun clearFinishedJobs() {
        _state.value = _state.value?.copy(
            jobs = _state.value?.jobs?.filter { !it.done && it.failed == null } ?: emptyList())
    }

    fun close() {
        watcher?.cancel(); watcher = null
        runCatching { bridge?.close() }; bridge = null
        runCatching { client?.close() }; client = null
        peer = null
        pin = null
        _state.value = null
    }

    private companion object {
        const val TAG = "VaultExplorer"
        /** How often the open folder is checked against the host. */
        const val WATCH_MS = 1_500L
    }
}
