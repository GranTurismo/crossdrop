package com.crossdrop.net

import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.pairing.PairedDevice
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.core.protocol.VerifyItem
import com.crossdrop.core.shake.ShakeMatcher
import com.crossdrop.core.transfer.ControlHandler
import com.crossdrop.core.transfer.FailureAdvice
import com.crossdrop.core.transfer.PairSecret
import com.crossdrop.core.transfer.FailureKind
import com.crossdrop.core.transfer.ResumableOffers
import com.crossdrop.core.transfer.ResumePolicy
import com.crossdrop.core.contacts.VContact
import com.crossdrop.core.delta.DeltaReceiver
import com.crossdrop.core.i18n.tr
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
import kotlinx.coroutines.delay
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
    /** What to try, for a failed or declined session (see [FailureAdvice]). */
    val hint: String? = null,
    /** A send whose connection dropped and which is waiting for the device to come back, then trying again. */
    val resuming: Boolean = false,
    /** Why it failed, once it did (see [FailureAdvice]); null otherwise. */
    val failure: FailureKind? = null,
    /** What the other device runs on ([com.crossdrop.core.Platforms]) when known, e.g. to warn that an iPhone must stay open. */
    val peerOs: String? = null,
    val id: String = "",
    /** Sessions started together (one send to several devices) share it; a receive is its own group. */
    val groupId: String = "",
    val kind: String? = null,
    /** Shown by its own screen (live camera, switch phones, text) — never opens the transfer view. */
    val quiet: Boolean = false,
    /** Per-file BLAKE3 roots both sides agreed on, once a v2/v3 transfer completes. */
    val roots: List<String>? = null,
) {
    val fraction: Float get() = when (status) {
        SessionStatus.COMPLETED -> 1f
        else -> (bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/**
 * The line under a session's status: what to try after a failure or a decline, or, while sending to an
 * iPhone or iPad, the heads-up that it only receives with CrossDrop open and its screen on.
 */
val SessionState.note: String?
    get() = when {
        resuming && status == SessionStatus.CONNECTING ->
            tr("Lost the connection to {0}. Trying again as soon as it's back; what already arrived is kept.", peerName)
        hint != null -> hint
        direction == Direction.SENDING && !status.isTerminal && peerOs == com.crossdrop.core.Platforms.IOS -> FailureAdvice.iosNote(peerName)
        else -> null
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
    /** A contact offer's cards, from its photo-less inline copy (empty when it had none). */
    val contacts: List<VContact> by lazy {
        if (offer.kind != OfferKind.CONTACT) emptyList()
        else offer.text?.let { runCatching { VCard.parse(it) }.getOrNull() }.orEmpty()
    }
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
    /**
     * A plain-files receive finished: [files] (parallel to the offer) are whole and in the inbox.
     * Runs off the transfer thread. Where the inbox needs a last step (Android moves them into
     * Downloads) it happens here; the default does nothing.
     */
    fun received(files: List<File>) {}
}

/**
 * Receiving end of "Switch phones": each batch is staged in [batchStore] and handed to
 * [importBatch] once it has arrived whole.
 */
interface SwitchReceiver {
    fun begin(peerName: String, batch: BatchInfo?)
    /** Staging for one batch — its own folder, so a batch can arrive while the previous one imports. */
    fun storeFor(offer: Offer): DeltaReceiver
    /** The v1 stream target for [meta] of [offer], inside that batch's folder. */
    fun batchFile(offer: Offer, meta: FileMeta): File
    /** Runs off the transfer thread; imports [files] (parallel to `offer.files`) and deletes them. */
    suspend fun importBatch(peerName: String, offer: Offer, files: List<File>)
    fun batchEnded(status: SessionStatus, error: String?)
}

/**
 * Receiving end of a phone's backup ("My devices"): batches from a paired phone arrive without a prompt,
 * are staged per batch, then filed by [importBatch]. [verify] answers the phone's "do you still hold
 * these?" before it frees up space. Only computers set one; elsewhere backup offers are declined.
 */
interface BackupReceiver {
    fun storeFor(pairId: String, offer: Offer): DeltaReceiver
    fun batchFile(pairId: String, offer: Offer, meta: FileMeta): File
    /** Runs off the transfer thread; [roots] are the agreed BLAKE3 roots (null on the v1 path). */
    suspend fun importBatch(pairId: String, peerName: String, offer: Offer, files: List<File>, roots: List<String>?)
    fun verify(pairId: String, items: List<VerifyItem>): List<String>
    fun batchEnded(pairId: String, status: SessionStatus, error: String?) {}
}

/**
 * Receiving end of installed apps sent from another Android phone: each APK is staged in [stagingFile] and the
 * whole set handed to [received] once it arrived (grouped by `FileMeta.pkg`), where the person installs them.
 * Only Android sets one; elsewhere an app offer lands in the inbox as `.apk` files.
 */
interface AppReceiver {
    fun stagingFile(offer: Offer, index: Int, meta: FileMeta): File
    /** Runs off the transfer thread; [files] are parallel to `offer.files`. */
    suspend fun received(peerName: String, offer: Offer, files: List<File>)
}

/**
 * Takes the clipboard of one of "My devices" (see [ClipboardSync]). [received] runs on the listener's thread, after the
 * sender proved the pairing on that connection; it returns whether the text was put on this device's clipboard.
 */
interface ClipboardReceiver {
    fun received(pairId: String, clipId: String, text: String): Boolean
}

/** A device asking to become one of "My devices"; both screens show [sas]. */
class PairPrompt(
    val peerName: String,
    val sas: String,
    internal val answer: CompletableDeferred<Boolean>,
    /** What the pairing is for ([com.crossdrop.core.protocol.PairPurpose]); null = phone backups, as older builds ask. */
    val purpose: String? = null,
)

/**
 * Receiving end of contact cards sent on their own: the `.vcf` is staged in [stagingFile] and handed
 * to [received] once whole. Platforms without an address book leave the hub's receiver unset, and the
 * card lands in the inbox like any file.
 */
interface ContactReceiver {
    fun stagingFile(meta: FileMeta): File
    /** Runs off the transfer thread; adds the cards in [files] and deletes (or keeps) them. */
    suspend fun received(peerName: String, offer: Offer, files: List<File>)
}

/**
 * Every transfer in flight, both directions, on any platform. The UI observes [sessions],
 * [focusedGroup], [incomingOffer] and [live]; platforms supply files and storage.
 *
 * - A send to several devices runs one session per device, side by side, as one group.
 * - Offers of kind text, contact, camera and switch are routed to their own screens instead of the
 *   transfer view. Camera and switch receivers hand out a [TrustStore] grant on the first accept so
 *   the rest of the shots/batches arrive without asking again.
 */
class TransferHub(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val port: ReceivePort,
    /** History hook: called once per session as it ends (camera shots and switch batches excluded). */
    private val onEnded: (Direction, SessionState) -> Unit,
    /** When an interrupted send is picked up again by itself (tests shorten the pauses). */
    private val resumePolicy: ResumePolicy = ResumePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val trust = TrustStore()
    var switchReceiver: SwitchReceiver? = null
    var contactReceiver: ContactReceiver? = null
    var backupReceiver: BackupReceiver? = null
    var appReceiver: AppReceiver? = null
    /** Clipboard sharing with "My devices"; without it every shared copy is refused. */
    var clipboardReceiver: ClipboardReceiver? = null

    /** "My devices". Without it this device can't pair, and backup offers are declined. */
    var pairings: PairingStore? = null

    /** Devices that announced themselves (ANNOUNCE); shown on the radar like mDNS sightings. */
    var announced: AnnouncedPeers? = null

    /** The devices on the radar now; an interrupted send waits for its device to be in here again. Optional. */
    var radar: StateFlow<Map<String, Peer>>? = null

    /** Which of "My devices" are on the radar: lets an interrupted send find a paired device that restarted. Optional. */
    var presence: PairedPresence? = null

    /** Offers just accepted, remembered so the same sender picking an interrupted transfer up again isn't asked twice. */
    private val resumable = ResumableOffers(clock = clock)

    /** Receive session id → the plain-files offer accepted for it (so a cancel here can tell [resumable]). */
    private val receiving = ConcurrentHashMap<String, InboundRequest>()

    private val _pairPrompt = MutableStateFlow<PairPrompt?>(null)
    /** A pairing request waiting for the human's answer. */
    val pairPrompt: StateFlow<PairPrompt?> = _pairPrompt

    /** Shake to connect: our shakes against SHAKE messages from the radar. */
    val shakes = ShakeMatcher()
    private val _shakeMatch = MutableStateFlow<ShakeMatcher.Match?>(null)
    /** The device we just shook phones with, until the UI has handled it ([shakeHandled]). */
    val shakeMatch: StateFlow<ShakeMatcher.Match?> = _shakeMatch

    /**
     * This device was shaken: tell every device on the radar (each checks for its own shake), and match any
     * SHAKE that arrived just before ours. Devices that can't shake (computers) or predate shaking ignore it.
     */
    fun shake(peers: List<Peer>, listenPort: Int) {
        shakes.local()?.let { _shakeMatch.value = it }
        for (p in peers) {
            val ep = p.endpoint ?: continue
            if (p.vault != null) continue
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { withTimeoutOrNull(3_000) { engine.control(ep, connectTimeoutMs = 1_500, readTimeoutMs = 3_000) { shake(listenPort) } } }
            }
        }
    }

    fun shakeHandled() { _shakeMatch.value = null }

    /** Backup transfer ids already accepted: a recorded session replayed verbatim must not be taken twice. */
    private val seenBackups = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

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
        /** Session id → the device it goes to; an auto-resume swaps in the device's new address. */
        val peers: MutableMap<String, Peer>,
        val files: suspend () -> List<OutgoingFile>,
        val options: SendOptions,
        val quiet: Boolean,
        val inline: (suspend (List<OutgoingFile>) -> String?)?,
        /** Session id → the pairing the device proved to hold, when it is one of "My devices". */
        val pairIds: MutableMap<String, String> = ConcurrentHashMap(),
    )

    /** One send that lost its connection and is waiting for the device to come back. */
    private class ResumeState(val firstLossAt: Long) {
        var attempts = 0
        var job: Job? = null
    }
    private val resumes = ConcurrentHashMap<String, ResumeState>()

    /** Dev-only: auto-accept the next inbound offer (loopback self-tests). */
    @Volatile var autoAcceptOnce = false

    // ------------------------------------------------------------ sending --

    /**
     * Send the same files to every peer in [peers] at once. [files] is resolved once per device, so
     * each connection opens its own streams. [inline], when set, computes `Offer.text` from the
     * resolved files (a shared `.vcf` previews its cards). Returns the group id.
     */
    fun send(
        peers: List<Peer>,
        files: suspend () -> List<OutgoingFile>,
        options: SendOptions = SendOptions(),
        quiet: Boolean = false,
        inline: (suspend (List<OutgoingFile>) -> String?)? = null,
    ): String {
        val groupId = newId()
        val targets = peers.filter { it.endpoint != null }.associateBy { newId() }
        if (targets.isEmpty()) return groupId
        specs[groupId] = SendSpec(ConcurrentHashMap(targets), files, options, quiet, inline)
        _sessions.update { list ->
            list + targets.map { (sid, peer) ->
                SessionState(
                    Direction.SENDING, peer.name, id = sid, groupId = groupId,
                    kind = options.kind, quiet = quiet, peerOs = peer.platform,
                )
            }
        }
        if (!quiet) focusedId.value = groupId
        targets.forEach { (sid, peer) -> launchSend(sid, peer, files, options, inline) }
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
        _sessions.update { it + SessionState(Direction.SENDING, peer.name, id = sid, groupId = sid, kind = options.kind, quiet = quiet, peerOs = peer.platform) }
        var last: SessionState? = null
        try {
            runSend(sid, peer, files, options)
        } finally {
            // Also on cancellation: a session left behind mid-transfer would keep "busy" (and the
            // foreground notification) up forever.
            observers.remove(sid)
            last = _sessions.value.firstOrNull { it.id == sid }
            _sessions.update { list -> list.filterNot { it.id == sid } }
        }
        return when {
            last == null -> SessionState(Direction.SENDING, peer.name, status = SessionStatus.FAILED)
            // The flow ended without a verdict (cancelled coroutine): call it cancelled.
            !last.status.isTerminal -> last.copy(status = SessionStatus.CANCELLED)
            else -> last
        }
    }

    private fun launchSend(
        sid: String, peer: Peer, files: suspend () -> List<OutgoingFile>, options: SendOptions,
        inline: (suspend (List<OutgoingFile>) -> String?)?,
    ) {
        // A retry reuses the session id: the previous attempt must neither keep running nor, when it
        // finally ends, unregister the new one.
        jobs.remove(sid)?.cancel()
        val job = scope.launch {
            val list = try {
                files()
            } catch (e: com.crossdrop.core.protocol.TooManyFilesException) {
                reduce(sid, TransferEvent.Failed(tr("That folder has more than {0} files. Send part of it, or zip it first.", e.limit)))
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (list.isEmpty()) {
                reduce(sid, TransferEvent.Failed(tr("nothing readable selected")))
                return@launch
            }
            val opts = if (inline == null) options
                else options.copy(text = runCatching { inline(list) }.getOrNull())
            runSend(sid, peer, list, opts)
        }
        jobs[sid] = job
        job.invokeOnCompletion { jobs.remove(sid, job) }
    }

    private suspend fun runSend(sid: String, peer: Peer, files: List<OutgoingFile>, options: SendOptions) {
        val endpoint = peer.endpoint ?: run { reduce(sid, TransferEvent.Failed(tr("device has no address"))); return }
        val kind = options.kind
        var opts = if (kind == OfferKind.CAMERA || kind == OfferKind.SWITCH)
            options.copy(trust = options.trust ?: trust.credentialFor(peer.instanceId, kind)) else options
        // Plain files to one of "My devices": prove the pairing, so it can take them without asking.
        if (opts.pairKey == null && (kind == null || kind == OfferKind.FILES)) {
            val secret = pairSecretFor(peer)
            val spec = _sessions.value.firstOrNull { it.id == sid }?.let { specs[it.groupId] }
            // This send already went to one of "My devices" and proved it: a retry (a resume, a manual Retry) must find
            // that same device again. Whoever now answers at the address, because it took the radar id or because the
            // check timed out, is not it: wait, never fall back to sending unproven (an impostor would get the files).
            if (spec?.pairIds?.get(sid) != null && secret?.id != spec.pairIds[sid]) {
                reduce(sid, TransferEvent.Failed("paired device not confirmed", kind = FailureKind.UNREACHABLE))
                return
            }
            opts = opts.copy(pairKey = secret?.key)
            if (secret != null) spec?.pairIds?.put(sid, secret.id)
        }
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

    /**
     * The pairing [peer] holds with us, or null (not one of "My devices", not reachable in time, an
     * older build). Costs one short connection, and only when we hold any pairing at all.
     */
    private suspend fun pairSecretFor(peer: Peer): PairSecret? {
        val held = pairings?.secrets().orEmpty()
        val endpoint = peer.endpoint
        if (held.isEmpty() || endpoint == null || peer.vault != null) return null
        val match = withTimeoutOrNull(PAIR_CHECK_MS) {
            runCatching { engine.control(endpoint, connectTimeoutMs = 2_000, readTimeoutMs = PAIR_CHECK_MS.toInt()) { pairCheck(held) } }.getOrNull()
        } ?: return null
        pairings?.seen(match.id, null)
        return match
    }

    /**
     * Pair with [peer] as one of "My devices": both screens show the code [onCode] gets, and the other person
     * agrees. The two then send each other files without asking (each can turn that off). Returns the pairing, or
     * null when they declined or didn't answer; throws when the device can't be reached.
     */
    suspend fun pairWith(peer: Peer, onCode: (String) -> Unit): PairedDevice? {
        val endpoint = peer.endpoint ?: return null
        val store = pairings ?: return null
        val id = PairingStore.newId()
        val (key, name) = engine.control(endpoint) {
            onCode(sas)
            pair(id, purpose = com.crossdrop.core.protocol.PairPurpose.DEVICES)?.let { it to peerName }
        } ?: return null
        return store.add(id, name, key, accept = true, backup = false)
    }

    /** Resend to the devices of [groupId] that failed, declined or were cancelled. */
    fun retryFailed(groupId: String) {
        val spec = specs[groupId] ?: return
        val retry = _sessions.value.filter { it.groupId == groupId && it.status.isProblem }
        if (retry.isEmpty()) return
        retry.forEach { resumes.remove(it.id)?.job?.cancel() }
        _sessions.update { list ->
            list.map { s ->
                if (retry.any { it.id == s.id }) SessionState(Direction.SENDING, s.peerName, id = s.id, groupId = groupId, kind = s.kind, quiet = s.quiet, peerOs = s.peerOs)
                else s
            }
        }
        if (!spec.quiet) focusedId.value = groupId
        retry.forEach { s -> spec.peers[s.id]?.let { launchSend(s.id, it, spec.files, spec.options, spec.inline) } }
    }

    fun cancelSession(id: String) {
        receiving[id]?.let(resumable::stop)      // a receive the person stopped: its sender must not be able to resume it silently
        resumes.remove(id)?.job?.cancel()
        jobs.remove(id)?.cancel()
        _sessions.update { list ->
            list.map { if (it.id == id && !it.status.isTerminal) it.copy(status = SessionStatus.CANCELLED, resuming = false) else it }
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

    // ------------------------------------------------------- auto-resume --

    /**
     * A send lost its connection mid-way (a Wi-Fi blip, the other device locking its screen): instead of failing,
     * wait for the device and try again, for as long as [ResumePolicy.windowMs]. The receiver keeps what arrived,
     * so each try only moves what is left, and it takes the repeat without asking again ([resumable]). Returns
     * true when the failure was taken over; false lets it show as a failure.
     */
    private fun startResume(id: String, event: TransferEvent.Failed): Boolean {
        val cur = _sessions.value.firstOrNull { it.id == id } ?: return false
        if (cur.status.isTerminal || cur.direction != Direction.SENDING) return false
        val spec = specs[cur.groupId] ?: return false
        if (spec.quiet || (spec.options.kind != null && spec.options.kind != OfferKind.FILES)) return false
        val state = resumes[id]
        if (state != null && resumePolicy.expired(state.firstLossAt, clock())) { resumes.remove(id); return false }
        val connected = cur.status != SessionStatus.CONNECTING
        val kind = FailureAdvice.kindOf(event.cause, event.kind, connected, sending = true)
        if (!resumePolicy.eligible(kind, connected, state != null)) { resumes.remove(id); return false }
        val st = state ?: ResumeState(clock()).also { resumes[id] = it }
        _sessions.update { list ->
            list.map { if (it.id == id) it.copy(status = SessionStatus.CONNECTING, resuming = true, error = null, hint = null, bytesPerSec = 0) else it }
        }
        observers[id]?.let { obs -> _sessions.value.firstOrNull { it.id == id }?.let { runCatching { obs(it) } } }
        scheduleResume(id, st)
        return true
    }

    private fun scheduleResume(id: String, st: ResumeState) {
        st.job?.cancel()
        st.job = scope.launch {
            delay(resumePolicy.pauseMs(st.attempts++))
            while (true) {
                val cur = _sessions.value.firstOrNull { it.id == id }
                // Cancelled, closed or already moving again by the person's own doing: nothing left to do here.
                if (cur == null || !cur.resuming || cur.status != SessionStatus.CONNECTING) { resumes.remove(id); return@launch }
                val spec = specs[cur.groupId] ?: run { resumes.remove(id); return@launch }
                if (resumePolicy.expired(st.firstLossAt, clock())) {
                    resumes.remove(id)
                    reduce(id, TransferEvent.Failed(tr("connection lost"), kind = FailureKind.CONNECTION_LOST))
                    return@launch
                }
                val peer = livePeer(spec, id)
                if (peer != null) {
                    spec.peers[id] = peer
                    launchSend(id, peer, spec.files, spec.options, spec.inline)
                    return@launch      // how this try ends comes back through reduce, which schedules the next one
                }
                delay(WAIT_FOR_PEER_MS)
            }
        }
    }

    /** The radar entry to retry against: the same device (same launch), or a paired one that restarted; null while it is away. */
    private fun livePeer(spec: SendSpec, id: String): Peer? {
        val original = spec.peers[id] ?: return null
        val radar = radar?.value ?: return original      // no radar to ask: just try again
        radar[original.instanceId]?.takeIf { it.endpoint != null }?.let { return it }
        val pairId = spec.pairIds[id] ?: return null
        return presence?.present?.value?.get(pairId)
    }

    // ----------------------------------------------------------- incoming --

    /** Serve one inbound connection accepted by the transfer listener. */
    fun onInboundSocket(socket: Socket) {
        val sid = newId()
        val route = Route()
        val job = scope.launch {
            engine.receiveRequest(socket, control) { req -> decide(sid, req, route) }.collect { ev ->
                if (_sessions.value.none { it.id == sid }) return@collect   // not accepted (yet)
                reduce(sid, ev)
                if (ev is TransferEvent.Completed) { route.request?.let(resumable::completed); onReceived(route, ev.offer, ev.roots) }
                if (ev is TransferEvent.Failed) {
                    // The sender sees any failure here as a dropped connection and retries. A bad link may well mend, but a full
                    // disk won't: for anything else the retries are asked about instead of silently accepted.
                    val kind = FailureAdvice.kindOf(ev.cause, ev.kind, connected = true, sending = false)
                    if (kind != FailureKind.CONNECTION_LOST && kind != FailureKind.UNREACHABLE) route.request?.let(resumable::stop)
                }
                if (ev is TransferEvent.Failed || ev is TransferEvent.Cancelled) {
                    val status = if (ev is TransferEvent.Failed) SessionStatus.FAILED else SessionStatus.CANCELLED
                    val message = (ev as? TransferEvent.Failed)?.message
                    if (route.kind == OfferKind.SWITCH) switchReceiver?.batchEnded(status, message)
                    if (route.kind == OfferKind.BACKUP) route.pairId?.let { backupReceiver?.batchEnded(it, status, message) }
                    if (route.kind == OfferKind.CAMERA) _live.update { it?.copy(incoming = null) }
                }
            }
        }
        jobs[sid] = job
        job.invokeOnCompletion { jobs.remove(sid, job); receiving.remove(sid) }
    }

    /** What an accepted offer turned into, so completion knows where its files went. */
    private class Route {
        var kind: String? = null
        var peerName: String = ""
        val v1Files = mutableListOf<File>()
        var store: DeltaReceiver? = null
        var switchStore: Boolean = false
        var pairId: String? = null
        /** The accepted plain-files offer, so completion can stop treating a repeat of it as a resume. */
        var request: InboundRequest? = null
    }

    /** Forget a receive that a retry replaced: its connection is dead, so it will never finish. */
    private fun dropSession(sid: String) {
        jobs.remove(sid)?.cancel()
        _sessions.update { list -> list.filterNot { it.id == sid } }
    }

    private suspend fun decide(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        route.peerName = req.peerName
        // One of "My devices" proves it with the offer (a device that only borrowed the name can't). Files from it
        // go straight in when the person said so; anything else is asked about as usual. Either way the answer
        // proves the pairing back, which the sender checks before sending a byte. Text, contacts and the like
        // are never taken unseen: they are shown first.
        val store = pairings
        val secret = if (req.offer.kind == OfferKind.BACKUP) null else store?.let { req.pairedWith(it.secrets()) }
        val device = secret?.let { store?.get(it.id) }
        if (device != null) store?.seen(device.id, req.peerName)
        val plainFiles = req.offer.kind == null || req.offer.kind == OfferKind.FILES
        // A transfer id is taken once: a recorded session replayed verbatim must not be accepted again.
        // …unless the person here cancelled that very transfer a moment ago: then it is asked about, not taken on trust.
        val stopped = plainFiles && resumable.isStopped(req)
        val auto = device != null && device.accept && plainFiles && !stopped && rememberTransfer(req.offer.transferId)
        // The same sender, in the same two app launches, picking up the very same files it was just let send and
        // did not finish: no reason to ask again. A dead connection may still be holding the first receive; the
        // retry replaces it.
        val repeat = plainFiles && !stopped && resumable.matches(req)
        val resumed = repeat && !auto
        if (repeat) resumable.staleSession(req)?.let(::dropSession)
        val decision = if (auto || resumed) acceptFiles(sid, req, route) else decideOffer(sid, req, route)
        return if (secret != null && decision is ReceiveDecision.Accept) decision.copy(pairKey = secret.key) else decision
    }

    /** True the first time [transferId] is seen. */
    private fun rememberTransfer(transferId: String): Boolean {
        val fresh = seenBackups.add(transferId)
        synchronized(seenBackups) { while (seenBackups.size > 4_096) seenBackups.remove(seenBackups.first()) }
        return fresh
    }

    private suspend fun decideOffer(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        val offer = req.offer

        // A backup batch never asks: it comes from one of "My devices" or it is refused.
        if (offer.kind == OfferKind.BACKUP) return decideBackup(sid, req, route)

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
            offer.kind == OfferKind.CONTACT && contactReceiver != null -> acceptContact(sid, req, route)
            offer.kind == OfferKind.APP && appReceiver != null -> acceptApps(sid, req, route)
            else -> acceptFiles(sid, req, route)
        }
    }

    fun respondToOffer(accept: Boolean) {
        _incoming.value?.let { respondToOffer(it, accept) }
    }

    /**
     * Answer exactly the offer the human was looking at. If a newer offer replaced it in the
     * meantime, that one stays up (the old one was already declined) — a tap never lands on an
     * offer that wasn't on screen.
     */
    fun respondToOffer(offer: IncomingOffer, accept: Boolean) {
        if (_incoming.value === offer) _incoming.value = null
        offer.answer.complete(accept)
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
        // Plain files are remembered for a resume *before* the session shows (and can be cancelled): a Cancel that
        // lands in between must still find what to stop.
        if (req.offer.kind == null || req.offer.kind == OfferKind.FILES) {
            resumable.remember(req, sid); route.request = req; receiving[sid] = req
        }
        register(sid, req, req.offer.kind, quiet = false)
        return ReceiveDecision.Accept(inboxSink(route), port.inboxDelta)
    }

    private fun acceptText(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        route.kind = OfferKind.TEXT
        register(sid, req, OfferKind.TEXT, quiet = true)
        // The text came inline with the offer; the file copy is only for older receivers.
        return ReceiveDecision.Accept({ DiscardStream }, delta = null)
    }

    private fun acceptContact(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        val rx = contactReceiver ?: return acceptFiles(sid, req, route)
        route.kind = OfferKind.CONTACT
        register(sid, req, OfferKind.CONTACT, quiet = true)
        // A few kilobytes: the plain stream path, staged outside the inbox until it's been added.
        val sink: (FileMeta) -> OutputStream = { meta ->
            val f = rx.stagingFile(meta)
            synchronized(route.v1Files) { route.v1Files += f }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
        return ReceiveDecision.Accept(sink, delta = null)
    }

    private fun acceptApps(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        val rx = appReceiver ?: return acceptFiles(sid, req, route)
        route.kind = OfferKind.APP
        register(sid, req, OfferKind.APP, quiet = false)
        // Staged privately (never the shared inbox) until the person installs them; the stream path keeps it simple.
        // The stream path opens files one after another in offer order, so the count so far is this file's index.
        val sink: (FileMeta) -> OutputStream = { meta ->
            val f = synchronized(route.v1Files) { rx.stagingFile(req.offer, route.v1Files.size, meta).also { route.v1Files += it } }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
        return ReceiveDecision.Accept(sink, delta = null)
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
        val store = rx.storeFor(req.offer)
        route.kind = OfferKind.SWITCH
        route.store = store
        route.switchStore = true
        register(sid, req, OfferKind.SWITCH, quiet = true)
        val sink: (FileMeta) -> OutputStream = { meta ->
            val f = rx.batchFile(req.offer, meta)
            synchronized(route.v1Files) { route.v1Files += f }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
        return ReceiveDecision.Accept(sink, store, grant)
    }

    private fun decideBackup(sid: String, req: InboundRequest, route: Route): ReceiveDecision {
        val rx = backupReceiver ?: return ReceiveDecision.Reject("no backups here")
        val store = pairings ?: return ReceiveDecision.Reject("not paired")
        val pair = req.pairedWith(store.secrets(backupOnly = true)) ?: return ReceiveDecision.Reject("not paired")
        if (!seenBackups.add(req.offer.transferId)) return ReceiveDecision.Reject("already received")
        synchronized(seenBackups) { while (seenBackups.size > 4_096) seenBackups.remove(seenBackups.first()) }
        store.seen(pair.id, req.peerName)
        val delta = rx.storeFor(pair.id, req.offer)
        route.kind = OfferKind.BACKUP
        route.store = delta
        route.pairId = pair.id
        register(sid, req, OfferKind.BACKUP, quiet = true)
        val sink: (FileMeta) -> OutputStream = { meta ->
            val f = rx.batchFile(pair.id, req.offer, meta)
            synchronized(route.v1Files) { route.v1Files += f }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
        return ReceiveDecision.Accept(sink, delta, pairKey = pair.key)
    }

    // ------------------------------------------------- control (no files) --

    /** What the listener does with connections that carry no files. */
    private val control = object : ControlHandler {
        override fun announced(name: String, instanceId: String, host: String, port: Int, os: String?) {
            this@TransferHub.announced?.add(instanceId, name, Endpoint(host, port), os)
        }

        override fun shaken(name: String, instanceId: String, host: String, port: Int, os: String?) {
            // Make sure we can reach it back, even if mDNS never showed it here.
            if (port > 0) this@TransferHub.announced?.add(instanceId, name, Endpoint(host, port), os)
            shakes.remote(instanceId, name)?.let { _shakeMatch.value = it }
        }

        override suspend fun pairRequested(name: String, sas: String, purpose: String?): Boolean {
            if (this@TransferHub.pairings == null) return false
            // The old, backup-only form of the request is for a computer that keeps backups. A device that doesn't (a
            // phone) refuses it without a word: agreeing would make the other device its "backup computer", and the
            // phone's own backup logic would then start sending it every photo.
            if (purpose != com.crossdrop.core.protocol.PairPurpose.DEVICES && backupReceiver == null) return false
            val prompt = PairPrompt(name, sas, CompletableDeferred(), purpose)
            _pairPrompt.value?.answer?.complete(false)
            _pairPrompt.value = prompt
            val answer = runCatching { withTimeoutOrNull(PAIR_PROMPT_MS) { prompt.answer.await() } }.getOrNull()
            if (_pairPrompt.value === prompt) _pairPrompt.value = null
            return answer == true
        }

        override fun paired(pairId: String, name: String, key: ByteArray, purpose: String?) {
            // "My devices": send each other files without asking. Anything else is a phone backing up here, as before.
            val devices = purpose == com.crossdrop.core.protocol.PairPurpose.DEVICES
            this@TransferHub.pairings?.add(pairId, name, key, accept = devices, backup = !devices)
        }

        override fun pairings(): List<PairSecret> = this@TransferHub.pairings?.secrets().orEmpty()

        override fun verify(pairId: String, items: List<VerifyItem>): List<String> =
            backupReceiver?.verify(pairId, items).orEmpty()

        override fun clipboard(pairId: String, clipId: String, text: String): Boolean =
            clipboardReceiver?.received(pairId, clipId, text) ?: false
    }

    /** Answer exactly the pairing request the human was looking at. */
    fun respondToPair(prompt: PairPrompt, accept: Boolean) {
        if (_pairPrompt.value === prompt) _pairPrompt.value = null
        prompt.answer.complete(accept)
    }

    /** Files of a completed receive, parallel to `offer.files`. */
    private fun receivedFiles(route: Route, offer: Offer): List<File> {
        synchronized(route.v1Files) {
            if (route.v1Files.size == offer.files.size) return route.v1Files.toList()
        }
        val store = route.store ?: return emptyList()
        return offer.files.map { store.targetFor(it) }
    }

    private fun onReceived(route: Route, offer: Offer, roots: List<String>?) {
        when (route.kind) {
            null, OfferKind.FILES -> {
                val files = receivedFiles(route, offer)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { port.received(files) } }
            }
            OfferKind.CAMERA -> {
                val now = System.currentTimeMillis()
                val shots = receivedFiles(route, offer).mapIndexed { i, f ->
                    LiveShot(f, offer.files[i].name, offer.files[i].mime, now)
                }
                _live.update { it?.copy(shots = (it.shots + shots).takeLast(MAX_LIVE_SHOTS), incoming = null) }
            }
            OfferKind.CONTACT -> contactReceiver?.let { rx ->
                val files = receivedFiles(route, offer)
                scope.launch {
                    try {
                        rx.received(route.peerName, offer, files)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The receiver reports its own failures; nothing more to do here.
                    }
                }
            }
            OfferKind.APP -> appReceiver?.let { rx ->
                val files = receivedFiles(route, offer)
                scope.launch {
                    try {
                        rx.received(route.peerName, offer, files)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The receiver reports its own failures.
                    }
                }
            }
            OfferKind.BACKUP -> {
                val files = receivedFiles(route, offer)
                val pairId = route.pairId ?: return
                scope.launch {
                    try {
                        backupReceiver?.importBatch(pairId, route.peerName, offer, files, roots)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        backupReceiver?.batchEnded(pairId, SessionStatus.FAILED, e.message)
                    }
                }
            }
            OfferKind.SWITCH -> if (route.switchStore) {
                val files = receivedFiles(route, offer)
                scope.launch {
                    try {
                        switchReceiver?.importBatch(route.peerName, offer, files)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        switchReceiver?.batchEnded(SessionStatus.FAILED, tr("couldn't add the items: {0}", e.message))
                    }
                }
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
        // A send that lost its connection waits for the device instead of failing (see startResume).
        if (event is TransferEvent.Failed && startResume(id, event)) return
        // Done, or moving again after a pause (a later loss starts a fresh window, not what is left of the old one).
        if (event is TransferEvent.Completed || event is TransferEvent.Failed || event is TransferEvent.Cancelled ||
            event is TransferEvent.DeclinedByPeer || event is TransferEvent.Progress) {
            resumes.remove(id)?.job?.cancel()
        }
        var ended: SessionState? = null
        _sessions.update { list ->
            list.map { cur ->
                // A finished session stays finished (a cancelled send must not flip to "completed").
                if (cur.id != id || cur.status.isTerminal) return@map cur
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
                    is TransferEvent.Progress -> cur.copy(
                        status = SessionStatus.TRANSFERRING, resuming = false,
                        bytesDone = event.bytesDone, bytesTotal = event.bytesTotal,
                        bytesPerSec = event.bytesPerSec, bytesSkipped = event.bytesSkipped,
                        currentFileName = event.currentFile.name,
                    )
                    is TransferEvent.Completed -> cur.copy(
                        status = SessionStatus.COMPLETED, resuming = false, offer = event.offer,
                        bytesDone = cur.bytesTotal.coerceAtLeast(cur.bytesDone), bytesPerSec = 0,
                        roots = event.roots,
                    )
                    is TransferEvent.TrustGranted -> cur
                    is TransferEvent.DeclinedByPeer -> cur.copy(
                        status = SessionStatus.DECLINED, resuming = false, bytesPerSec = 0,
                        hint = if (cur.direction == Direction.SENDING) FailureAdvice.declinedHint() else null,
                    )
                    is TransferEvent.Cancelled -> cur.copy(status = SessionStatus.CANCELLED, resuming = false, bytesPerSec = 0)
                    is TransferEvent.Failed -> {
                        // Before the offer went out nothing but a socket existed: a failure then means "couldn't reach".
                        val advice = FailureAdvice.explain(
                            event.cause, event.message, cur.peerName, cur.peerOs, event.kind,
                            connected = cur.status != SessionStatus.CONNECTING, sending = cur.direction == Direction.SENDING,
                        )
                        cur.copy(status = SessionStatus.FAILED, resuming = false, failure = advice.kind, error = advice.title, hint = advice.hint, bytesPerSec = 0)
                    }
                }
                // Record the outcome once, on the transition into a terminal state.
                if (next.status.isTerminal && !cur.status.isTerminal) ended = next
                next
            }
        }
        observers[id]?.let { obs -> _sessions.value.firstOrNull { it.id == id }?.let { runCatching { obs(it) } } }
        ended?.let { s ->
            if (s.kind != OfferKind.CAMERA && s.kind != OfferKind.SWITCH && s.kind != OfferKind.BACKUP) runCatching { onEnded(s.direction, s) }
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
        /** How long a pairing request waits for the human (the asking side waits a little longer). */
        const val PAIR_PROMPT_MS = 70_000L
        /** How long a send waits to learn whether the other device is one of ours, before it just sends. */
        const val PAIR_CHECK_MS = 3_000L
        /** While a dropped send waits for its device: how often it looks at the radar. */
        const val WAIT_FOR_PEER_MS = 2_000L
        const val MAX_LIVE_SHOTS = 60
    }
}
