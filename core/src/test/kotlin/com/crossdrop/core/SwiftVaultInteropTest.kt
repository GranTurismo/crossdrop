package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.delta.Blake3
import com.crossdrop.core.vault.FileVaultFs
import com.crossdrop.core.vault.VaultClient
import com.crossdrop.core.vault.VaultFs.VaultError
import com.crossdrop.core.vault.VaultServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Wire-compat check for the Wireless Drive against the Swift port (ios/CrossDropCore). Skipped
 * unless CROSSDROP_SWIFT_CLI points at a built `crossdrop-cli` binary — same switch as
 * [SwiftInteropTest]:
 *
 *   (cd ios/CrossDropCore && swift build -c release)
 *   CROSSDROP_SWIFT_CLI=$PWD/ios/CrossDropCore/.build/release/crossdrop-cli \
 *     ./gradlew :core:test --tests '*SwiftVaultInterop*'
 *
 * Covers: Kotlin VaultClient ↔ Swift server (ls/stat/pull/range/push/RO/PIN/jail), Swift client ↔
 * Kotlin VaultServer, and the Swift server's HTTP/WebDAV surface over raw sockets (mirrors
 * VaultTest / VaultDavTest assertions).
 */
class SwiftVaultInteropTest {

    private val cli: String? = System.getenv("CROSSDROP_SWIFT_CLI")?.takeIf { File(it).canExecute() }

    private lateinit var root: File
    private var swift: Process? = null
    private var port = 0
    private var client: VaultClient? = null
    private var kServer: VaultServer? = null

    private val blob = ByteArray(700_000) { ((it * 31) xor (it ushr 3)).toByte() }

    @Before fun setUp() {
        root = Files.createTempDirectory("swvault").toFile().canonicalFile
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
        kServer?.close()
        swift?.destroy()
        swift?.waitFor(5, TimeUnit.SECONDS)
        root.deleteRecursively()
    }

    // --------------------------------------------------------- harness --

