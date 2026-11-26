package com.crossdrop.core

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.share.HttpShareServer
import com.crossdrop.core.transfer.OutgoingFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class HttpShareServerTest {

    private val token = "testtoken0123456789"
    private val payloadA = ByteArray(100_000) { (it % 251).toByte() }
    private val payloadB = "hello receiver".toByteArray()
    private val files = listOf(
        OutgoingFile(FileMeta("a.bin", payloadA.size.toLong(), "application/octet-stream")) {
            ByteArrayInputStream(payloadA)
        },
        OutgoingFile(FileMeta("greeting.txt", payloadB.size.toLong(), "text/plain")) {
            ByteArrayInputStream(payloadB)
        },
    )

    private var server: HttpShareServer? = null

    private fun start(): HttpShareServer =
        HttpShareServer(files, "TestPhone", token).also { it.start(); server = it }

    @After
    fun tearDown() { server?.close() }

    private fun get(path: String, method: String = "GET"): Pair<Int, ByteArray> {
        val url = URL("http://127.0.0.1:${server!!.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        val body = runCatching {
            (conn.inputStream ?: conn.errorStream)?.readBytes() ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        conn.disconnect()
        return code to body
    }

    @Test
    fun `page serves at token root`() {
        start()
        val (code, body) = get("/$token/")
        assertEquals(200, code)
        assertTrue(String(body).contains("CrossDrop"))
    }

    @Test
    fun `token-less and wrong-token paths are invisible`() {
        start()
        assertEquals(404, get("/").first)
        assertEquals(404, get("/wrongtoken/").first)
        assertEquals(404, get("/wrongtoken/f/0").first)
        assertEquals(404, get("/$token/../etc/passwd").first)
    }

    @Test
    fun `manifest lists device and files`() {
        start()
        val (code, body) = get("/$token/manifest.json")
        assertEquals(200, code)
        val json = String(body)
        assertTrue(json.contains("\"TestPhone\""))
        assertTrue(json.contains("\"a.bin\""))
        assertTrue(json.contains("\"greeting.txt\""))
        assertTrue(json.contains("\"zip\":true"))
    }

    @Test
    fun `file download returns exact bytes`() {
        start()
        val (code, body) = get("/$token/f/0")
        assertEquals(200, code)
        assertArrayEquals(payloadA, body)
        val (code2, body2) = get("/$token/f/1")
        assertEquals(200, code2)
        assertArrayEquals(payloadB, body2)
    }

    @Test
    fun `HEAD gives headers without body`() {
        start()
        val url = URL("http://127.0.0.1:${server!!.port}/$token/f/0")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        assertEquals(200, conn.responseCode)
        assertEquals(payloadA.size.toLong(), conn.contentLengthLong)
        conn.disconnect()
    }

    @Test
    fun `zip streams all files and parses back`() {
        start()
        val (code, body) = get("/$token/all.zip")
        assertEquals(200, code)
        val seen = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(body)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                seen[e.name] = z.readBytes()
            }
        }
        assertArrayEquals(payloadA, seen["a.bin"])
        assertArrayEquals(payloadB, seen["greeting.txt"])
        assertEquals(2, seen.size)
    }

    @Test
    fun `status tracks completed downloads`() {
        start()
        get("/$token/f/0")
        get("/$token/f/1")
        // The server bumps "done" just after it writes the last byte, so a fast client can read the
        // whole body and ask for the status before the counter moves (seen on Windows runners). Wait
        // for it, bounded, instead of racing it.
        var code = 0
        var json = ""
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val (c, body) = get("/$token/status.json")
            code = c; json = String(body)
            if (json.contains("\"done\":2") || System.nanoTime() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(200, code)
        assertTrue(json, json.contains("\"done\":2"))
        assertTrue(json.contains("\"bytes\":${payloadA.size + payloadB.size}"))
    }

    @Test
    fun `bad file index 404s`() {
        start()
        assertEquals(404, get("/$token/f/9").first)
        assertEquals(404, get("/$token/f/abc").first)
    }

    // ---- photo previews (v/<i>) ----

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val script = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".toByteArray()

    private fun startWithPhotos(): HttpShareServer {
        fun file(name: String, mime: String?, bytes: ByteArray) =
            OutgoingFile(FileMeta(name, bytes.size.toLong(), mime)) { ByteArrayInputStream(bytes) }
        val shared = listOf(
            file("beach.JPG", null, jpeg),                       // no MIME: the extension decides
            file("drawing.svg", "image/svg+xml", script),        // an image type that can carry script
            file("notes.txt", "text/plain", payloadB),
            file("trick.jpg", "text/html", script),              // the sender's MIME is never echoed for a preview
            file("pic.webp", "image/webp; charset=x", jpeg),
        )
        return HttpShareServer(shared, "TestPhone", token).also { it.start(); server = it }
    }

    private fun head(path: String): Map<String, String> {
        val conn = URL("http://127.0.0.1:${server!!.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.responseCode
        val h = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
        runCatching { conn.inputStream.readBytes() }
        conn.disconnect()
        return h
    }

    @Test
    fun `photos are served inline with an allow-listed type, everything else is not`() {
        startWithPhotos()
        val (code, body) = get("/$token/v/0")
        assertEquals(200, code)
        assertArrayEquals(jpeg, body)
        val h = head("/$token/v/0")
        assertEquals("image/jpeg", h["content-type"])
        assertTrue(h["content-disposition"]!!, h["content-disposition"]!!.startsWith("inline;"))
        assertEquals("nosniff", h["x-content-type-options"])
        assertEquals("sandbox", h["content-security-policy"])
        assertEquals("no-referrer", h["referrer-policy"])

        assertEquals("image/jpeg", head("/$token/v/3")["content-type"])   // named .jpg, claimed text/html
        assertEquals("image/webp", head("/$token/v/4")["content-type"])   // parameters dropped
        assertEquals(404, get("/$token/v/1").first)                       // SVG
        assertEquals(404, get("/$token/v/2").first)                       // text
        assertEquals(404, get("/$token/v/9").first)
        assertEquals(404, get("/$token/v/x").first)
        assertEquals(404, get("/wrongtoken/v/0").first)
    }

    @Test
    fun `downloads stay attachments and the manifest says which files are photos`() {
        startWithPhotos()
        val h = head("/$token/f/0")
        assertTrue(h["content-disposition"]!!.startsWith("attachment;"))
        assertEquals("sandbox", h["content-security-policy"])
        assertEquals("text/html", head("/$token/f/3")["content-type"])   // the download keeps its MIME, sandboxed
        assertEquals("sandbox", head("/$token/f/3")["content-security-policy"])
        val json = String(get("/$token/manifest.json").second)
        assertTrue(json, Regex("\"name\":\"beach\\.JPG\"[^}]*\"view\":true").containsMatchIn(json))
        assertTrue(json, Regex("\"name\":\"drawing\\.svg\"[^}]*\"view\":false").containsMatchIn(json))
        assertTrue(json, Regex("\"name\":\"notes\\.txt\"[^}]*\"view\":false").containsMatchIn(json))
        assertEquals("no-referrer", head("/$token/")["referrer-policy"])
    }

    @Test
    fun `preview types`() {
        assertEquals("image/jpeg", HttpShareServer.previewType("a.jpeg", null))
        assertEquals("image/heic", HttpShareServer.previewType("IMG_1.HEIC", "application/octet-stream"))
        assertEquals("image/png", HttpShareServer.previewType("noext", "IMAGE/PNG"))
        assertEquals(null, HttpShareServer.previewType("a.svg", "image/svg+xml"))
        assertEquals(null, HttpShareServer.previewType("a.html", "text/html"))
        assertEquals(null, HttpShareServer.previewType("jpg", null))
    }
}
