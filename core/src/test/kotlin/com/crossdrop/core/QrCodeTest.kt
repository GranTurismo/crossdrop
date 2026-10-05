package com.crossdrop.core

import com.crossdrop.core.share.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class QrCodeTest {

    /** Render the matrix with quiet zone, decode with ZXing — end-to-end check. */
    private fun decode(qr: QrCode, scale: Int = 8, quiet: Int = 4): String {
        val pixels = (qr.size + quiet * 2) * scale
        val img = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, pixels, pixels, IntArray(pixels * pixels) { 0xFFFFFF }, 0, pixels)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) {
                img.setRGB((x + quiet) * scale, (y + quiet) * scale, scale, scale,
                    IntArray(scale * scale) { 0 }, 0, scale)
            }
        }
        return QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))
        ).text
    }

    @Test
    fun `short URL roundtrips through a real decoder`() {
        val url = "http://192.168.1.42:43871/9f2c8ab17d4e55019f2c8ab17d4e5501/"
        val qr = QrCode.encode(url)
        assertEquals(url, decode(qr))
    }

    @Test
    fun `unicode name survives byte mode`() {
        val text = "Grüße — файл 文件 🚀"
        assertEquals(text, decode(QrCode.encode(text)))
    }

    @Test
    fun `all error levels produce decodable codes`() {
        val payload = "http://10.0.2.16:42293/" + "ab".repeat(40) + "/"
        for (ecl in QrCode.EccLevel.entries) {
            assertEquals(payload, decode(QrCode.encode(payload, ecl)))
        }
    }

    @Test
    fun `finder patterns are present and size matches version`() {
        val qr = QrCode.encode("hello")
        assertEquals(1, qr.version)
        assertEquals(21, qr.size)
        assertTrue(qr.isDark(0, 0) && qr.isDark(6, 0) && qr.isDark(3, 3))
        assertTrue(qr.isDark(20, 20) && qr.isDark(14, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized payload is rejected`() {
        QrCode.encode(ByteArray(500) { it.toByte() }.decodeToString())
    }
}
