package com.crossdrop.core.review

/**
 * When it is fair to ask for a store rating: right after a run of transfers that worked, never after one
 * that failed, and rarely. The platform decides how to ask (Play's in-app review, StoreKit); this only
 * decides *when*, and keeps three numbers in [Store]. Nothing leaves the device and nothing about what
 * was sent is kept.
 *
 *  - a streak of [MIN_STREAK] successful transfers in a row (a failure starts it over; a decline or a
 *    cancel by the person is neither),
 *  - at least [MIN_GAP_MS] since the last time we asked, and at most [MAX_ASKS] times ever. The store
 *    may also decide to show nothing; we can't tell and don't retry sooner.
 */
class ReviewPolicy(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    /** A tiny key/value store (SharedPreferences, UserDefaults, a map in tests). */
    interface Store {
        fun getLong(key: String, default: Long): Long
        fun putLong(key: String, value: Long)
    }

    /** A transfer ended: [success] true when it completed, false when it failed. Cancels and declines aren't reported. */
    fun onTransfer(success: Boolean) {
        store.putLong(STREAK, if (success) store.getLong(STREAK, 0) + 1 else 0)
    }

    /** True when the moment is right *now*; call it when a good moment arrives (the person closed the "done" screen). */
    fun shouldAsk(): Boolean {
        if (store.getLong(STREAK, 0) < MIN_STREAK) return false
        val asks = store.getLong(ASKS, 0)
        if (asks >= MAX_ASKS) return false
        val last = store.getLong(LAST_ASK, 0)
        return asks == 0L || now() - last >= MIN_GAP_MS
    }

    /** Record that we asked: the streak starts over, so the next ask needs a fresh run of good transfers. */
    fun asked() {
        store.putLong(ASKS, store.getLong(ASKS, 0) + 1)
        store.putLong(LAST_ASK, now())
        store.putLong(STREAK, 0)
    }

    companion object {
        const val MIN_STREAK = 3
        const val MAX_ASKS = 3
        const val MIN_GAP_MS = 120L * 24 * 60 * 60 * 1000
        private const val STREAK = "review_streak"
        private const val ASKS = "review_asks"
        private const val LAST_ASK = "review_last_ask"
    }
}
