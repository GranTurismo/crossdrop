package com.crossdrop.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.crossdrop.core.Peer
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
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
) {
    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session

    private val _incomingOffer = MutableStateFlow<IncomingOffer?>(null)
    val incomingOffer: StateFlow<IncomingOffer?> = _incomingOffer

    private var sessionJob: Job? = null
    private val inboundJobs = mutableListOf<Job>()

    fun start() {
        server.onSocket = ::onInboundSocket
    }

    // ------------------------------------------------------------ sending --

    fun sendTo(peer: Peer, uris: List<Uri>) {
        val endpoint = peer.endpoint ?: return
        sessionJob?.cancel()
        _session.value = SessionState(Direction.SENDING, peer.name, status = SessionStatus.CONNECTING)
        sessionJob = scope.launch {
            val files = uris.mapNotNull { uri -> openOutgoing(uri) }
            if (files.isEmpty()) {
                _session.value = _session.value?.copy(
                    status = SessionStatus.FAILED, error = "nothing readable selected")
                return@launch
            }
            engine.send(endpoint, files).collect { reduce(it, Direction.SENDING, peer.name) }
        }
    }

    private fun openOutgoing(uri: Uri): OutgoingFile? = runCatching {
        var name = "file"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        val meta = FileMeta(name, size.coerceAtLeast(0), context.contentResolver.getType(uri))
        OutgoingFile(meta) {
            context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("cannot open $uri")
        }
    }.getOrNull()

    // ----------------------------------------------------------- incoming --

    private fun onInboundSocket(socket: Socket) {
        val job = scope.launch {
            engine.receive(socket) { peerName, offer ->
                val decision = CompletableDeferred<ReceiveDecision>()
                // Displace (reject) any still-pending offer — one sheet at a time.
                _incomingOffer.value?.decision?.complete(ReceiveDecision.Reject("superseded"))
                _incomingOffer.value = IncomingOffer(peerName, offer, decision)
                val result = runCatching {
                    withTimeoutOrNull(OFFER_TIMEOUT_MS) { decision.await() }
                }.getOrNull() ?: ReceiveDecision.Reject("no response")
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
            pending.decision.complete(ReceiveDecision.Accept { meta -> inbox.sinkFor(meta) })
        } else {
            pending.decision.complete(ReceiveDecision.Reject("declined"))
        }
    }

    // ------------------------------------------------------------ session --

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
        _session.value = when (event) {
            is TransferEvent.Connecting -> base.copy(status = SessionStatus.CONNECTING)
            is TransferEvent.Secured -> base.copy(peerName = event.peerName, sas = event.sas)
            is TransferEvent.AwaitingAccept -> base.copy(offer = event.offer, status = SessionStatus.AWAITING_ACCEPT)
            is TransferEvent.Progress -> base.copy(
                status = SessionStatus.TRANSFERRING,
                bytesDone = event.bytesDone, bytesTotal = event.bytesTotal,
                bytesPerSec = event.bytesPerSec, currentFileName = event.currentFile.name,
            )
            is TransferEvent.Completed -> base.copy(status = SessionStatus.COMPLETED, offer = event.offer)
            is TransferEvent.DeclinedByPeer -> base.copy(status = SessionStatus.DECLINED)
            is TransferEvent.Cancelled -> base.copy(status = SessionStatus.CANCELLED)
            is TransferEvent.Failed -> base.copy(status = SessionStatus.FAILED, error = event.message)
        }
    }

    private companion object {
        const val TAG = "TransferCoordinator"
        const val OFFER_TIMEOUT_MS = 45_000L
    }
}
