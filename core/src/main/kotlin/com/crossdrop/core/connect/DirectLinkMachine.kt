package com.crossdrop.core.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface DirectLinkState {
    data object Off : DirectLinkState
    data object Starting : DirectLinkState
    data class On(
        val ssid: String,
        val password: String?,
        val security: String,
        /** This phone's address on the private network and its transfer listener, once known. */
        val host: String? = null,
        val port: Int = 0,
        val instanceId: String? = null,
    ) : DirectLinkState {
        /** What the QR code holds: phone cameras read it and offer to join; CrossDrop also reads [host]. */
        val qr: String get() = WifiQr.payload(ssid, password, security, host, port, instanceId)
    }
    data class Failed(val reason: DirectLinkFailure) : DirectLinkState
}

enum class DirectLinkFailure { PERMISSION, LOCATION_OFF, BUSY, NO_CHANNEL, NOT_ALLOWED, TIMEOUT, OTHER }

/**
 * The bookkeeping behind "Connect directly", free of Android so it can be tested. The phone answers a
 * hotspot request whenever it likes (or never), so every request gets a token and an answer for an
 * older token is stale: a late "started" after a timeout or a Stop is refused (the caller closes what
 * it got), and a late "stopped" from an old hotspot can't switch off a new one.
 */
class DirectLinkMachine {
    private val _state = MutableStateFlow<DirectLinkState>(DirectLinkState.Off)
    val state: StateFlow<DirectLinkState> = _state
    private var token = 0

    /** Starts a request: its token, or null when one is already starting or running. */
    @Synchronized fun begin(): Int? {
        if (_state.value is DirectLinkState.Starting || _state.value is DirectLinkState.On) return null
        _state.value = DirectLinkState.Starting
        return ++token
    }

    /** We can't even try (permission, Location off). Never overrides a request that is starting or running. */
    @Synchronized fun refuse(reason: DirectLinkFailure) {
        if (_state.value is DirectLinkState.Starting || _state.value is DirectLinkState.On) return
        _state.value = DirectLinkState.Failed(reason)
    }

    /** The system started the hotspot. False means this answer is stale: close what you were given. */
    @Synchronized fun started(request: Int, on: DirectLinkState.On): Boolean {
        if (request != token || _state.value !is DirectLinkState.Starting) return false
        _state.value = on
        return true
    }

    @Synchronized fun failed(request: Int, reason: DirectLinkFailure) {
        if (request != token || _state.value !is DirectLinkState.Starting) return
        _state.value = DirectLinkState.Failed(reason)
    }

    /** The hotspot's address became known: the code now also tells CrossDrop where to connect. */
    @Synchronized fun located(request: Int, host: String, port: Int, instanceId: String): Boolean {
        val on = _state.value as? DirectLinkState.On ?: return false
        if (request != token) return false
        _state.value = on.copy(host = host, port = port, instanceId = instanceId)
        return true
    }

    /** The system ended a running hotspot (the user turned on their own, Wi-Fi went off, …). */
    @Synchronized fun stoppedBySystem(request: Int) {
        if (request == token && _state.value is DirectLinkState.On) _state.value = DirectLinkState.Off
    }

    /** The start took too long. True when it was still waiting: its late answer is now stale. */
    @Synchronized fun timedOut(request: Int): Boolean {
        if (request != token || _state.value !is DirectLinkState.Starting) return false
        token++
        _state.value = DirectLinkState.Failed(DirectLinkFailure.TIMEOUT)
        return true
    }

    /** Stop (or cancel a start in flight): whatever the system answers later is stale. */
    @Synchronized fun stop() {
        token++
        _state.value = DirectLinkState.Off
    }

    /** Debug builds only: shows a state without a hotspot. */
    @Synchronized fun force(state: DirectLinkState) { token++; _state.value = state }
}
