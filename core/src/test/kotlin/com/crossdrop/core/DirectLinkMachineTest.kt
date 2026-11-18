package com.crossdrop.core

import com.crossdrop.core.connect.DirectLinkFailure
import com.crossdrop.core.connect.DirectLinkMachine
import com.crossdrop.core.connect.DirectLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLinkMachineTest {
    private val on = DirectLinkState.On("AndroidShare_1", "secret123", "WPA")

    @Test fun `a request runs from starting to on`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        assertEquals(DirectLinkState.Starting, m.state.value)
        assertTrue(m.started(r, on))
        assertEquals(on, m.state.value)
    }

    @Test fun `a second request while one is starting or running is refused`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        assertNull(m.begin())
        m.started(r, on)
        assertNull(m.begin())
    }

    @Test fun `a request that never answers times out and its late answer is stale`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        assertTrue(m.timedOut(r))
        assertEquals(DirectLinkState.Failed(DirectLinkFailure.TIMEOUT), m.state.value)
        assertFalse("late start must be closed by the caller", m.started(r, on))
        assertEquals(DirectLinkState.Failed(DirectLinkFailure.TIMEOUT), m.state.value)
    }

    @Test fun `after a timeout the user can try again`() {
        val m = DirectLinkMachine()
        m.timedOut(m.begin()!!)
        assertNotNull(m.begin())
    }

    @Test fun `stop while starting cancels it`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        m.stop()
        assertEquals(DirectLinkState.Off, m.state.value)
        assertFalse(m.started(r, on))
        assertEquals(DirectLinkState.Off, m.state.value)
    }

    @Test fun `a late stopped from an old hotspot does not switch off the new one`() {
        val m = DirectLinkMachine()
        val old = m.begin()!!
        m.started(old, on)
        m.stop()
        val fresh = m.begin()!!
        m.started(fresh, on)
        m.stoppedBySystem(old)
        assertEquals(on, m.state.value)
    }

    @Test fun `the system ending the running hotspot turns it off`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        m.started(r, on)
        m.stoppedBySystem(r)
        assertEquals(DirectLinkState.Off, m.state.value)
    }

    @Test fun `a failure only counts for the current request`() {
        val m = DirectLinkMachine()
        val old = m.begin()!!
        m.timedOut(old)
        val fresh = m.begin()!!
        m.failed(old, DirectLinkFailure.BUSY)
        assertEquals(DirectLinkState.Starting, m.state.value)
        m.failed(fresh, DirectLinkFailure.BUSY)
        assertEquals(DirectLinkState.Failed(DirectLinkFailure.BUSY), m.state.value)
    }

    @Test fun `refusing never overrides a running link`() {
        val m = DirectLinkMachine()
        m.started(m.begin()!!, on)
        m.refuse(DirectLinkFailure.PERMISSION)
        assertEquals(on, m.state.value)
    }

    @Test fun `refusing shows the reason when idle`() {
        val m = DirectLinkMachine()
        m.refuse(DirectLinkFailure.LOCATION_OFF)
        assertEquals(DirectLinkState.Failed(DirectLinkFailure.LOCATION_OFF), m.state.value)
    }
}
