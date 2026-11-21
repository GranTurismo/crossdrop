package com.crossdrop.core.transfer

/**
 * When an interrupted send is picked up again by itself. A send that loses its connection mid-way (a Wi-Fi
 * blip, the other device locking its screen) is retried with growing pauses until the device is back, for
 * [windowMs]; what already arrived is kept by the receiver's resume pool, so each try only moves what is left.
 * Pure: the hub supplies the clock and does the retrying.
 */
class ResumePolicy(
    /** How long after the first loss the send keeps trying before it is reported as failed. */
    val windowMs: Long = 10 * 60_000,
    /** Pauses before the 1st, 2nd… try; the last one repeats. */
    private val pausesMs: List<Long> = listOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000),
) {
    /**
     * Whether a send that just failed should wait and try again. A connection that dropped after the channel
     * was up starts it ([connected] and [FailureKind.CONNECTION_LOST]); once it is on, any network failure
     * ([FailureKind.UNREACHABLE] while the other device is still away, or another loss) keeps it going.
     */
    fun eligible(kind: FailureKind, connected: Boolean, alreadyResuming: Boolean): Boolean = when {
        alreadyResuming -> kind == FailureKind.UNREACHABLE || kind == FailureKind.CONNECTION_LOST
        else -> connected && kind == FailureKind.CONNECTION_LOST
    }

    /** The pause before try number [attempt] (0 = the first). */
    fun pauseMs(attempt: Int): Long = pausesMs[attempt.coerceIn(0, pausesMs.lastIndex)]

    fun expired(firstLossAtMs: Long, nowMs: Long): Boolean = nowMs - firstLossAtMs >= windowMs
}
