package com.crossdrop.net

import com.crossdrop.core.Peer
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.protocol.BatchInfo
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.core.transfer.InboundRequest
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.SendOptions
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import com.crossdrop.core.transfer.TrustStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Shared verbatim with :desktop (copied at build time, like the shared UI) — no android.* here.

enum class Direction { SENDING, RECEIVING }

enum class SessionStatus {
    CONNECTING, AWAITING_ACCEPT, TRANSFERRING, COMPLETED, FAILED, DECLINED, CANCELLED
}

val SessionStatus.isTerminal: Boolean
    get() = this == SessionStatus.COMPLETED || this == SessionStatus.FAILED ||
            this == SessionStatus.DECLINED || this == SessionStatus.CANCELLED

val SessionStatus.isProblem: Boolean
    get() = this == SessionStatus.FAILED || this == SessionStatus.DECLINED || this == SessionStatus.CANCELLED

/** One connection: a send to one device, or one receive. */
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
    val id: String = "",
    /** Sessions started together (one send to several devices) share it; a receive is its own group. */
    val groupId: String = "",
    val kind: String? = null,
    /** Shown by its own screen (live camera, switch phones, text) — never opens the transfer view. */
    val quiet: Boolean = false,
) {
    val fraction: Float get() = when (status) {
        SessionStatus.COMPLETED -> 1f
        else -> (bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/** The sessions of one send (one per device) or of one receive, as the transfer view shows them. */
data class TransferGroup(val id: String, val sessions: List<SessionState>) {
    val primary: SessionState get() = sessions.first()
    val direction: Direction get() = primary.direction
    val multi: Boolean get() = sessions.size > 1
    val isTerminal: Boolean get() = sessions.all { it.status.isTerminal }
    val completed: Int get() = sessions.count { it.status == SessionStatus.COMPLETED }
    val problems: Int get() = sessions.count { it.status.isProblem }
    val bytesTotal: Long get() = sessions.sumOf { it.bytesTotal.coerceAtLeast(1) }
    val bytesDone: Long get() = sessions.sumOf {
        if (it.status == SessionStatus.COMPLETED) it.bytesTotal.coerceAtLeast(it.bytesDone) else it.bytesDone
    }
    val bytesPerSec: Long get() = sessions.filter { !it.status.isTerminal }.sumOf { it.bytesPerSec }
    val fraction: Float get() = (bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** An offer waiting for the human's accept/decline tap. */
class IncomingOffer(
    val peerName: String,
    val offer: Offer,
    val sas: String,
    internal val answer: CompletableDeferred<Boolean>,
) {
    val kind: String? get() = offer.kind
    /** Inline text of a text/link offer (null for files, or text too long to inline). */
    val text: String? get() = offer.text?.takeIf { offer.kind == OfferKind.TEXT }
}

/** A photo or video that arrived during a live camera session. */
data class LiveShot(val file: File, val name: String, val mime: String?, val atMs: Long)

/** Someone's camera feeding this device: every shot they take lands here without a prompt. */
data class LiveFeed(
    val grantId: String,
    val peerName: String,
    val shots: List<LiveShot> = emptyList(),
    /** The shot arriving right now, if any. */
    val incoming: SessionState? = null,
)

/** Where received files go. */
interface ReceivePort {
    /** A fresh, collision-free inbox file for [meta] (the v1 stream path writes it). */
    fun newInboxFile(meta: FileMeta): File
    /** The inbox's chunk store (v2/v3 delta/resume path). */
    val inboxDelta: DeltaReceiver
}

/**
 * Receiving end of "Switch phones": each batch is staged in [batchStore] and handed to
 * [importBatch] once it has arrived whole.
 */
interface SwitchReceiver {
    fun begin(peerName: String, batch: BatchInfo?)
    val batchStore: DeltaReceiver
    fun batchFile(meta: FileMeta): File
    /** Runs off the transfer thread; imports [files] (parallel to `offer.files`) and deletes them. */
    suspend fun importBatch(peerName: String, offer: Offer, files: List<File>)
    fun batchEnded(status: SessionStatus, error: String?)
}

/**
 * Every transfer in flight, both directions, on any platform. The UI observes [sessions],
 * [focusedGroup], [incomingOffer] and [live]; platforms supply files and storage.
 *
 * - A send to several devices runs one session per device, side by side, as one group.
 * - Offers of kind text, camera and switch are routed to their own screens instead of the
 *   transfer view. Camera and switch receivers hand out a [TrustStore] grant on the first accept so
 *   the rest of the shots/batches arrive without asking again.
 */
class TransferHub(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val port: ReceivePort,
    /** History hook: called once per session as it ends (camera shots and switch batches excluded). */
    private val onEnded: (Direction, SessionState) -> Unit,
) {
    val trust = TrustStore()
    var switchReceiver: SwitchReceiver? = null

    private val _sessions = MutableStateFlow<List<SessionState>>(emptyList())
    val sessions: StateFlow<List<SessionState>> = _sessions

    private val focusedId = MutableStateFlow<String?>(null)

    /** Groups the transfer view can show (quiet ones excluded), oldest first. */
    val groups: StateFlow<List<TransferGroup>> = _sessions.map { all ->
        all.filter { !it.quiet }.groupBy { it.groupId }.map { (id, s) -> TransferGroup(id, s) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val focusedGroup: StateFlow<TransferGroup?> = combine(groups, focusedId) { g, id ->
        g.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True while anything (including quiet sessions) is still moving bytes. */
    val busy: StateFlow<Boolean> = _sessions.map { s -> s.any { !it.status.isTerminal } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _incoming = MutableStateFlow<IncomingOffer?>(null)
    val incomingOffer: StateFlow<IncomingOffer?> = _incoming

    private val _live = MutableStateFlow<LiveFeed?>(null)
    val live: StateFlow<LiveFeed?> = _live

    private val jobs = ConcurrentHashMap<String, Job>()
    private val observers = ConcurrentHashMap<String, (SessionState) -> Unit>()
    private val specs = ConcurrentHashMap<String, SendSpec>()

    private class SendSpec(
        val peers: Map<String, Peer>,
        val files: suspend () -> List<OutgoingFile>,
        val options: SendOptions,
        val quiet: Boolean,
    )

    /** Dev-only: auto-accept the next inbound offer (loopback self-tests). */
    @Volatile var autoAcceptOnce = false

    // ------------------------------------------------------------ sending --

    /**
     * Send the same files to every peer in [peers] at once. [files] is resolved once per device, so
     * each connection opens its own streams. Returns the group id.
     */
    fun send(
        peers: List<Peer>,
        files: suspend () -> List<OutgoingFile>,
        options: SendOptions = SendOptions(),
        quiet: Boolean = false,
    ): String {
        val groupId = newId()
        val targets = peers.filter { it.endpoint != null }.associateBy { newId() }
        if (targets.isEmpty()) return groupId
        specs[groupId] = SendSpec(targets, files, options, quiet)
        _sessions.update { list ->
            list + targets.map { (sid, peer) ->
                SessionState(
                    Direction.SENDING, peer.name, id = sid, groupId = groupId,
                    kind = options.kind, quiet = quiet,
                )
            }
        }
        if (!quiet) focusedId.value = groupId
        targets.forEach { (sid, peer) -> launchSend(sid, peer, files, options) }
        return groupId
    }

    /**
     * Run one send to completion on the caller's coroutine; [onUpdate] sees every state change.
     * Returns the final state (the session is then dropped from [sessions]).
     */
    suspend fun sendNow(
        peer: Peer,
        files: List<OutgoingFile>,
        options: SendOptions,
        quiet: Boolean = true,
        onUpdate: (SessionState) -> Unit = {},
    ): SessionState {
        val sid = newId()
        observers[sid] = onUpdate
        _sessions.update { it + SessionState(Direction.SENDING, peer.name, id = sid, groupId = sid, kind = options.kind, quiet = quiet) }
        try {
            runSend(sid, peer, files, options)
        } finally {
            observers.remove(sid)
        }
        val last = _sessions.value.firstOrNull { it.id == sid }
        _sessions.update { list -> list.filterNot { it.id == sid } }
        return when {
            last == null -> SessionState(Direction.SENDING, peer.name, status = SessionStatus.FAILED)
            // The flow ended without a verdict (cancelled coroutine): call it cancelled.
            !last.status.isTerminal -> last.copy(status = SessionStatus.CANCELLED)
            else -> last
        }
    }

    private fun launchSend(sid: String, peer: Peer, files: suspend () -> List<OutgoingFile>, options: SendOptions) {
        jobs[sid] = scope.launch {
            val list = runCatching { files() }.getOrElse { emptyList() }
            if (list.isEmpty()) {
                reduce(sid, TransferEvent.Failed("nothing readable selected"))
                return@launch
            }
            runSend(sid, peer, list, options)
        }.also { job -> job.invokeOnCompletion { jobs.remove(sid) } }
    }

    private suspend fun runSend(sid: String, peer: Peer, files: List<OutgoingFile>, options: SendOptions) {
        val endpoint = peer.endpoint ?: run { reduce(sid, TransferEvent.Failed("device has no address")); return }
        val kind = options.kind
        val opts = if (kind == OfferKind.CAMERA || kind == OfferKind.SWITCH)
            options.copy(trust = options.trust ?: trust.credentialFor(peer.instanceId, kind)) else options
        engine.send(endpoint, files, opts).collect { ev ->
            when (ev) {
                is TransferEvent.TrustGranted -> trust.remember(peer.instanceId, ev.grant)
                // A declined proof means the receiver stopped trusting us — ask again next time.
                is TransferEvent.DeclinedByPeer -> if (kind != null) trust.forget(peer.instanceId, kind)
                else -> Unit
            }
            reduce(sid, ev)
        }
    }

    /** Resend to the devices of [groupId] that failed, declined or were cancelled. */
    fun retryFailed(groupId: String) {
        val spec = specs[groupId] ?: return
        val retry = _sessions.value.filter { it.groupId == groupId && it.status.isProblem }
        if (retry.isEmpty()) return
        _sessions.update { list ->
            list.map { s ->
                if (retry.any { it.id == s.id }) SessionState(Direction.SENDING, s.peerName, id = s.id, groupId = groupId, kind = s.kind, quiet = s.quiet)
                else s
            }
        }
        if (!spec.quiet) focusedId.value = groupId
        retry.forEach { s -> spec.peers[s.id]?.let { launchSend(s.id, it, spec.files, spec.options) } }
    }

    fun cancelSession(id: String) {
        jobs.remove(id)?.cancel()
        _sessions.update { list ->
            list.map { if (it.id == id && !it.status.isTerminal) it.copy(status = SessionStatus.CANCELLED) else it }
        }
    }

    fun cancelGroup(groupId: String) {
        _sessions.value.filter { it.groupId == groupId }.forEach { cancelSession(it.id) }
    }

    /** Forget the group's finished sessions (history already has them). */
    fun dismissGroup(groupId: String) {
        _sessions.update { list -> list.filterNot { it.groupId == groupId && it.status.isTerminal } }
        if (_sessions.value.none { it.groupId == groupId }) {
            specs.remove(groupId)
            if (focusedId.value == groupId) focusedId.value = null
        }
    }

    fun focus(groupId: String?) { focusedId.value = groupId }

    /** Drop finished quiet sessions (camera shots, switch batches) once their screens are done with them. */
    fun pruneQuiet(kind: String) {
        _sessions.update { list -> list.filterNot { it.quiet && it.kind == kind && it.status.isTerminal } }
    }

    // ----------------------------------------------------------- incoming --

    /** Serve one inbound connection accepted by the transfer listener. */
    fun onInboundSocket(socket: Socket) {
        val sid = newId()
        val route = Route()
        val job = scope.launch {
            engine.receiveRequest(socket) { req -> decide(sid, req, route) }.collect { ev ->
                if (_sessions.value.none { it.id == sid }) return@collect   // not accepted (yet)
                reduce(sid, ev)
                if (ev is TransferEvent.Completed) onReceived(route, ev.offer)
                if (ev is TransferEvent.Failed || ev is TransferEvent.Cancelled) {
                    if (route.kind == OfferKind.SWITCH) switchReceiver?.batchEnded(
                        if (ev is TransferEvent.Failed) SessionStatus.FAILED else SessionStatus.CANCELLED,
                        (ev as? TransferEvent.Failed)?.message,
                    )
                    if (route.kind == OfferKind.CAMERA) _live.update { it?.copy(incoming = null) }
                }
            }
        }
        jobs[sid] = job
        job.invokeOnCompletion { jobs.remove(sid) }
    }

    /** What an accepted offer turned into, so completion knows where its files went. */
    private class Route {
        var kind: String? = null
        var peerName: String = ""
        val v1Files = mutableListOf<File>()
        var store: DeltaReceiver? = null
        var switchStore: Boolean = false
    }

    private suspend fun decide(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        val offer = req.offer
        route.peerName = req.peerName

        trust.match(req)?.let { granted ->
            return when (offer.kind) {
                OfferKind.CAMERA -> acceptCamera(sid, req, route, granted.id, grant = null)
                OfferKind.SWITCH -> acceptSwitch(sid, req, route, grant = null)
                else -> acceptFiles(sid, req, route)
            }
        }

        val accepted = if (autoAcceptOnce) {
            autoAcceptOnce = false
            true
        } else {
            val pending = IncomingOffer(req.peerName, offer, req.sas, CompletableDeferred())
            // One prompt at a time: a newer offer displaces (declines) an unanswered one.
            _incoming.value?.answer?.complete(false)
            _incoming.value = pending
            val answer = runCatching { withTimeoutOrNull(OFFER_TIMEOUT_MS) { pending.answer.await() } }.getOrNull()
            if (_incoming.value === pending) _incoming.value = null
            answer == true
        }
        if (!accepted) return ReceiveDecision.Reject("declined")

        return when {
            offer.kind == OfferKind.TEXT && offer.text != null -> acceptText(sid, req, route)
            offer.kind == OfferKind.CAMERA -> {
                val g = trust.grant(OfferKind.CAMERA, req.peerName, LIVE_TTL_MS)
                acceptCamera(sid, req, route, g.id, g)
            }
            offer.kind == OfferKind.SWITCH && switchReceiver != null -> {
                trust.revokeKind(OfferKind.SWITCH)
                val g = trust.grant(OfferKind.SWITCH, req.peerName, SWITCH_TTL_MS)
                switchReceiver?.begin(req.peerName, offer.batch)
                acceptSwitch(sid, req, route, g)
            }
            else -> acceptFiles(sid, req, route)
        }
    }

    fun respondToOffer(accept: Boolean) {
        val pending = _incoming.value ?: return
        _incoming.value = null
        pending.answer.complete(accept)
    }

    private fun register(sid: String, req: InboundRequest, kind: String?, quiet: Boolean): SessionState {
        val s = SessionState(
            Direction.RECEIVING, req.peerName, sas = req.sas, offer = req.offer,
            bytesTotal = req.offer.files.sumOf { it.size }.coerceAtLeast(1),
            status = SessionStatus.TRANSFERRING, id = sid, groupId = sid, kind = kind, quiet = quiet,
        )
        _sessions.update { it + s }
        if (!quiet) {
            val current = focusedGroup.value
            if (current == null || current.isTerminal) focusedId.value = sid
        }
        return s
    }

    private fun inboxSink(route: Route): (FileMeta) -> OutputStream = { meta ->
        val f = port.newInboxFile(meta)
        synchronized(route.v1Files) { route.v1Files += f }
        f.outputStream()
    }

    private fun acceptFiles(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        route.kind = req.offer.kind
        route.store = port.inboxDelta
        register(sid, req, req.offer.kind, quiet = false)
        return ReceiveDecision.Accept(inboxSink(route), port.inboxDelta)
    }

    private fun acceptText(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        route.kind = OfferKind.TEXT
        register(sid, req, OfferKind.TEXT, quiet = true)
        // The text came inline with the offer; the file copy is only for older receivers.
        return ReceiveDecision.Accept({ DiscardStream }, delta = null)
    }

    private fun acceptCamera(
        sid: String, req: InboundRequest, route: Route, grantId: String,
        grant: com.crossdrop.core.protocol.TrustGrant?,
    ): ReceiveDecision {
        route.kind = OfferKind.CAMERA
        route.store = port.inboxDelta
        val s = register(sid, req, OfferKind.CAMERA, quiet = true)
        _live.update { cur ->
            if (cur?.grantId == grantId) cur.copy(incoming = s)
            else LiveFeed(grantId, req.peerName, incoming = s)
        }
        return ReceiveDecision.Accept(inboxSink(route), port.inboxDelta, grant)
    }

    private fun acceptSwitch(
        sid: String, req: InboundRequest, route: Route,
        grant: com.crossdrop.core.protocol.TrustGrant?,
    ): ReceiveDecision {
        val rx = switchReceiver ?: return acceptFiles(sid, req, route)
        route.kind = OfferKind.SWITCH
        route.store = rx.batchStore
        route.switchStore = true
        register(sid, req, OfferKind.SWITCH, quiet = true)
        val sink: (FileMeta) -> OutputStream = { meta ->
            val f = rx.batchFile(meta)
            synchronized(route.v1Files) { route.v1Files += f }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
        return ReceiveDecision.Accept(sink, rx.batchStore, grant)
    }

    /** Files of a completed receive, parallel to `offer.files`. */
    private fun receivedFiles(route: Route, offer: Offer): List<File> {
        synchronized(route.v1Files) {
            if (route.v1Files.size == offer.files.size) return route.v1Files.toList()
        }
        val store = route.store ?: return emptyList()
        return offer.files.map { store.targetFor(it) }
    }

    private fun onReceived(route: Route, offer: Offer) {
        when (route.kind) {
            OfferKind.CAMERA -> {
                val now = System.currentTimeMillis()
                val shots = receivedFiles(route, offer).mapIndexed { i, f ->
                    LiveShot(f, offer.files[i].name, offer.files[i].mime, now)
                }
                _live.update { it?.copy(shots = (it.shots + shots).takeLast(MAX_LIVE_SHOTS), incoming = null) }
            }
            OfferKind.SWITCH -> if (route.switchStore) {
                val files = receivedFiles(route, offer)
                scope.launch { switchReceiver?.importBatch(route.peerName, offer, files) }
            }
        }
    }

    /** End the live camera session: the sender's next shot will ask again. */
    fun stopLive() {
        _live.value?.let { trust.revoke(it.grantId) }
        _live.value = null
        pruneQuiet(OfferKind.CAMERA)
    }

    // ------------------------------------------------------------- state --

    private fun reduce(id: String, event: TransferEvent) {
        var ended: SessionState? = null
        _sessions.update { list ->
            list.map { cur ->
                if (cur.id != id) return@map cur
                val next = when (event) {
                    is TransferEvent.Connecting -> cur.copy(status = SessionStatus.CONNECTING)
                    is TransferEvent.Secured -> cur.copy(
                        peerName = if (cur.direction == Direction.SENDING) event.peerName else cur.peerName,
                        sas = event.sas,
                    )
                    is TransferEvent.AwaitingAccept -> cur.copy(
                        offer = event.offer, status = SessionStatus.AWAITING_ACCEPT,
                        bytesTotal = event.offer.files.sumOf { it.size }.coerceAtLeast(1),
                    )
                    is TransferEvent.Progress -> if (cur.status.isTerminal) cur else cur.copy(
                        status = SessionStatus.TRANSFERRING,
                        bytesDone = event.bytesDone, bytesTotal = event.bytesTotal,
                        bytesPerSec = event.bytesPerSec, bytesSkipped = event.bytesSkipped,
                        currentFileName = event.currentFile.name,
                    )
                    is TransferEvent.Completed -> cur.copy(
                        status = SessionStatus.COMPLETED, offer = event.offer,
                        bytesDone = cur.bytesTotal.coerceAtLeast(cur.bytesDone), bytesPerSec = 0,
                    )
                    is TransferEvent.TrustGranted -> cur
                    is TransferEvent.DeclinedByPeer -> cur.copy(status = SessionStatus.DECLINED, bytesPerSec = 0)
                    is TransferEvent.Cancelled -> cur.copy(status = SessionStatus.CANCELLED, bytesPerSec = 0)
                    is TransferEvent.Failed -> cur.copy(status = SessionStatus.FAILED, error = event.message, bytesPerSec = 0)
                }
                // Record the outcome once, on the transition into a terminal state.
                if (next.status.isTerminal && !cur.status.isTerminal) ended = next
                next
            }
        }
        observers[id]?.let { obs -> _sessions.value.firstOrNull { it.id == id }?.let { runCatching { obs(it) } } }
        ended?.let { s ->
            if (s.kind != OfferKind.CAMERA && s.kind != OfferKind.SWITCH) runCatching { onEnded(s.direction, s) }
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    private object DiscardStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        /** How long an inbound offer waits for the human before it is declined. */
        const val OFFER_TIMEOUT_MS = 45_000L
        /** A live camera session lapses after this long without a shot. */
        const val LIVE_TTL_MS = 30 * 60_000L
        /** Switch phones: longest pause between batches before the receiver asks again. */
        const val SWITCH_TTL_MS = 30 * 60_000L
        const val MAX_LIVE_SHOTS = 60
    }
}
