package com.crossdrop.net

import com.crossdrop.platform.Log
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * Always-on inbound listener. Bound on an ephemeral port which NSD advertises.
 * Each accepted socket is handed to [onSocket] — the coordinator turns it into
 * a receive session.
 */
class TransferServer(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val onBound: (port: Int) -> Unit,
) {
    var port: Int = 0
        private set

    private var listener: ServerSocket? = null

    /** Set by the coordinator — receives accepted sockets. */
    var onSocket: (Socket) -> Unit = {}

    fun start() {
        if (listener != null) return
        // accept() blocks its thread for the life of the app. On Dispatchers.Default (one thread per
        // CPU core, shared process-wide) that permanently costs a core's worth of the pool and can
        // starve everything else on a 2-core PC — it deadlocked a two-node test on a 2-vCPU runner.
        // Dispatchers.IO exists for exactly this kind of blocking call.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val server = ServerSocket(0)
                listener = server
                port = server.localPort
                onBound(port)
                Log.i(TAG, "listening on :$port")
                while (isActive) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { onSocket(socket) }
                }
            }.onFailure { Log.w(TAG, "listener died", it) }
        }
    }

    fun stop() {
        runCatching { listener?.close() }
        listener = null
        port = 0
    }

    private companion object { const val TAG = "TransferServer" }
}
