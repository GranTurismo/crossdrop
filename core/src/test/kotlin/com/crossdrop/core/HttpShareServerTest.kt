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
        val (code, body) = get("/$token/status.json")
        assertEquals(200, code)
        val json = String(body)
        assertTrue(json.contains("\"done\":2"))
        assertTrue(json.contains("\"bytes\":${payloadA.size + payloadB.size}"))
    }

    @Test
    fun `bad file index 404s`() {
        start()
        assertEquals(404, get("/$token/f/9").first)
        assertEquals(404, get("/$token/f/abc").first)
    }
}
