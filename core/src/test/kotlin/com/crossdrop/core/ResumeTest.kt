package com.crossdrop.core

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.core.transfer.FailureKind
import com.crossdrop.core.transfer.InboundRequest
import com.crossdrop.core.transfer.ResumableOffers
import com.crossdrop.core.transfer.ResumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {
    private val policy = ResumePolicy()

    @Test fun `only a connection lost mid-way starts a resume, and once on it waiting or another loss keeps it going`() {
        assertTrue(policy.eligible(FailureKind.CONNECTION_LOST, connected = true, alreadyResuming = false))
        assertFalse("never reached it: that is just 'couldn't reach'", policy.eligible(FailureKind.UNREACHABLE, connected = false, alreadyResuming = false))
        for (k in listOf(FailureKind.DISK_FULL, FailureKind.INTEGRITY, FailureKind.SOURCE_UNREADABLE, FailureKind.INCOMPATIBLE, FailureKind.OTHER))
            assertFalse("$k won't fix itself", policy.eligible(k, connected = true, alreadyResuming = false))
        assertTrue(policy.eligible(FailureKind.UNREACHABLE, connected = false, alreadyResuming = true))
        assertTrue(policy.eligible(FailureKind.CONNECTION_LOST, connected = true, alreadyResuming = true))
        assertFalse("a full disk ends it even mid-resume", policy.eligible(FailureKind.DISK_FULL, connected = true, alreadyResuming = true))
    }

    @Test fun `pauses grow and then repeat, and the window ends the trying`() {
        val pauses = (0..8).map { policy.pauseMs(it) }
        assertEquals(pauses.sorted(), pauses)
        assertEquals(pauses.last(), policy.pauseMs(100))
        assertEquals(2_000, policy.pauseMs(0))
        assertFalse(policy.expired(0, 9 * 60_000))
        assertTrue(policy.expired(0, 10 * 60_000))
    }

    private fun request(files: List<FileMeta>, binding: ByteArray = ByteArray(32) { 7 }, id: String = "t1") =
        InboundRequest("Pixel", "inst", "123456", Offer(id, files), binding)

    private val a = FileMeta("a.pdf", 10)
    private val b = FileMeta("b.pdf", 20, dir = "Trip")

    @Test fun `the same launches offering the same files again is a resume, anything else is not`() {
        var now = 0L
        val r = ResumableOffers(ttlMs = 1_000) { now }
        val first = request(listOf(a, b))
        assertFalse("nothing accepted yet", r.matches(first))
        r.remember(first, "s1")

        assertTrue(r.matches(request(listOf(a, b), id = "t2")))
        assertFalse("another file", r.matches(request(listOf(a), id = "t3")))
        assertFalse("same name, different size", r.matches(request(listOf(a.copy(size = 11), b), id = "t4")))
        assertFalse("same files in another order", r.matches(request(listOf(b, a), id = "t5")))
        assertFalse("another app launch (other keys, other binding)", r.matches(request(listOf(a, b), binding = ByteArray(32) { 9 }, id = "t6")))
        assertEquals("s1", r.staleSession(first))
    }

    @Test fun `a repeat stops counting once the transfer completed or the time passed`() {
        var now = 0L
        val r = ResumableOffers(ttlMs = 1_000) { now }
        val first = request(listOf(a))
        r.remember(first, "s1")
        now = 900; assertTrue("each repeat renews it", r.matches(request(listOf(a), id = "t2")))
        now = 1_800; assertTrue(r.matches(request(listOf(a), id = "t3")))
        now = 3_000; assertFalse("expired", r.matches(request(listOf(a), id = "t4")))

        r.remember(first, "s2")
        r.completed(first)
        assertFalse("a send after completion is a new send", r.matches(request(listOf(a), id = "t5")))
        assertNull(r.staleSession(first))
        assertEquals(0, r.size())
    }

    @Test fun `the session key follows the binding and nothing else`() {
        assertEquals(request(listOf(a)).sessionKey, request(listOf(b), id = "other").sessionKey)
        assertNotEquals(request(listOf(a)).sessionKey, request(listOf(a), binding = ByteArray(32) { 1 }).sessionKey)
    }
}
