package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.shake.ShakeGesture
import com.crossdrop.core.shake.ShakeMatcher
import com.crossdrop.core.transfer.ControlHandler
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.math.sin

/** Shake to connect: the matcher, the gesture, and the SHAKE exchange. */
class ShakeTest {

    @Test
    fun `our shake and theirs match in either order, within the window only`() {
        var now = 10_000L
        val m = ShakeMatcher(clock = { now })
        // They shook first, we follow a second later.
        assertNull(m.remote("aaaaaaaaaaaaaaaa", "Pixel"))
        now += 1_000
        assertEquals("aaaaaaaaaaaaaaaa", m.local()?.instanceId)
        // We shook first, theirs arrives two seconds later.
        now += 10_000
        assertNull(m.local())
        now += 2_000
        assertEquals("Galaxy", m.remote("bbbbbbbbbbbbbbbb", "Galaxy")?.name)
        // Too far apart: nothing.
        now += 10_000
        assertNull(m.local())
        now += 3_000
        assertNull(m.remote("cccccccccccccccc", "Far"))
    }

    @Test
    fun `each shake is used once and the closest message wins`() {
        var now = 0L
        val m = ShakeMatcher(clock = { now })
        m.remote("aaaaaaaaaaaaaaaa", "Early"); now += 1_500
        m.remote("bbbbbbbbbbbbbbbb", "Close"); now += 200
        assertEquals("Close", m.local()?.name)
        // The same shake can't open a second device, and the used message is gone.
        now += 100
        assertNull(m.remote("dddddddddddddddd", "Late"))
        assertEquals("Late", m.local()?.name)
        // Messages older than the window are forgotten.
        now += 3_000
        assertNull(m.local())
    }

    @Test
    fun `waitingSince says when we shook alone, until the window passes`() {
        var now = 0L
        val m = ShakeMatcher(clock = { now })
        assertNull(m.waitingSince())
        m.local()
        now += 1_000
        assertEquals(0L, m.waitingSince())
        now += 2_000
        assertNull(m.waitingSince())
    }

    /** 50 Hz samples of a waveform, in ns. */
    private fun feed(g: ShakeGesture, from: Long, ms: Long, accel: (Double) -> Triple<Double, Double, Double>): Int {
        var shakes = 0
        var t = 0L
        while (t <= ms) {
            val (x, y, z) = accel(t / 1000.0)
            if (g.sample(from + t * 1_000_000L, x, y, z)) shakes++
            t += 20
        }
        return shakes
    }

    @Test
    fun `a real shake counts once, gravity and a single bump don't`() {
        val g = ShakeGesture()
        assertEquals("lying still", 0, feed(g, 0, 3_000) { Triple(0.0, 0.0, 9.81) })
        assertEquals("one bump", 0, feed(g, 10_000_000_000L, 1_000) { t -> Triple(if (t in 0.2..0.24) 25.0 else 0.0, 0.0, 9.81) })
        // Shaking hard for a second: ±20 m/s² sideways at 4 Hz on top of gravity.
        val shake = { t: Double -> Triple(20.0 * (if (sin(t * 2 * Math.PI * 4) >= 0) 1 else -1), 0.0, 9.81) }
        assertEquals("one shake per burst (cooldown)", 1, feed(g, 20_000_000_000L, 1_200, shake))
        assertEquals("a later shake counts again", 1, feed(g, 25_000_000_000L, 1_200, shake))
    }

    @Test
    fun `walking with the phone doesn't count`() {
        val g = ShakeGesture()
        // Footsteps: brief 13+ m/s² peaks twice a second, mostly gravity in between.
        val walk = { t: Double -> Triple(0.0, 0.0, 9.81 + 5.0 * maxOf(0.0, sin(t * 2 * Math.PI * 2))) }
        assertEquals(0, feed(g, 0, 5_000, walk))
    }

    @Test
    fun `SHAKE reaches the listener with the sender's address and port`() = runBlocking {
        val listener = ServerSocket(0)
        var seen: List<Any>? = null
        val handler = object : ControlHandler {
            override fun shaken(name: String, instanceId: String, host: String, port: Int, os: String?) { seen = listOf(name, instanceId, host, port) }
        }
        val rx = TransferEngine(SessionIdentity(), { "Galaxy" }, "fedcba9876543210")
        val served = async(Dispatchers.IO) { rx.receiveRequest(listener.accept(), handler) { ReceiveDecision.Reject() }.toList() }
        TransferEngine(SessionIdentity(), { "Pixel" }, "0123456789abcdef").control(Endpoint("127.0.0.1", listener.localPort)) { shake(40123) }
        val events = served.await()
        listener.close()
        assertEquals(listOf("Pixel", "0123456789abcdef", "127.0.0.1", 40123), seen)
        assertTrue(events.none { it is TransferEvent.Failed || it is TransferEvent.Completed })
        assertFalse(events.any { it is TransferEvent.AwaitingAccept })
    }
}
