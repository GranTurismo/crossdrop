package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.VaultClient
import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultFs
import com.crossdrop.core.vault.VaultFs.VaultError
import com.crossdrop.core.vault.VaultServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class VaultTest {

    private lateinit var root: File
    private var server: VaultServer? = null
    private var client: VaultClient? = null

    private val blob = ByteArray(700_000) { ((it * 31) xor (it ushr 3)).toByte() }

    @Before fun setUp() {
        root = Files.createTempDirectory("vault-test").toFile().canonicalFile
        File(root, "docs").mkdirs()
        File(root, "docs/readme.txt").writeText("hello vault")
        File(root, "media").mkdirs()
        File(root, "media/clip.bin").writeBytes(blob)
        File(root, "pic.png").writeBytes(ByteArray(2048) { it.toByte() })
        File(root, ".secret").writeText("hidden")
        File(root, ".staging").mkdirs()
        File(root, ".staging/leftover.part").writeText("junk")
    }

    @After fun tearDown() {
        client?.close()
        server?.close()
        root.deleteRecursively()
    }

    private fun serve(writable: Boolean = true, pin: String? = null): VaultServer =
        VaultServer(FileVaultFs(root), SessionIdentity(), { "TestHost" },
            writable = writable, pin = pin, instanceId = "hostinstance01")
            .also { it.start(); server = it }

    private fun connect(pin: String? = null): VaultClient =
        VaultClient("127.0.0.1", server!!.port, SessionIdentity(),
            "ClientDev", "clientinstance")
            .also { it.connect(pin); client = it }

    // ------------------------------------------------------------- jail --

    @Test fun `jail rejects traversal, absolute and hidden paths`() {
        val fs = FileVaultFs(root)
        for (bad in listOf("..", "../x", "a/../../b", "..\\..\\win", "C:\\Windows",
            ".staging/x", ".hidden", "a/../b", "//..//x", "x\u0000y")) {
            try { fs.resolve(bad); fail("accepted $bad") }
            catch (e: VaultError) { assertTrue(e.code in 400..403) }
        }
        assertEquals(root, fs.resolve(""))
        assertEquals(root, fs.resolve("/"))
        assertEquals(File(root, "a/b"), fs.resolve("a/b"))
        assertEquals(File(root, "a/b"), fs.resolve("//a//b//"))
        assertEquals(File(root, "a/b"), fs.resolve("a/./b"))
        // leading slashes are stripped — the result stays inside the jail
        assertEquals(File(root, "etc/passwd"), fs.resolve("/etc/passwd"))
    }

    @Test fun `listing hides dotfiles and staging`() {
        val names = FileVaultFs(root).list("/").map { it.name }
        assertTrue("docs" in names && "media" in names && "pic.png" in names)
        assertFalse(".secret" in names)
        assertFalse(".staging" in names)
    }

    // -------------------------------------------------------- binary api --

    @Test fun `binary ls stat pull over encrypted session`() {
        serve(writable = true)
        val c = connect()
        val entries = c.ls("/")
        assertEquals(setOf("docs", "media", "pic.png"), entries.map { it.name }.toSet())
        assertTrue(entries.first { it.name == "docs" }.isDir)

        val attr = c.stat("media/clip.bin")
        assertEquals(blob.size.toLong(), attr.size)
        assertEquals(com.crossdrop.core.delta.Blake3.hash(
            ByteArrayInputStream(blob)).toHex(), attr.hash)

        // full pull
        val got = ByteArrayOutputStream()
        val done = c.pull("media/clip.bin", out = got)
        assertArrayEquals(blob, got.toByteArray())
        assertEquals(done.hash, com.crossdrop.core.delta.Blake3.hash(
            ByteArrayInputStream(blob)).toHex())

        // ranged pull
        val piece = ByteArrayOutputStream()
        c.pull("media/clip.bin", offset = 100_000, length = 123_456, out = piece)
        assertArrayEquals(blob.copyOfRange(100_000, 223_456), piece.toByteArray())
    }

    @Test fun `binary push lands in vault and ro vault rejects`() {
        serve(writable = true)
        val c = connect()
        val up = ByteArray(300_000) { (it % 97).toByte() }
        val done = c.push("uploads/photo.bin", up.size.toLong(),
            open = { ByteArrayInputStream(up) })
        assertTrue(done.path!!.endsWith(".bin"))
        assertArrayEquals(up, File(root, "uploads/photo.bin").readBytes())

        // read-only host
        val roRoot = Files.createTempDirectory("vault-ro").toFile()
        try {
            val ro = VaultServer(FileVaultFs(roRoot), SessionIdentity(), { "RO" },
                writable = false, pin = null, instanceId = "rohostinstance")
            ro.start()
            try {
                val rc = VaultClient("127.0.0.1", ro.port, SessionIdentity(),
                    "c", "cid")
                rc.connect()
                try {
                    rc.push("x.bin", 1, open = { ByteArrayInputStream(byteArrayOf(1)) })
                    fail("ro vault accepted push")
                } catch (e: VaultError) { assertEquals(403, e.code) }
                rc.close()
            } finally { ro.close() }
        } finally { roRoot.deleteRecursively() }
    }

    @Test fun `pin vault accepts right pin and drops wrong ones`() {
        serve(writable = true, pin = "4321")

        // no pin → 401 at connect
        try {
            VaultClient("127.0.0.1", server!!.port, SessionIdentity(),
                "c", "cid").connect()
            fail("unauthenticated connect succeeded")
        } catch (e: VaultError) { assertEquals(401, e.code) }

        // wrong pin → socket dropped → 401
        try {
            VaultClient("127.0.0.1", server!!.port, SessionIdentity(),
                "c", "cid").connect("0000")
            fail("wrong pin accepted")
        } catch (e: VaultError) { assertEquals(401, e.code) }

        // right pin works
        val c = connect("4321")
        assertTrue(c.requiresPin)
        assertTrue(c.ls("/").isNotEmpty())
    }

    @Test fun `upload dedupes existing names`() {
        serve(writable = true)
        val c = connect()
        c.push("pic.png", 3, open = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val again = c.push("pic.png", 1, open = { ByteArrayInputStream(byteArrayOf(9)) })
        assertNotEquals("pic.png", again.path)
        assertTrue(File(root, "pic.png").readBytes().size > 3)   // original intact
        assertTrue(File(root, again.path!!).exists())
    }

    @Test fun `malicious paths rejected over the wire`() {
        serve(writable = true)
        val c = connect()
        for (bad in listOf("../x", "..\\..\\escape", ".staging/x", "/etc/passwd")) {
            try { c.ls(bad); fail("ls accepted $bad") } catch (e: VaultError) {}
            try { c.stat(bad); fail("stat accepted $bad") } catch (e: VaultError) {}
        }
        try {
            c.push("../escape.bin", 1, open = { ByteArrayInputStream(byteArrayOf(1)) })
            fail("push accepted traversal")
        } catch (e: VaultError) {}
    }

    // ------------------------------------------------------------ http --

    private fun http(path: String, method: String = "GET",
                     headers: Map<String, String> = emptyMap(),
                     body: ByteArray? = null): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:${server!!.port}$path")
            .openConnection() as HttpURLConnection
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        if (body != null) { conn.doOutput = true; conn.outputStream.write(body) }
        val code = conn.responseCode
        val bytes = runCatching {
            (conn.inputStream ?: conn.errorStream)?.readBytes()
        }.getOrNull() ?: ByteArray(0)
        conn.disconnect()
        return code to bytes
    }

    @Test fun `same port serves page, api and ranged raw`() {
        serve(writable = true)
        // page
        val (pc, page) = http("/")
        assertEquals(200, pc); assertTrue(String(page).contains("Vault"))

        // api/fs
        val (fc, fs) = http("/api/fs?path=" + enc("/docs"))
        assertEquals(200, fc)
        assertTrue(String(fs).contains("readme.txt"))
        assertFalse(String(fs).contains(".staging"))

        // full raw
        val (rc, raw) = http("/raw/" + enc("/media/clip.bin"))
        assertEquals(200, rc); assertArrayEquals(blob, raw)

        // ranged raw → 206
        val conn = URL("http://127.0.0.1:${server!!.port}/raw/" + enc("/media/clip.bin"))
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=100-199")
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 100-199/${blob.size}",
            conn.getHeaderField("Content-Range"))
        assertArrayEquals(blob.copyOfRange(100, 200), conn.inputStream.readBytes())
        assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
        conn.disconnect()

        // suffix + unsatisfiable
        val suf = http("/raw/" + enc("/media/clip.bin"),
            headers = mapOf("Range" to "bytes=-10"))
        assertEquals(206, suf.first)
        assertArrayEquals(blob.copyOfRange(blob.size - 10, blob.size), suf.second)
        assertEquals(416, http("/raw/" + enc("/media/clip.bin"),
            headers = mapOf("Range" to "bytes=999999999-")).first)

        // traversal over http
        assertEquals(403, http("/raw/..%2f..%2fetc%2fpasswd").first)
        assertEquals(404, http("/nope").first)
    }

    @Test fun `http upload and read-only enforcement`() {
        serve(writable = true)
        val boundary = "----xdboundary"
        val data = ByteArray(50_000) { (it % 61).toByte() }
        val mp = buildMultipart(boundary, "notes.bin", data)
        val (code, resp) = http("/upload?path=" + enc("/docs"), "POST",
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), mp)
        assertEquals(200, code)
        assertTrue(String(resp).contains("notes.bin"))
        assertArrayEquals(data, File(root, "docs/notes.bin").readBytes())

        // ro vault: upload rejected
        val roRoot = Files.createTempDirectory("vault-ro").toFile()
        val ro = VaultServer(FileVaultFs(roRoot), SessionIdentity(), { "RO" },
            writable = false, pin = null, instanceId = "rohostinstance")
        ro.start()
        try {
            val conn = URL("http://127.0.0.1:${ro.port}/upload")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=$boundary")
            conn.outputStream.write(mp)
            assertEquals(403, conn.responseCode)
            conn.disconnect()
        } finally { ro.close(); roRoot.deleteRecursively() }
    }

    @Test fun `http pin gate`() {
        serve(writable = true, pin = "7777")
        assertEquals(401, http("/api/fs?path=/").first)
        assertEquals(401, http("/raw/pic.png").first)
        // page + info are open so the PIN prompt can render
        assertEquals(200, http("/").first)
        assertEquals(200, http("/api/info").first)
        assertEquals(200, http("/api/fs?path=/&pin=7777").first)
        assertEquals(200, http("/api/fs?path=/",
            headers = mapOf("X-Vault-Pin" to "7777")).first)
        // native client still works alongside
        val c = connect("7777")
        assertTrue(c.ls("/").isNotEmpty())
    }

    @Test fun `binary and http share one port`() {
        serve(writable = false)
        assertEquals(200, http("/api/info").first)
        val c = connect()
        assertTrue(c.ls("/").isNotEmpty())
        assertEquals(200, http("/").first)          // still alive after native session
        val got = ByteArrayOutputStream()
        c.pull("/pic.png", out = got)
        assertEquals(2048, got.size())
    }

    // ------------------------------------------------------------ helpers --

    private fun enc(p: String) =
        java.net.URLEncoder.encode(p, Charsets.UTF_8).replace("+", "%20")

    private fun buildMultipart(boundary: String, name: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("--$boundary\r\n")
        w("Content-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n")
        w("Content-Type: application/octet-stream\r\n\r\n")
        out.write(data)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
