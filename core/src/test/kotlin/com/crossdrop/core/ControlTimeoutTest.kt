package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.transfer.TransferEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ControlTimeoutTest {
    /** A device that takes the connection and never says a word (asleep, wedged, half-open). */
    @Test fun `a quick check of a silent device gives up when told to, not after the long handshake timeout`() = runBlocking {
        val silent = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val held = ArrayList<java.net.Socket>()
        thread(isDaemon = true) { while (!silent.isClosed) runCatching { held += silent.accept() } }
        try {
            val engine = TransferEngine(SessionIdentity(), { "me" }, "id")
            val started = System.currentTimeMillis()
            try {
                engine.control(Endpoint("127.0.0.1", silent.localPort), connectTimeoutMs = 500, readTimeoutMs = 600) { pairCheck(emptyList()) }
                fail("a silent device cannot answer")
            } catch (_: Exception) { /* timed out */ }
            val took = System.currentTimeMillis() - started
            assertTrue("gave up after ${took}ms", took < 4_000)
        } finally { silent.close(); held.forEach { runCatching { it.close() } } }
    }
}
