package com.crossdrop.core.connect

/** What the radar should tell someone about the network, if anything. */
enum class ConnectHint {
    /** Nothing to say: a device is visible, or we haven't looked long enough to judge. */
    NONE,

    /** No Wi-Fi or Ethernet at all. */
    NO_NETWORK,

    /** On a network, but nobody has shown up: the other device is elsewhere, or the network blocks it. */
    NOBODY_FOUND,
}

/**
 * The one rule behind the "Can't see the other device?" card, kept free of UI so every platform
 * (and a test) decides the same way. [emptyForMs] is how long the radar has shown zero devices.
 */
object ConnectAdvice {
    /** Long enough for mDNS to answer on a healthy network, short enough to not leave anyone staring. */
    const val STUCK_AFTER_MS = 10_000L

    /** Network state flickers while Wi-Fi reconnects; don't announce it for a blip. */
    const val NO_NETWORK_AFTER_MS = 2_000L

    fun hint(hasNetwork: Boolean, deviceCount: Int, emptyForMs: Long, directLinkOn: Boolean = false): ConnectHint = when {
        directLinkOn || deviceCount > 0 -> ConnectHint.NONE
        !hasNetwork -> if (emptyForMs >= NO_NETWORK_AFTER_MS) ConnectHint.NO_NETWORK else ConnectHint.NONE
        emptyForMs >= STUCK_AFTER_MS -> ConnectHint.NOBODY_FOUND
        else -> ConnectHint.NONE
    }
}
