package com.crossdrop.core

import com.crossdrop.core.review.ReviewPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private class MapStore : ReviewPolicy.Store {
        val m = HashMap<String, Long>()
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLong(key: String, value: Long) { m[key] = value }
    }

    private var clock = 1_000_000_000_000L
    private val store = MapStore()
    private val policy = ReviewPolicy(store) { clock }
    private val day = 24L * 60 * 60 * 1000

    @Test fun `waits for a run of successful transfers`() {
        assertFalse(policy.shouldAsk())
        policy.onTransfer(true); policy.onTransfer(true)
        assertFalse(policy.shouldAsk())
        policy.onTransfer(true)
        assertTrue(policy.shouldAsk())
    }

    @Test fun `a failure starts the run over`() {
        repeat(5) { policy.onTransfer(true) }
        policy.onTransfer(false)
        assertFalse("never right after a failure", policy.shouldAsk())
        policy.onTransfer(true); policy.onTransfer(true)
        assertFalse(policy.shouldAsk())
        policy.onTransfer(true)
        assertTrue(policy.shouldAsk())
    }

    @Test fun `asking resets the run and spaces the next ask out`() {
        repeat(3) { policy.onTransfer(true) }
        assertTrue(policy.shouldAsk())
        policy.asked()
        assertFalse(policy.shouldAsk())
        repeat(10) { policy.onTransfer(true) }
        assertFalse("too soon", policy.shouldAsk())
        clock += 119 * day
        assertFalse(policy.shouldAsk())
        clock += 2 * day
        assertTrue(policy.shouldAsk())
    }

    @Test fun `asks at most three times ever`() {
        repeat(3) {
            repeat(3) { policy.onTransfer(true) }
            assertTrue(policy.shouldAsk())
            policy.asked()
            clock += 200 * day
        }
        repeat(10) { policy.onTransfer(true) }
        assertFalse(policy.shouldAsk())
        assertEquals(3L, store.m["review_asks"])
    }
}
