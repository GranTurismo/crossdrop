package com.crossdrop.core

import com.crossdrop.core.protocol.ProtocolException
import com.crossdrop.core.transfer.FailureAdvice
import com.crossdrop.core.transfer.FailureKind
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureAdviceTest {
    private fun kind(t: Throwable?, connected: Boolean = true, sending: Boolean = true, explicit: FailureKind? = null) =
        FailureAdvice.kindOf(t, explicit, connected, sending)

    @Test fun `nothing answering before the channel is up means unreachable`() {
        assertEquals(FailureKind.UNREACHABLE, kind(ConnectException("Connection refused"), connected = false))
        assertEquals(FailureKind.UNREACHABLE, kind(NoRouteToHostException("No route to host"), connected = false))
        assertEquals(FailureKind.UNREACHABLE, kind(SocketTimeoutException("connect timed out"), connected = false))
        // a peer that hangs up during the handshake is also "couldn't reach"
        assertEquals(FailureKind.UNREACHABLE, kind(EOFException(), connected = false))
    }

    @Test fun `a socket dying after the channel was up means the connection was lost`() {
        assertEquals(FailureKind.CONNECTION_LOST, kind(SocketException("Connection reset")))
        assertEquals(FailureKind.CONNECTION_LOST, kind(SocketException("Broken pipe")))
        assertEquals(FailureKind.CONNECTION_LOST, kind(EOFException()))
        assertEquals(FailureKind.CONNECTION_LOST, kind(SocketTimeoutException("Read timed out")))
        // …also when the real cause is wrapped
        assertEquals(FailureKind.CONNECTION_LOST, kind(IOException("receive failed", SocketException("Connection reset"))))
    }

    @Test fun `a full disk, an unreadable source, a foreign protocol and the engine's own verdicts`() {
        assertEquals(FailureKind.DISK_FULL, kind(IOException("No space left on device")))
        assertEquals(FailureKind.DISK_FULL, kind(IOException("write failed", IOException("ENOSPC (No space left on device)"))))
        assertEquals(FailureKind.SOURCE_UNREADABLE, kind(FileNotFoundException("/x/a.pdf (No such file or directory)")))
        // a path the other device chose can contain the words: only the system's wording at the end counts
        assertEquals(FailureKind.OTHER, kind(IOException("/home/u/disk full/no space left on device.txt (Permission denied)")))
        // on the receiving side a missing file is not "couldn't read the file you picked"
        assertEquals(FailureKind.OTHER, kind(FileNotFoundException("/inbox/a.pdf"), sending = false))
        assertEquals(FailureKind.INCOMPATIBLE, kind(ProtocolException("expected HELLO_ACK, got ERROR"), connected = false))
        assertEquals(FailureKind.INTEGRITY, kind(null, explicit = FailureKind.INTEGRITY))
        assertEquals(FailureKind.OTHER, kind(IllegalStateException("boom")))
        assertEquals(FailureKind.OTHER, kind(null))
    }

    @Test fun `advice names the device and says what to try`() {
        val a = FailureAdvice.explain(ConnectException("refused"), "Connection refused", "Anna's phone", connected = false)
        assertEquals(FailureKind.UNREACHABLE, a.kind)
        assertTrue("Anna's phone" in a.title)
        assertNotNull(a.hint)
        // an unknown failure is shown as the engine said it, never hidden
        val other = FailureAdvice.explain(IllegalStateException("odd"), "odd thing happened", "Mac")
        assertEquals("odd thing happened", other.title)
        assertNull(other.hint)
    }

    @Test fun `a dropped connection to an iPhone explains that it must stay open`() {
        val ios = FailureAdvice.explain(SocketException("Connection reset"), null, "Gogita's iPhone", peerOs = Platforms.IOS)
        assertEquals(FailureAdvice.iosNote("Gogita's iPhone"), ios.hint)
        val android = FailureAdvice.explain(SocketException("Connection reset"), null, "Pixel", peerOs = Platforms.ANDROID)
        assertTrue(android.hint != ios.hint)
    }

    @Test fun `a protocol error after the handshake is not a version mismatch`() {
        // Only a handshake that goes wrong says "update CrossDrop"; the same exception type is also how a file that
        // changed while it was being sent, or a chunk that failed its hash, is reported.
        assertEquals(FailureKind.INCOMPATIBLE, kind(ProtocolException("expected HELLO_ACK, got ERROR"), connected = false))
        assertEquals(FailureKind.OTHER, kind(ProtocolException("expected CHUNK_REQ, got ERROR"), connected = true))
        assertEquals(FailureKind.SOURCE_UNREADABLE, kind(com.crossdrop.core.protocol.SourceChangedException("a.pdf changed mid-transfer")))
        assertEquals(FailureKind.INTEGRITY, kind(ProtocolException("chunk 3 failed hash check")))
        // a source that changed is the sender's problem only
        assertEquals(FailureKind.OTHER, kind(com.crossdrop.core.protocol.SourceChangedException("x"), sending = false))
    }
}
