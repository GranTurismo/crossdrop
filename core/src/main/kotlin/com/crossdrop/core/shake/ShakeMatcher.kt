package com.crossdrop.core.shake

import kotlin.math.abs

/**
 * Shake to connect: pairs this device's own shake with SHAKE messages from devices on the radar.
 *
 * Every time is taken on this device's clock: when we were shaken, and when each SHAKE *arrived*. Two
 * people shaking together see each other's message land within a second or two of their own shake, so
 * the phones' clocks never need to agree. Whichever happens second (our shake, or their message) makes the
 * match; each shake and each message is used once, so one shake opens one device.
 */
class ShakeMatcher(
    private val clock: () -> Long = System::currentTimeMillis,
    /** How far apart our shake and their message may be. */
    val windowMs: Long = WINDOW_MS,
) {
    data class Match(val instanceId: String, val name: String, val atMs: Long)

    private var localAt = NEVER
    /** instanceId → (name, arrival time) of recent SHAKE messages. */
    private val remote = LinkedHashMap<String, Pair<String, Long>>()

    /** We were shaken: the device whose SHAKE arrived closest to now, if any arrived within the window. */
    @Synchronized
    fun local(): Match? {
        val now = clock()
        prune(now)
        val best = remote.entries.minByOrNull { abs(now - it.value.second) }
        return if (best != null) {
            remote.remove(best.key)
            localAt = NEVER
            Match(best.key, best.value.first, now)
        } else {
            localAt = now
            null
        }
    }

    /** A device said it was shaken: a match if we were shaken within the window. */
    @Synchronized
    fun remote(instanceId: String, name: String): Match? {
        val now = clock()
        prune(now)
        if (localAt != NEVER && now - localAt <= windowMs) {
            localAt = NEVER
            remote.remove(instanceId)
            return Match(instanceId, name, now)
        }
        remote[instanceId] = name to now
        while (remote.size > MAX_REMOTE) remote.remove(remote.keys.first())
        return null
    }

    /** When this device was last shaken without a match yet (for "now shake the other phone"); null if not lately. */
    @Synchronized
    fun waitingSince(): Long? = localAt.takeIf { it != NEVER && clock() - it <= windowMs }

    private fun prune(now: Long) {
        remote.entries.removeIf { now - it.value.second > windowMs }
    }

    companion object {
        const val WINDOW_MS = 2_500L
        private const val NEVER = Long.MIN_VALUE
        private const val MAX_REMOTE = 32
    }
}

/**
 * Turns accelerometer samples into "shaken" moments. A shake is a burst: within the last [spanNs], at least
 * three quarters of the samples are well above gravity, over at least half that span. One shake per
 * [cooldownNs]. Platform-free so every platform shakes alike (the Swift twin uses the same numbers).
 */
class ShakeGesture(
    /** m/s²; gravity alone is about 9.8, a deliberate shake goes well past this. */
    private val threshold: Double = 13.5,
    private val spanNs: Long = 500_000_000L,
    private val cooldownNs: Long = 1_500_000_000L,
) {
    private val times = ArrayDeque<Long>()
    private val strong = ArrayDeque<Boolean>()
    private var strongCount = 0
    private var lastShakeNs = Long.MIN_VALUE / 2

    /** Feed one sample (any time base in ns, x/y/z in m/s² including gravity); true when it completes a shake. */
    fun sample(timeNs: Long, x: Double, y: Double, z: Double): Boolean {
        val isStrong = x * x + y * y + z * z > threshold * threshold
        times.addLast(timeNs); strong.addLast(isStrong); if (isStrong) strongCount++
        while (times.isNotEmpty() && timeNs - times.first() > spanNs) {
            times.removeFirst(); if (strong.removeFirst()) strongCount--
        }
        val covered = timeNs - times.first() >= spanNs / 2
        if (covered && times.size >= 4 && strongCount * 4 >= times.size * 3 && timeNs - lastShakeNs >= cooldownNs) {
            lastShakeNs = timeNs
            times.clear(); strong.clear(); strongCount = 0
            return true
        }
        return false
    }
}
