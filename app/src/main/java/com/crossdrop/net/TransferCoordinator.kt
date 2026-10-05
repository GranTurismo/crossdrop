package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket

enum class Direction { SENDING, RECEIVING }

enum class SessionStatus {
    CONNECTING, AWAITING_ACCEPT, TRANSFERRING, COMPLETED, FAILED, DECLINED, CANCELLED
}

val SessionStatus.isTerminal: Boolean
    get() = this == SessionStatus.COMPLETED || this == SessionStatus.FAILED ||
            this == SessionStatus.DECLINED || this == SessionStatus.CANCELLED

data class SessionState(
    val direction: Direction,
    val peerName: String,
    val sas: String? = null,
    val offer: Offer? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 1,
    val bytesPerSec: Long = 0,
    /** v2 delta: bytes satisfied from local sources instead of the wire. */
    val bytesSkipped: Long = 0,
    val currentFileName: String? = null,
    val status: SessionStatus = SessionStatus.CONNECTING,
    val error: String? = null,
) {
    val fraction: Float get() = (bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** An offer waiting for the human's accept/decline tap. */
class IncomingOffer(
    val peerName: String,
    val offer: Offer,
    internal val decision: CompletableDeferred<ReceiveDecision>,
)

/**
 * Single source of truth for the "what is happening right now" transfer state.
 * The UI never touches sockets — it observes [session] / [incomingOffer] and
 * calls intent-style methods.
 */
class TransferCoordinator(
    private val scope: CoroutineScope,
    private val context: Context,
    private val engine: TransferEngine,
    private val server: TransferServer,
    private val inbox: InboxStore,
    private val history: HistoryStore,
) {
    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session

    private val _incomingOffer = MutableStateFlow<IncomingOffer?>(null)
    val incomingOffer: StateFlow<IncomingOffer?> = _incomingOffer

    private var sessionJob: Job? = null
    private val inboundJobs = mutableListOf<Job>()

    /** Dev-only: auto-accept the next inbound offer — set by loopback
     *  self-tests so they can complete without a sheet tap. */
    @Volatile var autoAcceptOnce = false

    fun start() {
        server.onSocket = ::onInboundSocket
    }

    // ------------------------------------------------------------ sending --

    fun sendTo(peer: Peer, uris: List<Uri>) {
        val endpoint = peer.endpoint ?: return
        sessionJob?.cancel()
        _session.value = SessionState(Direction.SENDING, peer.name, status = SessionStatus.CONNECTING)
        sessionJob = scope.launch {
            val files = uris.mapNotNull { uri -> context.outgoingFileFor(uri) }
            if (files.isEmpty()) {
                _session.value = _session.value?.copy(
                    status = SessionStatus.FAILED, error = "nothing readable selected")
                return@launch
            }
            engine.send(endpoint, files).collect { reduce(it, Direction.SENDING, peer.name) }
        }
    }

    // ----------------------------------------------------------- incoming --

    private fun onInboundSocket(socket: Socket) {
        val job = scope.launch {
            engine.receive(socket) { peerName, offer ->
                Log.i(TAG, "offer from '$peerName': ${offer.files.size} file(s), id=${offer.transferId}")
                val result = if (autoAcceptOnce) {
                    autoAcceptOnce = false
                    Log.i(TAG, "auto-accepting loopback offer")
                    ReceiveDecision.Accept(
                        sinkFor = { meta -> inbox.sinkFor(meta) }, delta = inbox.delta)
                } else {
                    val decision = CompletableDeferred<ReceiveDecision>()
                    // Displace (reject) any still-pending offer — one sheet at a time.
                    _incomingOffer.value?.decision?.complete(ReceiveDecision.Reject("superseded"))
                    _incomingOffer.value = IncomingOffer(peerName, offer, decision)
                    runCatching {
                        withTimeoutOrNull(OFFER_TIMEOUT_MS) { decision.await() }
                    }.getOrNull() ?: ReceiveDecision.Reject("no response")
                }
                Log.i(TAG, "decision for ${offer.transferId}: $result")
                if (_incomingOffer.value?.offer?.transferId == offer.transferId) {
                    _incomingOffer.value = null
                }
                if (result is ReceiveDecision.Accept) {
                    _session.value = SessionState(Direction.RECEIVING, peerName, offer = offer,
                        status = SessionStatus.TRANSFERRING)
                }
                result
            }.collect { reduce(it, Direction.RECEIVING, _session.value?.peerName ?: "peer") }
        }
        inboundJobs += job
        job.invokeOnCompletion { inboundJobs -= job }
    }

    fun respondToOffer(accept: Boolean) {
        val pending = _incomingOffer.value ?: return
        _incomingOffer.value = null
        if (accept) {
            pending.decision.complete(ReceiveDecision.Accept(
                sinkFor = { meta -> inbox.sinkFor(meta) },
                delta = inbox.delta,
            ))
        } else {
            pending.decision.complete(ReceiveDecision.Reject("declined"))
        }
    }

    // ------------------------------------------------------------ session --

    /**
     * Debug: send a generated file to a listener — our own by default, or a
     * remote [host] for cross-device testing without discovery. Exercises the
     * whole stack — handshake, offer sheet, encrypted DATA path, inbox.
     */
    fun selfTransfer(host: String = "127.0.0.1", port: Int = server.port) {
        val target = if (port == 0) server.port else port
        if (target == 0) return
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val file = OutgoingFile(FileMeta("self-test.bin", bytes.size.toLong(), "application/octet-stream")) {
            java.io.ByteArrayInputStream(bytes)
        }
        // Loopback sends accept their own offer sheet.
        if (host == "127.0.0.1" || host == "::1") autoAcceptOnce = true
        sessionJob?.cancel()
        _session.value = SessionState(Direction.SENDING, "self", status = SessionStatus.CONNECTING)
        sessionJob = scope.launch {
            engine.send(Endpoint(host, target), listOf(file))
                .collect { reduce(it, Direction.SENDING, "self") }
        }
    }

    fun cancelSession() {
        sessionJob?.cancel()
        if (_session.value?.direction == Direction.RECEIVING) {
            inboundJobs.forEach { it.cancel() }
        }
        _session.value = _session.value?.copy(status = SessionStatus.CANCELLED)
    }

    fun dismissSession() {
        if (_session.value?.status?.isTerminal == true) _session.value = null
    }

    private fun reduce(event: TransferEvent, direction: Direction, peerName: String) {
        val cur = _session.value
        val base = cur ?: SessionState(direction, peerName)
        val next = when (event) {
            is TransferEvent.Connecting -> base.copy(status = SessionStatus.CONNECTING)
            is TransferEvent.Secured -> base.copy(peerName = event.peerName, sas = event.sas)
            is TransferEvent.AwaitingAccept -> base.copy(offer = event.offer, status = SessionStatus.AWAITING_ACCEPT)
            is TransferEvent.Progress -> base.copy(
                status = SessionStatus.TRANSFERRING,
                bytesDone = event.bytesDone, bytesTotal = event.bytesTotal,
                bytesPerSec = event.bytesPerSec, bytesSkipped = event.bytesSkipped,
                currentFileName = event.currentFile.name,
            )
            is TransferEvent.Completed -> base.copy(status = SessionStatus.COMPLETED, offer = event.offer)
            is TransferEvent.DeclinedByPeer -> base.copy(status = SessionStatus.DECLINED)
            is TransferEvent.Cancelled -> base.copy(status = SessionStatus.CANCELLED)
            is TransferEvent.Failed -> base.copy(status = SessionStatus.FAILED, error = event.message)
        }
        // Record the outcome once, on the transition into a terminal state.
        // Direction comes from the caller — _session may already hold the
        // opposite leg of a loopback, so it can't be trusted here.
        if (next.status.isTerminal && cur?.status?.isTerminal != true) record(direction, next)
        _session.value = next
    }

    private fun record(direction: Direction, s: SessionState) {
        val files = s.offer?.files.orEmpty()
        runCatching {
            history.add(
                HistoryStore.Entry(
                    ts = System.currentTimeMillis(),
                    direction = direction.name,
                    peerName = s.peerName,
                    summary = when {
                        files.size == 1 -> files.first().name
                        files.size > 1 -> "${files.size} files"
                        s.currentFileName != null -> s.currentFileName
                        else -> "—"
                    },
                    files = files.size,
                    bytes = if (files.isNotEmpty()) files.sumOf { it.size } else s.bytesDone,
                    status = s.status.name,
                )
            )
        }
    }

    private companion object {
        const val TAG = "TransferCoordinator"
        const val OFFER_TIMEOUT_MS = 45_000L
    }
}
