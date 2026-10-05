package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.share.HttpShareServer
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Owns the "send to anyone" session: an [HttpShareServer] plus the UI state
 * driving the QR overlay. The receiver needs nothing but a browser — the QR
 * encodes a capability URL (`http://<wifi-ip>:<port>/<token>/`) that is both
 * the address and the secret.
 */
class QrShareManager(
    private val scope: CoroutineScope,
    private val context: Context,
    private val deviceName: () -> String,
    private val history: HistoryStore,
) {
    data class State(
        val url: String,
        val deviceName: String,
        val files: List<FileMeta>,
        val connected: Boolean = false,
        val downloadsStarted: Int = 0,
        val downloadsDone: Int = 0,
        val bytesServed: Long = 0,
    ) {
        /** Every file delivered at least once. */
        val delivered: Boolean get() = files.isNotEmpty() && downloadsDone >= files.size
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var server: HttpShareServer? = null
    private var pollJob: kotlinx.coroutines.Job? = null
    @Volatile private var clientSeen = false

    val isSharing: Boolean get() = _state.value != null

    fun start(uris: List<Uri>) {
        val files = uris.mapNotNull { context.outgoingFileFor(it) }
        if (files.isEmpty()) { Log.w(TAG, "nothing readable selected"); return }
        launch(files)
    }

    /** Debug hook: share the same generated 4 MiB payload the self-test uses. */
    fun startDebug() {
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val file = OutgoingFile(FileMeta("qr-share-test.bin", bytes.size.toLong(),
            "application/octet-stream")) { java.io.ByteArrayInputStream(bytes) }
        launch(listOf(file))
    }

    private fun launch(files: List<OutgoingFile>) {
        stop()
        val ip = LocalIp.wifiIpv4() ?: run {
            Log.w(TAG, "no usable IPv4 — not on Wi-Fi?")
            return
        }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val srv = HttpShareServer(files, deviceName(), token) { ev ->
            if (ev == HttpShareServer.Event.CLIENT_CONNECTED) clientSeen = true
            refresh()
        }
        runCatching { srv.start() }.onFailure {
            Log.w(TAG, "share server failed to bind", it); return
        }
        server = srv
        val url = "http://$ip:${srv.port}/$token/"
        Log.i(TAG, "share up at $url")
        _state.value = State(url, deviceName(), files.map { it.meta })
        pollJob = scope.launch {
            while (isActive) { delay(1_000); refresh() }
        }
    }

    private fun refresh() {
        val srv = server ?: return
        val s = srv.stats()
        _state.value = _state.value?.copy(
            connected = clientSeen || s.downloadsStarted > 0,
            downloadsStarted = s.downloadsStarted,
            downloadsDone = s.downloadsDone,
            bytesServed = s.bytesServed,
        )
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        server?.close(); server = null
        clientSeen = false
        val s = _state.value
        _state.value = null
        if (s != null) runCatching {
            history.add(HistoryStore.Entry(
                ts = System.currentTimeMillis(),
                direction = "SENDING",
                peerName = "browser share",
                summary = if (s.files.size == 1) s.files.first().name else "${s.files.size} files",
                files = s.files.size,
                bytes = if (s.bytesServed > 0) s.bytesServed else s.files.sumOf { it.size },
                status = if (s.downloadsDone > 0) "COMPLETED" else "CANCELLED",
            ))
        }
    }

    private companion object { const val TAG = "QrShare" }
}
