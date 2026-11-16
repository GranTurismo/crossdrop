package com.crossdrop

import com.crossdrop.ui.decodeImage
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Phone photos store their rotation in EXIF; the live viewer must show them upright. */
class ExifOrientationTest {

    /** 80×40 JPEG: left half red, right half blue, tagged with [orientation]. */
    private fun jpeg(orientation: Int): ByteArray {
        val img = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED; g.fillRect(0, 0, 40, 40)
        g.color = Color.BLUE; g.fillRect(40, 0, 40, 40)
        g.dispose()
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        // APP1 "Exif\0\0" + big-endian TIFF with one IFD entry: Orientation (0x0112), SHORT, 1.
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    private fun decode(orientation: Int) =
        File.createTempFile("exif", ".jpg").apply { writeBytes(jpeg(orientation)); deleteOnExit() }
            .let { decodeImage(it, 4096)!!.toPixelMap() }

    private fun isRed(c: androidx.compose.ui.graphics.Color) = c.red > 0.7f && c.blue < 0.3f
    private fun isBlue(c: androidx.compose.ui.graphics.Color) = c.blue > 0.7f && c.red < 0.3f

    @Test fun upright() {
        val p = decode(1)
        assertEquals(80, p.width); assertEquals(40, p.height)
        assertTrue(isRed(p[10, 20])); assertTrue(isBlue(p[70, 20]))
    }

    @Test fun rotate90ClockwisePutsLeftOnTop() {
        val p = decode(6)
        assertEquals(40, p.width); assertEquals(80, p.height)
        assertTrue(isRed(p[20, 10])); assertTrue(isBlue(p[20, 70]))
    }

    @Test fun rotate90CounterClockwisePutsLeftAtTheBottom() {
        val p = decode(8)
        assertEquals(40, p.width); assertEquals(80, p.height)
        assertTrue(isBlue(p[20, 10])); assertTrue(isRed(p[20, 70]))
    }

    @Test fun upsideDown() {
        val p = decode(3)
        assertTrue(isBlue(p[10, 20])); assertTrue(isRed(p[70, 20]))
    }

    @Test fun transposeAndTransverseStayInFrame() {
        val p5 = decode(5)
        assertEquals(40, p5.width); assertTrue(isRed(p5[20, 10])); assertTrue(isBlue(p5[20, 70]))
        val p7 = decode(7)
        assertEquals(40, p7.width); assertTrue(isBlue(p7[20, 10])); assertTrue(isRed(p7[20, 70]))
    }
}
