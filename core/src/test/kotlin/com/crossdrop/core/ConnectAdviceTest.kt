package com.crossdrop.core

import com.crossdrop.core.connect.ConnectAdvice
import com.crossdrop.core.connect.ConnectHint
import com.crossdrop.core.connect.WifiQr
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectAdviceTest {
    @Test fun `a visible device means nothing to say`() {
        assertEquals(ConnectHint.NONE, ConnectAdvice.hint(hasNetwork = false, deviceCount = 1, emptyForMs = 60_000))
        assertEquals(ConnectHint.NONE, ConnectAdvice.hint(hasNetwork = true, deviceCount = 2, emptyForMs = 60_000))
    }

    @Test fun `no network is announced after a short grace period`() {
        assertEquals(ConnectHint.NONE, ConnectAdvice.hint(false, 0, 500))
        assertEquals(ConnectHint.NO_NETWORK, ConnectAdvice.hint(false, 0, ConnectAdvice.NO_NETWORK_AFTER_MS))
    }

    @Test fun `an empty radar on a network gets help only after the wait`() {
        assertEquals(ConnectHint.NONE, ConnectAdvice.hint(true, 0, ConnectAdvice.STUCK_AFTER_MS - 1))
        assertEquals(ConnectHint.NOBODY_FOUND, ConnectAdvice.hint(true, 0, ConnectAdvice.STUCK_AFTER_MS))
    }

    @Test fun `a running direct connection silences the card`() {
        assertEquals(ConnectHint.NONE, ConnectAdvice.hint(false, 0, 60_000, directLinkOn = true))
    }

    @Test fun `wifi qr has the standard shape`() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_4821;P:k9Qm-x2Lp;;", WifiQr.payload("AndroidShare_4821", "k9Qm-x2Lp"))
        assertEquals("WIFI:T:SAE;S:Net;P:pass word;;", WifiQr.payload("Net", "pass word", "SAE"))
        assertEquals("WIFI:T:nopass;S:Open;;", WifiQr.payload("Open", null))
    }

    @Test fun `reserved characters are escaped`() {
        val expected = """WIFI:T:WPA;S:a\;b\,c\:d\\e\"f;P:p\;w;;"""
        assertEquals(expected, WifiQr.payload("""a;b,c:d\e"f""", "p;w"))
    }

    @Test fun `hex-looking values are quoted`() {
        assertEquals("WIFI:T:WPA;S:Home;P:\"deadbeef12\";;", WifiQr.payload("Home", "deadbeef12"))
        assertEquals("WIFI:T:WPA;S:\"CAFE\";P:xyz12345;;", WifiQr.payload("CAFE", "xyz12345"))
    }
}
