package com.crossdrop.net

import com.crossdrop.core.Endpoint
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.platform.Log
import com.crossdrop.platform.OsWifi
import com.crossdrop.platform.WifiJoiner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Join a CrossDrop Wi-Fi" on a computer: one click joins the network an Android phone made with "Make a CrossDrop
 * Wi-Fi" ([DirectWifi]: the same name and password everywhere) through the OS ([OsWifi]), then announces this
 * computer to the phone at the network's well-known port so both radars show each other (mDNS usually finds the
 * phone too: the phone answers mDNS on its own network, and JmDNS re-binds when our address changes).
 *
 * The network's password is public, so **this computer must never keep it**: a remembered network is joined again by
 * the OS whenever it shows up, including someone else's (or a fake) `DIRECT-CD-CrossDrop`, and the computer loses its
 * internet. So Leave, a failed join, losing the network and quitting all *forget* it (and leave it, if still on it),
 * and a [JoinedMarker] saved before every join lets the next launch clean up after a crash or force quit.
 *
 * Every OS Wi-Fi call goes through one lock ([osLock]), so a forget always finishes before the next join starts and
 * two joins never overlap. Each [join]/[leave] bumps a generation: work for an older one never changes the state, and
 * a join that finishes after its Leave forgets the network itself (unless a newer join wants it).
 */