    private fun serveSwift(rw: Boolean = true, pin: String? = null, dir: File = root): Int {
        val cmd = mutableListOf(cli!!, "vault-serve", dir.absolutePath)
        if (rw) cmd += "--rw"
        if (pin != null) { cmd += "--pin"; cmd += pin }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        swift = p
        val reader = p.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 15_000
        var found = -1
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("LISTENING ")) { found = line.removePrefix("LISTENING ").trim().toInt(); break }
        }
        check(found > 0) { "swift vault did not start" }
        port = found
        return found
    }

    private fun connect(pin: String? = null): VaultClient =
        VaultClient("127.0.0.1", port, SessionIdentity(), "KotlinClient", "kclient01")
            .also { it.connect(pin); client = it }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------- kotlin client → swift --

    @Test fun `kotlin client browses and pulls from swift vault`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val c = connect()
        assertEquals("swift-vault", c.peerName)
        assertFalse(c.requiresPin)

        val entries = c.ls("/")
        assertEquals(setOf("docs", "media", "pic.png"), entries.map { it.name }.toSet())   // no dotfiles / .staging
        assertTrue(entries.first { it.name == "docs" }.isDir)
        assertTrue(entries.first { it.isDir }.let { entries.indexOf(it) } < entries.indexOfFirst { !it.isDir })  // dirs first

        val attr = c.stat("media/clip.bin")
        assertEquals(blob.size.toLong(), attr.size)
        assertEquals(hex(Blake3.hash(ByteArrayInputStream(blob))), attr.hash)

        val got = ByteArrayOutputStream()
        val done = c.pull("media/clip.bin", out = got)
        assertArrayEquals(blob, got.toByteArray())
        assertEquals(attr.hash, done.hash)

        val piece = ByteArrayOutputStream()
        c.pull("media/clip.bin", offset = 100_000, length = 123_456, out = piece)
        assertArrayEquals(blob.copyOfRange(100_000, 223_456), piece.toByteArray())
    }

    @Test fun `kotlin client pushes to swift vault and gets deduped names`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val c = connect()
        val up = ByteArray(600_000) { (it % 97).toByte() }       // > 2 data frames
        val done = c.push("uploads/photo.bin", up.size.toLong(), open = { ByteArrayInputStream(up) })
        assertEquals("uploads/photo.bin", done.path)
        assertArrayEquals(up, File(root, "uploads/photo.bin").readBytes())

        val again = c.push("pic.png", 1, open = { ByteArrayInputStream(byteArrayOf(9)) })
        assertEquals("pic (2).png", again.path)
        assertEquals(2048, File(root, "pic.png").length())        // original intact
        // zero-byte push
        val z = c.push("empty.bin", 0, open = { ByteArrayInputStream(ByteArray(0)) })
        assertEquals(0, File(root, z.path!!).length())
        // nothing left in staging
        assertEquals(listOf("leftover.part").sorted(), File(root, ".staging").list()!!.toList().sorted())
    }

    @Test fun `swift read-only vault rejects pushes`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = false)
        val c = connect()
        try { c.push("x.bin", 1, open = { ByteArrayInputStream(byteArrayOf(1)) }); fail("ro accepted push") }
        catch (e: VaultError) { assertEquals(403, e.code) }
        assertFalse(File(root, "x.bin").exists())
        assertTrue(c.ls("/").isNotEmpty())                        // session survives the error
    }

    @Test fun `swift pin vault accepts right pin and drops wrong ones`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true, pin = "4321")
        try { VaultClient("127.0.0.1", port, SessionIdentity(), "c", "cid").connect(); fail("no-pin connect") }
        catch (e: VaultError) { assertEquals(401, e.code) }
        try { VaultClient("127.0.0.1", port, SessionIdentity(), "c", "cid").connect("0000"); fail("wrong pin") }
        catch (e: VaultError) { assertEquals(401, e.code) }
        val c = connect("4321")
        assertTrue(c.requiresPin)
        assertTrue(c.ls("/").isNotEmpty())
    }

    @Test fun `swift vault jail rejects malicious paths over the wire`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val c = connect()
        for (bad in listOf("../x", "..\\..\\escape", ".staging/x", "/etc/passwd", ".secret", "C:\\Windows")) {
            try { c.ls(bad); fail("ls accepted $bad") } catch (e: VaultError) {}
            try { c.stat(bad); fail("stat accepted $bad") } catch (e: VaultError) {}
        }
        try { c.push("../escape.bin", 1, open = { ByteArrayInputStream(byteArrayOf(1)) }); fail("push traversal") }
        catch (e: VaultError) {}
        assertFalse(File(root.parentFile, "escape.bin").exists())
    }

    // ------------------------------------------- swift client → kotlin --

    private fun swiftClient(pin: String?, mode: String, vararg rest: String): Pair<Int, List<String>> {
        val cmd = mutableListOf(cli!!, mode, "127.0.0.1", kServer!!.port.toString())
        if (pin != null) { cmd += "--pin"; cmd += pin }
        cmd += rest
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        assertTrue("swift client timed out", p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue() to p.inputStream.bufferedReader().readLines()
    }

    private fun serveKotlin(writable: Boolean = true, pin: String? = null) {
        kServer = VaultServer(FileVaultFs(root), SessionIdentity(), { "KotlinHost" },
            writable = writable, pin = pin, instanceId = "khost01").also { it.start() }
    }

    @Test fun `swift client browses pulls and pushes against kotlin vault`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveKotlin()
        val (c1, ls) = swiftClient(null, "vault-ls", "/")
        assertEquals(ls.toString(), 0, c1)
        val names = ls.filter { it.startsWith("ENTRY") }.map { it.split("\t")[1] }
        assertEquals(setOf("docs", "media", "pic.png"), names.toSet())

        val (c2, st) = swiftClient(null, "vault-stat", "media/clip.bin")
        assertEquals(0, c2)
        val attr = st.first { it.startsWith("ATTR") }.split("\t")
        assertEquals(blob.size.toString(), attr[2])
        assertEquals(hex(Blake3.hash(ByteArrayInputStream(blob))), attr[4])

        val out = File(root.parentFile, "swift-pull-${System.nanoTime()}.bin")
        try {
            val (c3, pl) = swiftClient(null, "vault-pull", "media/clip.bin", out.absolutePath)
            assertEquals(pl.toString(), 0, c3)
            assertArrayEquals(blob, out.readBytes())
            val (c4, _) = swiftClient(null, "vault-pull", "media/clip.bin", out.absolutePath,
                "--offset", "100000", "--length", "123456")
            assertEquals(0, c4)
            assertArrayEquals(blob.copyOfRange(100_000, 223_456), out.readBytes())
        } finally { out.delete() }

        val src = File(root.parentFile, "swift-push-${System.nanoTime()}.bin").apply {
            writeBytes(ByteArray(900_000) { (it % 89).toByte() })
        }
        try {
            val (c5, pu) = swiftClient(null, "vault-push", src.absolutePath, "up/new.bin")
            assertEquals(pu.toString(), 0, c5)
            assertArrayEquals(src.readBytes(), File(root, "up/new.bin").readBytes())
            val (c6, pu2) = swiftClient(null, "vault-push", src.absolutePath, "up/new.bin")
            assertEquals(0, c6)
            assertTrue(pu2.toString(), pu2.any { it.contains("up/new (2).bin") })
        } finally { src.delete() }
    }

    @Test fun `swift client against kotlin pin and read-only vaults`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveKotlin(writable = false, pin = "2468")
        val (noPin, o1) = swiftClient(null, "vault-ls", "/")
        assertEquals(1, noPin); assertTrue(o1.toString(), o1.any { it.startsWith("ERROR 401") })
        val (bad, o2) = swiftClient("0000", "vault-ls", "/")
        assertEquals(1, bad); assertTrue(o2.toString(), o2.any { it.startsWith("ERROR 401") })
        val (ok, _) = swiftClient("2468", "vault-ls", "/")
        assertEquals(0, ok)
        val src = File(root.parentFile, "swift-ro-${System.nanoTime()}.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val (rc, o3) = swiftClient("2468", "vault-push", src.absolutePath, "nope.bin")
            assertEquals(1, rc); assertTrue(o3.toString(), o3.any { it.startsWith("ERROR 403") })
        } finally { src.delete() }
    }

    // ------------------------------------------------- swift http / dav --

    private data class Resp(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun dav(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Resp {
        Socket("127.0.0.1", port).use { s ->
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
            var i = 0
            while (i + 3 < raw.size && !(raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                    raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte())) i++
            val split = i + 4
            val lines = String(raw.copyOfRange(0, split), Charsets.ISO_8859_1).split("\r\n")
            val hs = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Resp(lines[0].split(" ")[1].toInt(), hs, raw.copyOfRange(split, raw.size))
        }
    }

    private fun http(path: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
                     body: ByteArray? = null): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        if (body != null) { conn.doOutput = true; conn.outputStream.write(body) }
        val code = conn.responseCode
        val bytes = runCatching { (conn.inputStream ?: conn.errorStream)?.readBytes() }.getOrNull() ?: ByteArray(0)
        conn.disconnect()
        return code to bytes
    }

    private fun enc(p: String) = java.net.URLEncoder.encode(p, Charsets.UTF_8).replace("+", "%20")
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    @Test fun `swift http page api and ranged raw`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val (pc, page) = http("/")
        assertEquals(200, pc); assertTrue(String(page).contains("Vault"))
        assertArrayEquals(File("../core/src/main/resources/vault.html").takeIf { it.exists() }?.readBytes() ?: page, page)

        val info = String(http("/api/info").second)
        assertTrue(info, info.contains("\"writable\":true") && info.contains("\"pin\":false") && info.contains("swift-vault"))

        val (fc, fs) = http("/api/fs?path=" + enc("/docs"))
        assertEquals(200, fc)
        assertTrue(String(fs).contains("readme.txt")); assertFalse(String(fs).contains(".staging"))
        val rootJson = String(http("/api/fs?path=/").second)
        assertFalse(rootJson.contains(".secret")); assertFalse(rootJson.contains(".staging"))

        val (rc, raw) = http("/raw/" + enc("/media/clip.bin"))
        assertEquals(200, rc); assertArrayEquals(blob, raw)

        val conn = URL("http://127.0.0.1:$port/raw/" + enc("/media/clip.bin")).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=100-199")
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 100-199/${blob.size}", conn.getHeaderField("Content-Range"))
        assertArrayEquals(blob.copyOfRange(100, 200), conn.inputStream.readBytes())
        assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
        conn.disconnect()

        val suf = http("/raw/" + enc("/media/clip.bin"), headers = mapOf("Range" to "bytes=-10"))
        assertEquals(206, suf.first)
        assertArrayEquals(blob.copyOfRange(blob.size - 10, blob.size), suf.second)
        assertEquals(416, http("/raw/" + enc("/media/clip.bin"), headers = mapOf("Range" to "bytes=999999999-")).first)

        assertEquals(403, http("/raw/..%2f..%2fetc%2fpasswd").first)
        assertEquals(404, http("/nope").first)
        assertEquals(404, http("/raw/.secret").first.let { if (it == 403) 404 else it })   // hidden: 403/404 both fine
    }

    @Test fun `swift http upload and read-only enforcement`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val boundary = "----xdboundary"
        val data = ByteArray(250_000) { (it % 61).toByte() }
        fun multipart(name: String): ByteArray {
            val out = ByteArrayOutputStream()
            fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n")
            w("Content-Type: application/octet-stream\r\n\r\n"); out.write(data)
            w("\r\n--$boundary--\r\n")
            return out.toByteArray()
        }
        val (code, resp) = http("/upload?path=" + enc("/docs"), "POST",
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), multipart("notes.bin"))
        assertEquals(200, code)
        assertTrue(String(resp).contains("notes.bin"))
        assertArrayEquals(data, File(root, "docs/notes.bin").readBytes())
        // same name again → deduped
        val (c2, r2) = http("/upload?path=" + enc("/docs"), "POST",
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), multipart("notes.bin"))
        assertEquals(200, c2); assertTrue(String(r2).contains("notes (2).bin"))
        // hidden filename rejected
        assertEquals(400, http("/upload?path=" + enc("/docs"), "POST",
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), multipart(".evil")).first)

        swift!!.destroy(); swift!!.waitFor(5, TimeUnit.SECONDS)
        val roRoot = Files.createTempDirectory("swvault-ro").toFile().canonicalFile
        try {
            serveSwift(rw = false, dir = roRoot)
            assertEquals(403, http("/upload", "POST", mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), multipart("a.bin")).first)
        } finally { roRoot.deleteRecursively() }
    }

    @Test fun `swift http pin gate and lockout`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true, pin = "7777")
        assertEquals(401, http("/api/fs?path=/").first)
        assertEquals(401, http("/raw/pic.png").first)
        assertEquals(200, http("/").first)
        assertEquals(200, http("/api/info").first)
        assertEquals(200, http("/api/fs?path=/&pin=7777").first)
        assertEquals(200, http("/api/fs?path=/", headers = mapOf("X-Vault-Pin" to "7777")).first)
        val c = connect("7777")
        assertTrue(c.ls("/").isNotEmpty())

        // 5 wrong guesses lock the IP out — even the right PIN gets 429 afterwards
        repeat(5) { assertEquals(401, http("/api/fs?path=/&pin=000$it").first) }
        assertEquals(429, http("/api/fs?path=/&pin=7777").first)
    }

    @Test fun `swift dav options propfind get and hidden files`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        val o = dav("OPTIONS", "/")
        assertEquals(204, o.code)
        assertTrue(o.headers["dav"]!!.contains("1")); assertTrue(o.headers["allow"]!!.contains("PROPFIND"))

        val r1 = dav("PROPFIND", "/", mapOf("Depth" to "1"))
        assertEquals(207, r1.code)
        val x1 = String(r1.body, Charsets.UTF_8)
        assertTrue(x1.contains("multistatus")); assertTrue(x1.contains("/docs/")); assertTrue(x1.contains("/media/"))
        assertTrue(x1.contains("<D:collection/>")); assertTrue(x1.contains("pic.png"))
        assertFalse(x1.contains(".secret")); assertFalse(x1.contains(".staging"))
        assertFalse(x1.contains("readme.txt")); assertFalse(x1.contains("clip.bin"))
        val xm = String(dav("PROPFIND", "/media/", mapOf("Depth" to "1")).body)
        assertTrue(xm.contains("clip.bin")); assertTrue(xm.contains("<D:getcontentlength>${blob.size}</D:getcontentlength>"))
        assertTrue(xm.contains("<D:getcontenttype>"))
        val x0 = String(dav("PROPFIND", "/docs", mapOf("Depth" to "0")).body)
        assertTrue(x0.contains("/docs/")); assertFalse(x0.contains("readme.txt"))
        assertEquals(403, dav("PROPFIND", "/", mapOf("Depth" to "infinity")).code)
        assertEquals(404, dav("PROPFIND", "/nope").code)
        assertEquals(403, dav("PROPFIND", "/..%2f..%2fetc").code)

        assertArrayEquals(blob, dav("GET", "/media/clip.bin").body)
        val r = dav("GET", "/media/clip.bin", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, r.code); assertArrayEquals(blob.copyOfRange(100, 200), r.body)
        val dc = dav("GET", "/docs/"); assertEquals(200, dc.code); assertTrue(String(dc.body).length > 20)
        assertEquals(404, dav("HEAD", "/ghost").code)
        assertEquals(200, dav("HEAD", "/pic.png").code)
    }

    @Test fun `swift dav writable verbs`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        assertEquals(201, dav("PUT", "/new.txt", body = "v1".toByteArray()).code)
        assertEquals(204, dav("PUT", "/new.txt", body = "v2".toByteArray()).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/new.txt").body)
        assertEquals(409, dav("PUT", "/ghost/f.txt", body = byteArrayOf(1)).code)
        assertEquals(201, dav("MKCOL", "/fresh").code)
        assertEquals(405, dav("MKCOL", "/fresh").code)
        assertEquals(409, dav("MKCOL", "/ghost/sub").code)
        assertEquals(201, dav("MOVE", "/new.txt", mapOf("Destination" to "/fresh/moved.txt")).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/fresh/moved.txt").body)
        dav("PUT", "/blocker.txt", body = "b".toByteArray())
        assertEquals(412, dav("MOVE", "/fresh/moved.txt", mapOf("Destination" to "/blocker.txt", "Overwrite" to "F")).code)
        assertEquals(201, dav("COPY", "/fresh/moved.txt", mapOf("Destination" to "http://host:1/copy.txt")).code)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/copy.txt").body)
        assertArrayEquals("v2".toByteArray(), dav("GET", "/fresh/moved.txt").body)
        assertEquals(204, dav("DELETE", "/copy.txt").code)
        assertEquals(404, dav("GET", "/copy.txt").code)
        assertEquals(204, dav("DELETE", "/fresh/").code)
        assertEquals(404, dav("DELETE", "/fresh/").code)
        val lock = dav("LOCK", "/pic.png", body = ByteArray(0))
        assertEquals(200, lock.code); assertTrue(lock.headers["lock-token"]!!.contains("opaquelocktoken"))
        assertEquals(204, dav("UNLOCK", "/pic.png").code)

        // subtree guard
        assertEquals(409, dav("MOVE", "/docs", mapOf("Destination" to "/docs/inner")).code)
        assertEquals(409, dav("COPY", "/docs", mapOf("Destination" to "/docs/inner")).code)
        assertEquals(400, dav("MOVE", "/docs", mapOf("Destination" to "/docs")).code)
        assertEquals(200, dav("GET", "/docs/readme.txt").code)
    }

    @Test fun `swift dav put answers expect 100-continue`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true)
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            out.write("PUT /cont.txt HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = s.getInputStream()
            fun readUntil(term: String): String {
                val sb = StringBuilder(); val end = term.toByteArray(); var m = 0
                while (m < end.size) { val b = input.read(); if (b < 0) break; sb.append(b.toChar())
                    m = if (b == end[m].toInt()) m + 1 else if (b == end[0].toInt()) 1 else 0 }
                return sb.toString()
            }
            assertTrue(readUntil("\r\n\r\n").startsWith("HTTP/1.1 100"))
            out.write("data".toByteArray()); out.flush()
            assertTrue(readUntil("\r\n\r\n").startsWith("HTTP/1.1 201"))
        }
        assertArrayEquals("data".toByteArray(), dav("GET", "/cont.txt").body)
    }

    @Test fun `swift dav read only blocks every write verb`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = false)
        assertEquals(403, dav("PUT", "/x.txt", body = byteArrayOf(1)).code)
        assertEquals(403, dav("MKCOL", "/d").code)
        assertEquals(403, dav("DELETE", "/pic.png").code)
        assertEquals(403, dav("MOVE", "/pic.png", mapOf("Destination" to "/m.png")).code)
        assertEquals(403, dav("COPY", "/pic.png", mapOf("Destination" to "/c.png")).code)
        assertEquals(403, dav("LOCK", "/pic.png").code)
        assertEquals(207, dav("PROPFIND", "/", mapOf("Depth" to "1")).code)
        assertEquals(200, dav("GET", "/pic.png").code)
        assertTrue(File(root, "pic.png").exists())
    }

    @Test fun `swift dav digest auth gates dav when pin set`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = true, pin = "4821")
        val chal = dav("PROPFIND", "/", mapOf("Depth" to "1"))
        assertEquals(401, chal.code)
        val wa = chal.headers["www-authenticate"] ?: error("no challenge")
        assertTrue(wa.startsWith("Digest")); assertTrue(wa.contains("realm=\"crossdrop\""))
        val nonce = Regex("""nonce="([^"]+)"""").find(wa)!!.groupValues[1]
        val cnonce = "abcdef0123456789"
        val ha1 = md5("drive:crossdrop:4821")
        val ha2 = md5("PROPFIND:/")
        val resp = md5("$ha1:$nonce:00000001:$cnonce:auth:$ha2")
        val auth = "Digest username=\"drive\", realm=\"crossdrop\", uri=\"/\"," +
            " nonce=\"$nonce\", nc=00000001, cnonce=\"$cnonce\", qop=auth, response=\"$resp\""
        assertEquals(207, dav("PROPFIND", "/", mapOf("Depth" to "1", "Authorization" to auth)).code)
        val bad = md5("${md5("drive:crossdrop:9999")}:$nonce:00000001:$cnonce:auth:$ha2")
        assertEquals(401, dav("PROPFIND", "/", mapOf("Depth" to "1", "Authorization" to auth.replace(resp, bad))).code)
        // writes need credentials too — a Digest-authed MKCOL works, an unauthenticated one challenges
        assertEquals(401, dav("MKCOL", "/anon").code)
        val ha2m = md5("MKCOL:/viaDigest")
        val respM = md5("$ha1:$nonce:00000002:$cnonce:auth:$ha2m")
        val authM = "Digest username=\"drive\", realm=\"crossdrop\", uri=\"/viaDigest\"," +
            " nonce=\"$nonce\", nc=00000002, cnonce=\"$cnonce\", qop=auth, response=\"$respM\""
        assertEquals(201, dav("MKCOL", "/viaDigest", mapOf("Authorization" to authM)).code)
        assertTrue(File(root, "viaDigest").isDirectory)
        assertEquals(200, dav("GET", "/pic.png", mapOf("X-Vault-Pin" to "4821")).code)
        assertEquals(207, dav("PROPFIND", "/", mapOf("Depth" to "1", "X-Vault-Pin" to "4821")).code)
    }

    @Test fun `swift binary and http share one port`() {
        assumeTrue("CROSSDROP_SWIFT_CLI not set", cli != null)
        serveSwift(rw = false)
        assertEquals(200, http("/api/info").first)
        val c = connect()
        assertTrue(c.ls("/").isNotEmpty())
        assertEquals(200, http("/").first)
        val got = ByteArrayOutputStream()
        c.pull("/pic.png", out = got)
        assertEquals(2048, got.size())
    }
}
