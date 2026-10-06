package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

/**
 * WebDAV surface on VaultHttp: PROPFIND/multistatus, direct GET+Range, the
 * write verbs under `writable`, and Digest auth — over a real loopback
 * socket, the same bytes Finder and Explorer will send.
 */
class VaultDavTest {

    private lateinit var root: File
    private var server: VaultServer? = null

    private val blob = ByteArray(300_000) { ((it * 31) xor (it ushr 3)).toByte() }

    @Before fun setUp() {
        root = Files.createTempDirectory("dav-test").toFile()
        File(root, "docs").mkdirs()
        File(root, "docs/readme.txt").writeText("hello vault")
        File(root, "media").mkdirs()
        File(root, "media/clip.bin").writeBytes(blob)
        File(root, "pic.png").writeBytes(ByteArray(1024) { it.toByte() })
        File(root, ".secret").writeText("hidden")
    }

    @After fun tearDown() {
        server?.close()
        root.deleteRecursively()
    }

    private fun serve(writable: Boolean = true, pin: String? = null): VaultServer =
        VaultServer(FileVaultFs(root), SessionIdentity(), { "DavHost" },
            writable = writable, pin = pin, instanceId = "davinstance01")
            .also { it.start(); server = it }

    // --------------------------------------------------------- raw http --

    private data class Resp(val code: Int,
                            val headers: Map<String, String>,
                            val body: ByteArray)