class DirectWifiJoin(
    private val scope: CoroutineScope,
    private val engine: TransferEngine,
    private val listenPort: () -> Int,
    /** Where the phone shows up on our radar (the same list announcements land in). */
    private val known: AnnouncedPeers,
    private val os: OsWifi,
    /** This computer's LAN address now (it moves onto the phone's network once joined). */
    private val localIp: () -> String?,
    /** Where the phone listens, from our address on its network. Tests point it at a local device. */
    private val hostFor: (String?) -> Endpoint = { Endpoint(DirectWifi.hostFor(it), DirectWifi.PORT) },
    /** Is [localIp] on the phone's network? Tests say yes at once. */
    private val onNetwork: (String?) -> Boolean = DirectWifi::onGroupSubnet,
    private val timing: Timing = Timing(),
    /** "The OS may still remember the network": survives a crash in the app's prefs. */
    private val marker: JoinedMarker = JoinedMarker.InMemory(),
) {
    data class Timing(
        val addressWaitMs: Long = 20_000,
        val stepMs: Long = 500,
        val firstTries: Int = 8,
        val firstRetryMs: Long = 1_000,
        val reannounceMs: Long = 15_000,
    )

    /** Persisted "we asked the OS to join and haven't forgotten it yet". */
    interface JoinedMarker {
        var joined: Boolean

        class InMemory : JoinedMarker {
            @Volatile override var joined = false
        }
    }

    sealed interface State {
        data object Idle : State
        /** The OS is joining the network. */
        data object Joining : State
        /** On the network; reaching the phone that made it. */
        data object Connecting : State
        /** On the network. [hostName] is set once the phone answered. */
        data class Joined(val hostName: String? = null) : State
        data class Failed(val reason: Failure) : State
    }

    enum class Failure {
        /** No CrossDrop Wi-Fi in range (not made yet, too far, or the OS couldn't see it). */
        NOT_FOUND,
        /** This computer has no Wi-Fi, or it is switched off. */
        NO_WIFI,
        /** The OS joined nothing we can use (wrong network, refused, or the tool is missing). */
        FAILED,
        /** We were on it, and it went away (the phone stopped it, or moved out of range). */
        LOST,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Whether this computer can join networks from inside the app at all. */
    val supported: Boolean get() = os.supported

    private val osLock = Mutex()
    @Volatile private var generation = 0
    private var job: Job? = null
    private var hostId: String? = null

    @Synchronized
    fun join() {
        val gen = ++generation
        stopTalking()
        _state.value = State.Joining
        job = scope.launch(Dispatchers.IO) { run(gen) }
    }

    /** Leave the network (the OS goes back to its usual one), forget it, and forget the phone. */
    @Synchronized
    fun leave() {
        val gen = ++generation
        stopTalking()
        _state.value = State.Idle
        scope.launch(Dispatchers.IO) { forget { generation == gen } }
    }

    /**
     * The app is quitting: forget the network now, not in the background (also from a JVM shutdown hook). Waits for a
     * join still talking to the OS, but not forever.
     */
    fun shutdown() {
        synchronized(this) { generation++; stopTalking() }
        runBlocking { withTimeoutOrNull(SHUTDOWN_WAIT_MS) { forget() } }
    }

    /** At launch: a network left remembered by a crash or a force quit is forgotten. */
    fun cleanUpLeftover() {
        if (!marker.joined) return
        Log.i(TAG, "forgetting a CrossDrop Wi-Fi left from an earlier run")
        scope.launch(Dispatchers.IO) { forget { _state.value == State.Idle } }
    }

    /** Clears a failure shown to the person. */
    @Synchronized
    fun dismiss() { if (_state.value is State.Failed) _state.value = State.Idle }

    /** Under the lock. */
    private fun stopTalking() {
        job?.cancel(); job = null
        hostId?.let(known::remove); hostId = null
    }

    /** Sets the state only for the current generation. */
    @Synchronized
    private fun publish(gen: Int, s: State): Boolean {
        if (gen != generation) return false
        _state.value = s
        return true
    }

    /**
     * Forgets the network (and leaves it, when this computer is still on it) if we may have left it remembered. Never
     * cancelled half-way. [onlyIf] is checked under the OS lock: false means a newer join owns the network now.
     */
    private suspend fun forget(onlyIf: () -> Boolean = { true }) = withContext(NonCancellable) {
        osLock.withLock {
            if (!marker.joined || !onlyIf()) return@withLock
            val stillOn = onNetwork(localIp())
            runCatching { os.forget(DirectWifi.SSID, disconnect = stillOn) }
                .onFailure { Log.w(TAG, "forgetting the network failed", it) }
            marker.joined = false
        }
    }

    private suspend fun run(gen: Int) {
        val result = osLock.withLock {
            if (gen != generation) return
            marker.joined = true           // before: a crash during the join still gets cleaned up at the next launch
            os.join(DirectWifi.SSID, DirectWifi.PASSWORD)
        }
        if (gen != generation) {
            // Left (or quit) while the OS was joining: forget it, unless a newer join wants the network now.
            forget { _state.value == State.Idle || _state.value is State.Failed }
            return
        }
        val failure = when (result) {
            WifiJoiner.Result.Ok -> null
            WifiJoiner.Result.NotFound -> Failure.NOT_FOUND
            WifiJoiner.Result.NoWifi -> Failure.NO_WIFI
            WifiJoiner.Result.Unsupported -> Failure.FAILED
            is WifiJoiner.Result.Failed -> { Log.w(TAG, "join failed: ${result.detail}"); Failure.FAILED }
        }
        if (failure != null) {
            // A failed join may still have left the network remembered (or still activating): forget it.
            forget { generation == gen }
            publish(gen, State.Failed(failure))
            return
        }

        // The OS took it; our address moves onto the phone's network a moment later (DHCP).
        var ip = localIp()
        var waited = 0L
        while (!onNetwork(ip) && waited < timing.addressWaitMs) {
            delay(timing.stepMs); waited += timing.stepMs
            if (gen != generation) return
            ip = localIp()
        }
        if (!onNetwork(ip)) {
            // Joined something, but not the phone's network (or no address from it): don't stay on it.
            Log.w(TAG, "no address on the CrossDrop Wi-Fi (now $ip)")
            forget { generation == gen }
            publish(gen, State.Failed(Failure.FAILED))
            return
        }
        if (!publish(gen, State.Connecting)) return
        talk(gen, hostFor(ip))
    }

    /** Announce to the phone until it answers, then keep it current; notice when the network is gone. */
    private suspend fun talk(gen: Int, host: Endpoint) {
        var first = true
        var misses = 0
        var offNetwork = 0
        while (gen == generation) {
            if (!onNetwork(localIp())) {
                if (++offNetwork >= 2) {
                    synchronized(this) {
                        if (gen != generation) return
                        hostId?.let(known::remove); hostId = null
                    }
                    forget { generation == gen }
                    publish(gen, State.Failed(Failure.LOST))
                    return
                }
            } else offNetwork = 0
            val answer = runCatching {
                engine.control(host) { announce(listenPort()); Triple(peerName, peerInstanceId, peerOs) }
            }.onFailure { Log.i(TAG, "announce to $host: ${it.message}") }.getOrNull()
            if (answer != null) {
                val (name, id, platform) = answer
                // Under the lock with the generation check: a Leave in between must not see the phone come back.
                synchronized(this) {
                    if (gen != generation) return
                    hostId = id
                    known.add(id, name, host, platform)
                }
                misses = 0
                if (first || (_state.value as? State.Joined)?.hostName != name) publish(gen, State.Joined(name))
                first = false
                delay(timing.reannounceMs)
            } else {
                misses++
                // The phone may need a moment; after that stay on the network even if it doesn't answer (discovery
                // may still find it). A phone that stopped answering is no longer named as linked.
                if ((first && misses == timing.firstTries) || (!first && misses == 2)) publish(gen, State.Joined(null))
                delay(if (first && misses < timing.firstTries) timing.firstRetryMs else timing.reannounceMs)
            }
        }
    }

    private companion object {
        const val TAG = "DirectWifiJoin"
        const val SHUTDOWN_WAIT_MS = 20_000L
    }
}
