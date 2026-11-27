package com.crossdrop.core

import com.crossdrop.core.connect.DirectLinkState
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.connect.WifiQr
import com.google.zxing.Result
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CrossDrop Wi-Fi: one name and password on every phone, so others join without a code. */
class DirectWifiTest {

    @Test
    fun `the name is a valid Wi-Fi Direct group name and the password a valid WPA2 passphrase`() {
        // Android: "DIRECT-" + two letters/digits, then anything, 9 to 32 bytes in all.
        assertTrue(Regex("^DIRECT-[A-Za-z0-9]{2}.*").matches(DirectWifi.SSID))
        assertTrue(DirectWifi.SSID.toByteArray().size in 9..32)
        // WPA2-PSK: 8 to 63 printable ASCII characters. Short and plain enough to type on a laptop.
        assertTrue(DirectWifi.PASSWORD.length in 8..63)
        assertTrue(DirectWifi.PASSWORD.all { it in 'a'..'z' || it in '0'..'9' })
    }

    @Test
    fun `a one-tap join knows where the phone that made the network listens`() {
        val code = DirectWifi.joinCode()
        assertEquals(DirectWifi.SSID, code.ssid)
        assertEquals(DirectWifi.PASSWORD, code.password)
        assertEquals("WPA", code.security)
        assertEquals(Endpoint("192.168.49.1", DirectWifi.PORT), code.host)
        assertEquals(Endpoint("192.168.43.1", DirectWifi.PORT), DirectWifi.joinCode("192.168.43.1").host)
    }

    @Test
    fun `the host is the first address of the joiner's subnet`() {
        assertEquals("192.168.49.1", DirectWifi.hostFor("192.168.49.57"))
        assertEquals("10.20.30.1", DirectWifi.hostFor("10.20.30.4"))
        assertEquals("172.20.1.1", DirectWifi.hostFor("172.20.1.9"))
        // Nothing usable: the usual Wi-Fi Direct address.
        assertEquals(DirectWifi.HOST, DirectWifi.hostFor(null))
        assertEquals(DirectWifi.HOST, DirectWifi.hostFor("8.8.8.8"))
        assertEquals(DirectWifi.HOST, DirectWifi.hostFor("192.168.1"))
        assertEquals(DirectWifi.HOST, DirectWifi.hostFor("192.168.1.300"))
        assertEquals(DirectWifi.HOST, DirectWifi.hostFor("192.168.5.1"))   // we are the .1 ourselves
    }

    @Test
    fun `only the CrossDrop Wi-Fi counts as one, with or without Android's quotes`() {
        assertTrue(DirectWifi.isCrossDropWifi("DIRECT-CD-CrossDrop"))
        assertTrue(DirectWifi.isCrossDropWifi("\"DIRECT-CD-CrossDrop\""))
        assertFalse(DirectWifi.isCrossDropWifi("DIRECT-CD-CrossDrop2"))
        assertFalse(DirectWifi.isCrossDropWifi("DIRECT-xy-Printer"))
        assertFalse(DirectWifi.isCrossDropWifi(null))
        assertTrue(DirectWifi.onGroupSubnet("192.168.49.23"))
        assertFalse(DirectWifi.onGroupSubnet("192.168.49.1"))
        assertFalse(DirectWifi.onGroupSubnet("192.168.1.23"))
    }

    @Test
    fun `its code is an ordinary Wi-Fi code for phone cameras and still carries the address for CrossDrop`() {
        val on = DirectLinkState.On(
            DirectWifi.SSID, DirectWifi.PASSWORD, "WPA", "192.168.49.1", 40123, "0123456789abcdef", oneTap = true,
        )
        val parsed = ResultParser.parseResult(Result(on.qr, null, null, null)) as WifiParsedResult
        assertEquals(DirectWifi.SSID, parsed.ssid)
        assertEquals(DirectWifi.PASSWORD, parsed.password)
        assertEquals("WPA", parsed.networkEncryption)
        val ours = WifiQr.parse(on.qr)!!
        assertEquals(Endpoint("192.168.49.1", 40123), ours.host)
        assertTrue(DirectWifi.isCrossDropWifi(ours.ssid))
    }
}