    private fun dav(method: String, path: String,
                    headers: Map<String, String> = emptyMap(),
                    body: ByteArray? = null): Resp {
        Socket("127.0.0.1", server!!.port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            if (body != null) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            val raw = s.getInputStream().readBytes()
            val split = raw.asList().let { l ->
                var i = 0
                while (i + 3 < l.size &&
                    !(l[i] == '\r'.code.toByte() && l[i + 1] == '\n'.code.toByte() &&
                      l[i + 2] == '\r'.code.toByte() && l[i + 3] == '\n'.code.toByte())) i++
                i + 4
            }
            val head = String(raw.copyOfRange(0, split), Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val code = lines[0].split(" ")[1].toInt()
            val hs = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to
                    it.substringAfter(':').trim() }
            return Resp(code, hs, raw.copyOfRange(split, raw.size))
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------- tests --

    @Test fun `options advertises dav`() {
        serve()
        val r = dav("OPTIONS", "/")
        assertEquals(204, r.code)
        assertTrue(r.headers["dav"]!!.contains("1"))
        assertTrue(r.headers["allow"]!!.contains("PROPFIND"))
    }

    @Test fun `propfind lists root depth rules and hides dotfiles`() {
        serve()
        val r1 = dav("PROPFIND", "/", mapOf("Depth" to "1"))
        assertEquals(207, r1.code)
        val x1 = String(r1.body, Charsets.UTF_8)
        assertTrue(x1.contains("multistatus"))
        assertTrue(x1.contains("/docs/"))
        assertTrue(x1.contains("/media/"))
        assertTrue(x1.contains("<D:collection/>"))
        assertTrue(x1.contains("pic.png"))
        assertFalse(x1.contains(".secret"))
        assertFalse(x1.contains(".staging"))
        assertFalse(x1.contains("readme.txt"))      // depth 1 → no grandchildren
        assertFalse(x1.contains("clip.bin"))

        // depth 1 inside a subdir lists grandchildren with full props
        val rd = dav("PROPFIND", "/media/", mapOf("Depth" to "1"))
        val xm = String(rd.body, Charsets.UTF_8)
        assertTrue(xm.contains("clip.bin"))
        assertTrue(xm.contains("<D:getcontentlength>${blob.size}</D:getcontentlength>"))

        val r0 = dav("PROPFIND", "/docs", mapOf("Depth" to "0"))
        val x0 = String(r0.body, Charsets.UTF_8)
        assertEquals(207, r0.code)
        assertTrue(x0.contains("/docs/"))
        assertFalse(x0.contains("readme.txt"))      // depth 0 → self only

        assertEquals(403, dav("PROPFIND", "/",
            mapOf("Depth" to "infinity")).code)
        assertEquals(404, dav("PROPFIND", "/nope").code)
        assertEquals(403, dav("PROPFIND", "/..%2f..%2fetc").code)
    }

    @Test fun `dav get range and directory page`() {
        serve()
        assertEquals(200, dav("GET", "/media/clip.bin").let {
            assertArrayEquals(blob, it.body); it.code
        })
        val r = dav("GET", "/media/clip.bin", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, r.code)
        assertArrayEquals(blob.copyOfRange(100, 200), r.body)
        // GET on a directory serves the explorer page, not an error
        val (dc, db) = dav("GET", "/docs/").let { it.code to String(it.body) }
        assertEquals(200, dc); assertTrue(db.contains("<") && db.length > 20)
    }

    @Test fun `writable verbs create move copy delete`() {
        serve(writable = true)
        // PUT create → 201, replace → 204
        assertEquals(201, dav("PUT", "/new.txt", body = "v1".toByteArray()).code)
        assertEquals(204, dav("PUT", "/new.txt", body = "v2".toByteArray()).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/new.txt").body)
        // PUT into missing dir → 409
        assertEquals(409, dav("PUT", "/ghost/f.txt", body = byteArrayOf(1)).code)

        assertEquals(201, dav("MKCOL", "/fresh").code)
        assertEquals(405, dav("MKCOL", "/fresh").code)         // exists
        assertEquals(409, dav("MKCOL", "/ghost/sub").code)     // parent missing

        // MOVE to new location → 201, file landed
        assertEquals(201, dav("MOVE", "/new.txt",
            mapOf("Destination" to "/fresh/moved.txt")).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/fresh/moved.txt").body)
        // MOVE over existing without overwrite → 412
        dav("PUT", "/blocker.txt", body = "b".toByteArray())
        assertEquals(412, dav("MOVE", "/fresh/moved.txt",
            mapOf("Destination" to "/blocker.txt", "Overwrite" to "F")).code)

        // COPY leaves source in place
        assertEquals(201, dav("COPY", "/fresh/moved.txt",
            mapOf("Destination" to "/copy.txt")).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/copy.txt").body)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/fresh/moved.txt").body)

        assertEquals(204, dav("DELETE", "/copy.txt").code)
        assertEquals(404, dav("GET", "/copy.txt").code)
        assertEquals(204, dav("DELETE", "/fresh/").code)        // recursive
        assertEquals(404, dav("DELETE", "/fresh/").code)

        // lock stubs keep Finder happy on rw mounts
        val lock = dav("LOCK", "/pic.png", body = ByteArray(0))
        assertEquals(200, lock.code)
        assertTrue(lock.headers["lock-token"]!!.contains("opaquelocktoken"))
        assertEquals(204, dav("UNLOCK", "/pic.png").code)
    }

    @Test fun `read only blocks every write verb`() {
        serve(writable = false)
        assertEquals(403, dav("PUT", "/x.txt", body = byteArrayOf(1)).code)
        assertEquals(403, dav("MKCOL", "/d").code)
        assertEquals(403, dav("DELETE", "/pic.png").code)
        assertEquals(403, dav("MOVE", "/pic.png",
            mapOf("Destination" to "/m.png")).code)
        assertEquals(403, dav("COPY", "/pic.png",
            mapOf("Destination" to "/c.png")).code)
        assertEquals(403, dav("LOCK", "/pic.png").code)
        // reads still fine
        assertEquals(207, dav("PROPFIND", "/", mapOf("Depth" to "1")).code)
        assertEquals(200, dav("GET", "/pic.png").code)
    }

    @Test fun `digest auth gates dav when pin set`() {
        serve(pin = "4821")
        // no creds → 401 with Digest challenge
        val chal = dav("PROPFIND", "/", mapOf("Depth" to "1"))
        assertEquals(401, chal.code)
        val wa = chal.headers["www-authenticate"] ?: error("no challenge")
        assertTrue(wa.startsWith("Digest"))
        val nonce = Regex("""nonce="([^"]+)"""").find(wa)!!.groupValues[1]

        // valid digest → 207
        val cnonce = "abcdef0123456789"
        val ha1 = md5("drive:crossdrop:4821")
        val ha2 = md5("PROPFIND:/")
        val resp = md5("$ha1:$nonce:00000001:$cnonce:auth:$ha2")
        val auth = "Digest username=\"drive\", realm=\"crossdrop\", uri=\"/\"," +
            " nonce=\"$nonce\", nc=00000001, cnonce=\"$cnonce\", qop=auth," +
            " response=\"$resp\""
        assertEquals(207, dav("PROPFIND", "/", mapOf(
            "Depth" to "1", "Authorization" to auth)).code)

        // wrong password → still 401
        val bad = md5("${
            md5("drive:crossdrop:9999")}:$nonce:00000001:$cnonce:auth:$ha2")
        val authBad = auth.replace(resp, bad)
        assertEquals(401, dav("PROPFIND", "/", mapOf(
            "Depth" to "1", "Authorization" to authBad)).code)

        // pin header still works for the browser page
        assertEquals(200, dav("GET", "/pic.png",
            mapOf("X-Vault-Pin" to "4821")).code)
        assertEquals(207, dav("PROPFIND", "/", mapOf(
            "Depth" to "1", "X-Vault-Pin" to "4821")).code)
    }
}
