package com.crossdrop.core

import com.crossdrop.core.connect.DirectLinkMachine
import com.crossdrop.core.connect.DirectLinkState
import com.crossdrop.core.connect.WifiQr
import com.crossdrop.core.share.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.Result
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

/** The private link's QR code: still a plain Wi-Fi code for phone cameras, plus where CrossDrop connects. */
class JoinCodeTest {

    private val code = WifiQr.payload("AndroidShare_4821", "k9Qm-x2Lp", "WPA", "192.168.49.1", 40123, "0123456789abcdef")

    @Test
    fun `the address rides in its own field before the end`() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_4821;P:k9Qm-x2Lp;CD:192.168.49.1/40123/0123456789abcdef;;", code)
    }

    @Test
    fun `CrossDrop reads everything back`() {
        val j = WifiQr.parse(code)!!
        assertEquals("AndroidShare_4821", j.ssid)
        assertEquals("k9Qm-x2Lp", j.password)
        assertEquals("WPA", j.security)
        assertEquals(Endpoint("192.168.49.1", 40123), j.host)
        assertEquals("0123456789abcdef", j.instanceId)
    }

    /** What a phone camera (ZXing's reader, the same rules as Android's) makes of it: the network, nothing else. */
    @Test
    fun `phone cameras still read the network from the extended code`() {
        val parsed = ResultParser.parseResult(Result(code, null, null, null)) as WifiParsedResult
        assertEquals("AndroidShare_4821", parsed.ssid)
        assertEquals("k9Qm-x2Lp", parsed.password)
        assertEquals("WPA", parsed.networkEncryption)
    }

    @Test
    fun `the extended code survives a real QR encode and decode`() {
        assertEquals(code, decode(QrCode.encode(code)))
    }

    @Test
    fun `router codes parse without an address`() {
        val j = WifiQr.parse("WIFI:S:Home Net;T:WPA;P:secret 123;H:true;;")!!
        assertEquals("Home Net", j.ssid)
        assertEquals("secret 123", j.password)
        assertTrue(j.hidden)
        assertNull(j.host)
        val open = WifiQr.parse("WIFI:S:Cafe;T:nopass;;")!!
        assertEquals("nopass", open.security)
        assertNull(open.password)
        assertEquals("WPA", WifiQr.parse("wifi:S:x;P:abcdefgh;;")!!.security)   // no type, but a password
    }

    @Test
    fun `escapes and quoted hex values come back as typed`() {
        val tricky = WifiQr.payload("""a;b,c:d\e"f""", "p;w")
        val j = WifiQr.parse(tricky)!!
        assertEquals("""a;b,c:d\e"f""", j.ssid)
        assertEquals("p;w", j.password)
        val hex = WifiQr.parse(WifiQr.payload("Home", "deadbeef12"))!!
        assertEquals("deadbeef12", hex.password)
        val quotedSsid = WifiQr.parse(WifiQr.payload("CAFE", "xyz12345"))!!
        assertEquals("CAFE", quotedSsid.ssid)
    }

    @Test
    fun `anything that isn't a Wi-Fi code is refused, a broken address is ignored`() {
        assertNull(WifiQr.parse("http://192.168.1.4:8080/abc/"))
        assertNull(WifiQr.parse("WIFI:T:WPA;P:x;;"))   // no network name
        val bad = WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:999.1.1.1/80/abcdef12;;")!!
        assertNull(bad.host)
        val badPort = WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/0;;")!!
        assertNull(badPort.host)
        // Junk is never written into a code either.
        assertFalse(WifiQr.payload("n", "pppppppp", host = "evil;host", port = 80).contains("CD:"))
    }

    @Test
    fun `the hotspot's address is added to a running link only`() {
        val m = DirectLinkMachine()
        val r = m.begin()!!
        assertFalse(m.located(r, "192.168.49.1", 4000, "abcdef0123456789"))    // still starting
        m.started(r, DirectLinkState.On("S", "pppppppp", "WPA"))
        assertTrue(m.located(r, "192.168.49.1", 4000, "abcdef0123456789"))
        val on = m.state.value as DirectLinkState.On
        assertEquals(Endpoint("192.168.49.1", 4000), WifiQr.parse(on.qr)!!.host)
        m.stop()
        assertFalse(m.located(r, "192.168.49.1", 4000, "abcdef0123456789"))
    }

    private fun decode(qr: QrCode, scale: Int = 6, quiet: Int = 4): String {
        val pixels = (qr.size + quiet * 2) * scale
        val img = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, pixels, pixels, IntArray(pixels * pixels) { 0xFFFFFF }, 0, pixels)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) img.setRGB((x + quiet) * scale, (y + quiet) * scale, scale, scale, IntArray(scale * scale) { 0 }, 0, scale)
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))).text
    }
}
