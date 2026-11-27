package com.crossdrop.net

import android.util.Log
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
        scope.launch {
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
        closeRendezvous()
    }

    private var rendezvous: ServerSocket? = null

    /**
     * A second listener on a fixed port ([com.crossdrop.core.connect.DirectWifi.PORT]) while this phone runs the
     * CrossDrop Wi-Fi, so a device that joined it in one tap (no code, so no port) knows where to announce itself.
     * Same handling as the usual listener. If the port is taken, joiners still find us by discovery.
     */
    @Synchronized
    fun openRendezvous(fixedPort: Int) {
        if (rendezvous != null) return
        val server = ServerSocket()
        try {
            // Reusable at once: the link may be stopped and started again within seconds.
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(fixedPort))
        } catch (e: Exception) {
            Log.w(TAG, "rendezvous port $fixedPort unavailable: ${e.message}")
            runCatching { server.close() }
            return
        }
        rendezvous = server
        Log.i(TAG, "also listening on :$fixedPort (CrossDrop Wi-Fi)")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { onSocket(socket) }
            }
            // Closed by closeRendezvous, or accept failed: either way it can be opened again.
            synchronized(this@TransferServer) { if (rendezvous === server) rendezvous = null }
            runCatching { server.close() }
        }
    }

    @Synchronized
    fun closeRendezvous() {
        runCatching { rendezvous?.close() }
        rendezvous = null
    }

    private companion object { const val TAG = "TransferServer" }
}
