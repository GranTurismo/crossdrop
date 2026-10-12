package com.crossdrop.core.vault

import com.crossdrop.core.vault.VaultFs.VaultError
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Zero-install HTTP/1.1 surface for the vault — same socket as the AEAD
 * protocol, routed here when the first byte looks like an HTTP verb.
 *
 * Endpoints:
 *  - `GET /`            embedded vault.html explorer (PIN-free; the page asks)
 *  - `GET /api/info`    `{name, writable, pin}` header data for the page
 *  - `GET /api/fs?path=`      directory listing JSON
 *  - `GET /api/stat?path=`    single-entry JSON
 *  - `GET|HEAD /raw/<path>`   file stream, single-range `Range:` → 206
 *  - `POST /upload?path=`     multipart/form-data → staging → atomic commit
 *
 * WebDAV surface (mounted at `/`, so Finder/Explorer mount `http://ip:port/`):
 *  - `OPTIONS`                       DAV capability advertisement
 *  - `PROPFIND <path>`  Depth 0/1    multistatus XML listing
 *  - `GET|HEAD /<path>`              direct file access (Range → 206)
 *  - `PUT / MKCOL / DELETE / MOVE / COPY`   writable vaults only
 *  - `LOCK / UNLOCK`                 stubs — Finder wants them for RW mounts
 *
 * When the vault runs `auth=pin`: the JSON API + browser paths accept
 * `X-Vault-Pin`/`?pin=` (JSON 401 → page PIN prompt), while DAV methods and
 * direct GETs additionally accept HTTP Digest auth
 * (`WWW-Authenticate: Digest realm=crossdrop`) — that is what Finder,
 * Explorer and browsers prompt for as "name: drive / 4-digit passcode".
 * Every request is one-shot (`Connection: close`).
 */
internal class VaultHttp(
    private val fs: VaultFs,
    private val writable: Boolean,
    private val pin: String?,
    private val deviceName: () -> String,
    private val onBytesServed: (Long) -> Unit = {},
) {
    private val page: ByteArray by lazy {
        javaClass.getResourceAsStream("/vault.html")?.use { it.readBytes() }
            ?: FALLBACK_PAGE
    }

    fun handle(socket: Socket, input: InputStream) {
        val out = socket.getOutputStream()
        try {
            val head = readHead(input) ?: return
            peerIp = (socket.remoteSocketAddress as? java.net.InetSocketAddress)
                ?.address?.hostAddress ?: "?"
            val (method, rawTarget) = head.first
            val headers = head.second

            val q = rawTarget.indexOf('?')
            val rawPath = (if (q >= 0) rawTarget.substring(0, q) else rawTarget)
            val query = if (q >= 0) rawTarget.substring(q + 1) else ""
            val params = parseQuery(query)

            when {
                method == "OPTIONS" -> respondOptions(out)
                method == "GET" && (rawPath == "/" || rawPath == "/index.html") ->
                    respondPage(out)
                method == "GET" && rawPath == "/api/info" ->
                    respondJson(out, 200, infoJson())
                method == "GET" && rawPath == "/api/fs" -> {
                    requirePin(headers, params)
                    respondJson(out, 200, fsJson(params["path"] ?: "/"))
                }
                method == "GET" && rawPath == "/api/stat" -> {
                    requirePin(headers, params)
                    respondJson(out, 200, attrJson(params["path"] ?: "/"))
                }
                (method == "GET" || method == "HEAD") && rawPath.startsWith("/raw/") -> {
                    requirePin(headers, params)
                    serveRaw(out, urlDecode(rawPath.removePrefix("/raw/")),
                        headers["range"], headOnly = method == "HEAD",
                        download = params.containsKey("dl"))
                }
                method == "POST" && rawPath == "/upload" -> {
                    requirePin(headers, params)
                    if (!writable) throw VaultError(403, "vault is read-only")
                    continue100(out, headers)
                    handleUpload(out, input, headers, params["path"] ?: "/")
                }

                // --------------------------------------------------- WebDAV --
                method == "PROPFIND" -> {
                    if (!authorized(headers, params, method)) { authChallenge(out); return }
                    drainBody(input, headers)
                    handlePropfind(out, urlDecode(rawPath), headers["depth"])
                }
                method == "GET" || method == "HEAD" -> {
                    // DAV path: any non-/api GET — file → bytes, dir → explorer
                    if (!authorized(headers, params, method)) { authChallenge(out); return }
                    serveDavPath(out, urlDecode(rawPath), headers["range"],
                        headOnly = method == "HEAD")
                }
                method == "PUT" -> {
                    if (!writable) throw VaultError(403, "vault is read-only")
                    if (!authorized(headers, params, method)) { authChallenge(out); return }
                    continue100(out, headers)
                    handlePut(out, input, urlDecode(rawPath), headers)
                }
                method == "MKCOL" -> {
                    requireWritable(headers, params, method)
                    fs.mkdir(urlDecode(rawPath))
                    respond(out, 201)
                }
                method == "DELETE" -> {
                    requireWritable(headers, params, method)
                    fs.delete(urlDecode(rawPath))
                    respond(out, 204)
                }
                method == "MOVE" || method == "COPY" -> {
                    requireWritable(headers, params, method)
                    val dst = headers["destination"]?.let(::destPath)
                        ?: throw VaultError(400, "missing Destination")
                    val overwrite = headers["overwrite"]?.uppercase() != "F"
                    val src = urlDecode(rawPath)
                    val existed = runCatching { fs.stat(dst) }.isSuccess
                    if (method == "MOVE") fs.move(src, dst, overwrite)
                    else fs.copy(src, dst, overwrite)
                    respond(out, if (existed) 204 else 201)
                }
                method == "LOCK" -> {
                    requireWritable(headers, params, method)
                    drainBody(input, headers)
                    respondLock(out)
                }
                method == "UNLOCK" -> {
                    requireWritable(headers, params, method)
                    respond(out, 204)
                }
                else -> throw VaultError(404, "not found")
            }
        } catch (e: VaultError) {
            runCatching { respondJson(out, e.code, errorJson(e.code, e.message ?: "error")) }
        } catch (_: AuthRequired) {
            runCatching { authChallenge(out) }
        } catch (e: BadRequest) {
            runCatching { respondJson(out, 400, errorJson(400, e.message ?: "bad request")) }
        } catch (_: Exception) {
            runCatching { respondJson(out, 500, errorJson(500, "internal error")) }
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------ routing --

    private fun requirePin(headers: Map<String, String>, params: Map<String, String>) {
        val p = pin ?: return
        val sent = headers["x-vault-pin"] ?: params["pin"]
        if (sent == null || !pinMatches(sent, p)) throw VaultError(401, "pin required")
    }

    // ----------------------------------------------- brute-force throttle --

    /** Remote IP of the request being handled (one thread per socket). */
    private val peerIpLocal = ThreadLocal<String>()
    private var peerIp: String
        get() = peerIpLocal.get() ?: "?"
        set(v) = peerIpLocal.set(v)

    private class Strikes(var count: Int = 0, var lockedUntil: Long = 0)
    private val strikes = java.util.concurrent.ConcurrentHashMap<String, Strikes>()

    /** Records a credential check: lockout after repeated misses from one IP. */
    private fun recordAttempt(ok: Boolean): Boolean {
        val st = strikes.getOrPut(peerIp) { Strikes() }
        synchronized(st) {
            val now = System.currentTimeMillis()
            if (now < st.lockedUntil) throw VaultError(429, "too many attempts — wait a minute")
            if (ok) { st.count = 0; return true }
            if (++st.count >= MAX_PIN_STRIKES) {
                st.count = 0; st.lockedUntil = now + PIN_LOCKOUT_MS
            }
            return false
        }
    }

    private fun pinMatches(sent: String, p: String): Boolean =
        recordAttempt(java.security.MessageDigest.isEqual(
            sent.toByteArray(Charsets.UTF_8), p.toByteArray(Charsets.UTF_8)))

    // --------------------------------------------------------------- auth --

    /** Per-boot nonce for Digest challenges — LAN-scoped, never persisted. */
    private val davNonce = run {
        val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

    /**
     * DAV-capable auth: pass on `X-Vault-Pin`/`?pin=` (browser page) OR a
     * valid HTTP Digest credential (Finder/Explorer/browsers' native dialog).
     * Open vaults always pass.
     */
    private fun authorized(headers: Map<String, String>, params: Map<String, String>,
                           method: String): Boolean {
        val p = pin ?: return true
        val sent = headers["x-vault-pin"] ?: params["pin"]
        if (sent != null) return pinMatches(sent, p)
        return checkDigest(headers["authorization"], method, p)
    }

    /** Write-verb gate: read-only 403s before auth, then Digest/pin applies. */
    private fun requireWritable(headers: Map<String, String>, params: Map<String, String>,
                                method: String) {
        if (!writable) throw VaultError(403, "vault is read-only")
        if (!authorized(headers, params, method)) throw AuthRequired()
    }

    /** Marker → catch-site writes the Digest 401 (needs headers, unlike errors). */
    private class AuthRequired : Exception()

    private fun checkDigest(auth: String?, method: String, p: String): Boolean {
        if (auth == null || !auth.startsWith("Digest ")) return false
        val kv = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|[^,\s]+)""")
            .findAll(auth.substring(7)).associate { m ->
                m.groupValues[1].lowercase() to
                    m.groupValues[2].ifEmpty { m.groupValues[0].substringAfter('=').trim().removeSurrounding("\"") }
            }
        val user = kv["username"] ?: return false
        val uri = kv["uri"] ?: return false
        if (kv["nonce"] != davNonce) return false
        val response = kv["response"] ?: return false
        val ha1 = md5Hex("$user:$DAV_REALM:$p")
        val ha2 = md5Hex("$method:$uri")
        val expected = if (kv["qop"] != null) {
            md5Hex("$ha1:$davNonce:${kv["nc"]}:${kv["cnonce"]}:${kv["qop"]}:$ha2")
        } else md5Hex("$ha1:$davNonce:$ha2")
        return recordAttempt(java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.ISO_8859_1),
            response.lowercase().toByteArray(Charsets.ISO_8859_1)))
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

    private fun authChallenge(out: OutputStream) {
        respond(out, 401, listOf(
            "WWW-Authenticate" to
                "Digest realm=\"$DAV_REALM\", nonce=\"$davNonce\", qop=\"auth\", algorithm=MD5",
            "Content-Type" to "text/plain; charset=utf-8",
        ), contentLength = 0)
    }

    // ------------------------------------------------------------- WebDAV --

    private fun respondOptions(out: OutputStream) {
        respond(out, 204, listOf(
            "DAV" to "1,2",
            "Allow" to "OPTIONS,GET,HEAD,POST,PROPFIND,PUT,DELETE,MKCOL,COPY,MOVE,LOCK,UNLOCK",
            "MS-Author-Via" to "DAV",
        ), contentLength = 0)
    }

    /** `GET /some/dir` → the explorer page; `GET /file` → bytes. */
    private fun serveDavPath(out: OutputStream, path: String, rangeHeader: String?,
                             headOnly: Boolean) {
        val e = try { fs.stat(path) } catch (err: VaultError) {
            if (err.code == 404 && headOnly) { respond(out, 404, contentLength = 0); return }
            throw err
        }
        if (e.isDir) {
            if (headOnly) { respond(out, 200, listOf("Content-Type" to "text/html"),
                contentLength = 0); return }
            respondPage(out)
        } else serveRaw(out, path, rangeHeader, headOnly, download = false)
    }

    private fun handlePropfind(out: OutputStream, path: String, depth: String?) {
        if (depth == "infinity") throw VaultError(403, "depth infinity not supported")
        val e = fs.stat(path)
        val sb = StringBuilder("""<?xml version="1.0" encoding="utf-8"?>""")
            .append("<D:multistatus xmlns:D=\"DAV:\">")
        appendResponse(sb, davHref(path, e.isDir), e)
        if (e.isDir && depth != "0") {
            val parent = davHref(path, true)
            for (child in fs.list(path)) {
                appendResponse(sb, parent + urlEncode(child.name) +
                    if (child.isDir) "/" else "", child)
            }
        }
        sb.append("</D:multistatus>")
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        respond(out, 207, listOf(
            "Content-Type" to "application/xml; charset=utf-8",
            "Content-Length" to body.size.toString(),
        ))
        out.write(body); out.flush()
    }

    /** href form of a jail path: percent-encoded per segment, dirs trailing /. */
    private fun davHref(path: String, isDir: Boolean): String {
        val clean = fs.sanitize(path)
        if (clean.isEmpty()) return "/"
        val href = "/" + clean.split('/').joinToString("/") { urlEncode(it) }
        return if (isDir) "$href/" else href
    }

    private fun appendResponse(sb: StringBuilder, href: String, e: VaultFs.Entry) {
        sb.append("<D:response><D:href>").append(xml(href)).append("</D:href>")
            .append("<D:propstat><D:prop>")
            .append("<D:displayname>").append(xml(e.name)).append("</D:displayname>")
            .append(if (e.isDir) "<D:resourcetype><D:collection/></D:resourcetype>"
                else "<D:resourcetype/>")
        if (!e.isDir) {
            sb.append("<D:getcontentlength>").append(e.size).append("</D:getcontentlength>")
            if (e.mime != null) sb.append("<D:getcontenttype>")
                .append(xml(e.mime)).append("</D:getcontenttype>")
        }
        sb.append("<D:getlastmodified>").append(httpDate(e.mtime * 1000))
            .append("</D:getlastmodified>")
            .append("<D:getetag>\"").append(e.mtime).append('-').append(e.size).append("\"</D:getetag>")
            .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
            .append("</D:response>")
    }

    /** Direct PUT: body → file, replacing. 201 when created, 204 when replaced. */
    private fun handlePut(out: OutputStream, input: InputStream, path: String,
                          headers: Map<String, String>) {
        val length = headers["content-length"]?.toLongOrNull()
            ?: throw VaultError(411, "content-length required")
        val existed = runCatching { fs.stat(path) }.isSuccess
        fs.putStream(path, input, length)
        respond(out, if (existed) 204 else 201)
    }

    /** Finder requires lock tokens to mount read-write; the lock is a stub. */
    private fun respondLock(out: OutputStream) {
        val token = "opaquelocktoken:crossdrop-" +
            java.util.UUID.randomUUID().toString()
        val body = ("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype>" +
            "<D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth>" +
            "<D:timeout>Second-3600</D:timeout>" +
            "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "</D:activelock></D:lockdiscovery></D:prop>").toByteArray(Charsets.UTF_8)
        respond(out, 200, listOf(
            "Lock-Token" to "<$token>",
            "Content-Type" to "application/xml; charset=utf-8",
            "Content-Length" to body.size.toString(),
        ))
        out.write(body); out.flush()
    }

    /** `Destination:` may be an absolute URL or a bare path — take the path part. */
    private fun destPath(h: String): String {
        var p = h.trim()
        val scheme = p.indexOf("://")
        if (scheme >= 0) {
            val slash = p.indexOf('/', scheme + 3)
            p = if (slash >= 0) p.substring(slash) else "/"
        }
        return urlDecode(p.substringBefore('?'))
    }

    /** Finder/Explorer PUTs carry `Expect: 100-continue` — answer it so the body flows. */
    private fun continue100(out: OutputStream, headers: Map<String, String>) {
        if (headers["expect"]?.equals("100-continue", true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /** Discard a bounded request body (PROPFIND/LOCK payloads are ignored). */
    private fun drainBody(input: InputStream, headers: Map<String, String>) {
        var left = headers["content-length"]?.toLongOrNull()?.coerceAtMost(256 * 1024) ?: 0
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            left -= n
        }
    }

    private fun httpDate(ms: Long): String {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return f.format(java.util.Date(ms))
    }

    private fun xml(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    private fun infoJson(): String = buildString {
        append("{\"name\":").append(jsonStr(deviceName()))
        append(",\"writable\":").append(writable)
        append(",\"pin\":").append(pin != null).append('}')
    }

    private fun fsJson(path: String): String = buildString {
        val entries = fs.list(path)
        append("{\"path\":").append(jsonStr("/" + fs.sanitize(path)))
        append(",\"writable\":").append(writable)
        append(",\"entries\":[")
        entries.forEachIndexed { i, e -> if (i > 0) append(','); appendEntry(e) }
        append("]}")
    }

    private fun attrJson(path: String): String = buildString {
        val e = fs.stat(path)
        append('{'); appendEntry(e); append('}')
    }

    private fun StringBuilder.appendEntry(e: VaultFs.Entry) {
        append("{\"name\":").append(jsonStr(e.name))
        append(",\"size\":").append(e.size)
        append(",\"isDir\":").append(e.isDir)
        append(",\"mtime\":").append(e.mtime)
        if (e.mime != null) append(",\"mime\":").append(jsonStr(e.mime))
        append('}')
    }

    // -------------------------------------------------------------- /raw --

    private fun serveRaw(out: OutputStream, path: String, rangeHeader: String?,
                         headOnly: Boolean, download: Boolean) {
        val e = fs.stat(path)
        if (e.isDir) throw VaultError(404, "not found")
        val size = e.size
        var start = 0L
        var end = size - 1
        var status = 200
        if (rangeHeader != null) {
            val r = parseRange(rangeHeader, size)
                ?: throw VaultError(416, "unsatisfiable range")
            start = r.first; end = r.second; status = 206
        }
        val len = if (size == 0L) 0 else end - start + 1
        val mime = e.mime ?: fs.mimeOf(path.substringAfterLast('/'))
        val name = path.substringAfterLast('/')
        val headers = buildList {
            add("Accept-Ranges" to "bytes")
            add("Content-Type" to mime)
            add("Content-Length" to len.toString())
            add("Cache-Control" to "no-store")
            add("Content-Disposition" to
                "${if (download) "attachment" else "inline"}; filename*=UTF-8''${urlEncode(name)}")
            if (status == 206) add("Content-Range" to "bytes $start-$end/$size")
        }
        respond(out, status, headers, contentLength = len)
        if (headOnly || len == 0L) return
        fs.open(path, start).use { src ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
            out.flush()
        }
        onBytesServed(len)
    }

    /** `bytes=a-b`, `bytes=a-`, `bytes=-n` — single range only. */
    private fun parseRange(h: String, size: Long): Pair<Long, Long>? {
        val spec = h.trim().removePrefix("bytes=").takeIf { h.trim().startsWith("bytes=") }
            ?: return null
        if (',' in spec) return null                      // no multi-range support
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (size == 0L) return null
        return when {
            a.isEmpty() -> {                              // suffix: last n bytes
                val n = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                val s = (size - n).coerceAtLeast(0)
                s to size - 1
            }
            else -> {
                val s = a.toLongOrNull() ?: return null
                if (s >= size) return null
                val e = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null)
                if (e < s) return null
                s to e.coerceAtMost(size - 1)
            }
        }
    }

    // ---------------------------------------------------------- /upload --

    private class BadRequest(msg: String) : Exception(msg)

    private fun handleUpload(out: OutputStream, input: InputStream,
                             headers: Map<String, String>, dirPath: String) {
        val ctype = headers["content-type"] ?: throw BadRequest("no content-type")
        val boundary = ctype.substringAfter("boundary=", "")
            .trim().removeSurrounding("\"")
        if (boundary.isEmpty() || boundary.length > 128) throw BadRequest("no boundary")
        val length = headers["content-length"]?.toLongOrNull()
            ?: throw BadRequest("no content-length")

        if (!fs.stat(dirPath).isDir) throw VaultError(400, "bad target dir")

        val body = BodyReader(input, length)
        val saved = mutableListOf<String>()

        // first line: "--boundary" (or "--boundary--" for an empty body)
        val first = body.readLine()
        if (first == "--$boundary--") {
            respondJson(out, 200, "{\"ok\":true,\"saved\":[]}")
            return
        }
        if (first != "--$boundary") throw BadRequest("bad multipart preamble")

        val marker = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        var partNo = 0
        while (true) {
            // --- part headers
            val ph = mutableMapOf<String, String>()
            while (true) {
                if (body.left <= 0) throw EOFException("truncated multipart")
                val line = body.readLine()
                if (line.isEmpty()) break
                val c = line.indexOf(':')
                if (c > 0) ph[line.substring(0, c).trim().lowercase()] =
                    line.substring(c + 1).trim()
            }
            val filename = ph["content-disposition"]?.let(::parseFilename)
            partNo++
            if (filename != null && saved.size >= MAX_FILES_PER_UPLOAD) {
                throw VaultError(400, "too many files")
            }
            // --- part body → staging (skipped when no filename)
            val stage = fs.stageFile(HTTP_STAGE_BASE + partNo)
            try {
                FileOutputStream(stage).use { fos ->
                    streamPart(body, marker, if (filename != null) fos else null)
                }
                if (filename != null) {
                    val rel = joinPath(dirPath, sanitizeFilename(filename))
                    saved += fs.commitStaged(stage, rel)
                }
            } finally {
                stage.delete()
            }
            // --- what follows the boundary: "--" ends, "\r\n" = next part
            if (body.readLine().startsWith("--")) break
        }

        respondJson(out, 200, buildString {
            append("{\"ok\":true,\"saved\":[")
            saved.forEachIndexed { i, s -> if (i > 0) append(','); append(jsonStr(s)) }
            append("]}")
        })
    }

    /** Content-length-bounded stream over the request body. */
    private class BodyReader(input: InputStream, length: Long) {
        val pb = PushbackInputStream(input, PUSHBACK)
        var left = length
            private set

        fun read(buf: ByteArray): Int {
            if (left <= 0) return -1
            val n = pb.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        fun unread(data: ByteArray, off: Int, len: Int) {
            pb.unread(data, off, len)
            left += len
        }

        /** One \r\n-terminated line. EOF ends the line too. */
        fun readLine(): String {
            val sb = StringBuilder()
            while (left > 0) {
                val b = pb.read()
                if (b < 0) break
                left--
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
    }

    /**
     * Scan the body for `\r\n--boundary`, writing everything before it to [out].
     * The marker and anything after it is pushed back for the next part.
     */
    private fun streamPart(body: BodyReader, marker: ByteArray, out: FileOutputStream?) {
        val buf = ByteArray(64 * 1024)
        var pending = ByteArray(0)
        while (true) {
            val n = body.read(buf)
            if (n < 0) throw EOFException("part truncated before boundary")
            val data = if (pending.isEmpty()) buf.copyOf(n) else pending + buf.copyOf(n)
            val idx = indexOf(data, marker)
            if (idx >= 0) {
                out?.write(data, 0, idx)
                body.unread(data, idx + marker.size, data.size - idx - marker.size)
                return
            }
            val keep = marker.size - 1                    // marker may straddle chunks
            val flushable = data.size - keep
            if (flushable > 0) {
                out?.write(data, 0, flushable)
                pending = data.copyOfRange(flushable, data.size)
            } else pending = data
        }
    }

    /** First index of [needle] in [hay], naive scan keyed on needle[0] ('\r'). */
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        val first = needle[0]
        var i = 0
        val limit = hay.size - needle.size
        while (i <= limit) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun parseFilename(disposition: String): String? {
        // filename*=UTF-8''x.jpg wins over filename="x.jpg"
        Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)?.let { return urlDecode(it.groupValues[1]) }
        return Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""")
            .find(disposition)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
            .replace('\u0000', ' ').trim()
        if (base.isEmpty() || base.startsWith('.')) throw VaultError(400, "bad filename")
        return base
    }

    private fun joinPath(dir: String, name: String): String =
        fs.sanitize(dir).let { if (it.isEmpty()) name else "$it/$name" }

    // ------------------------------------------------------------ plumbing --

    /** Read until CRLFCRLF (≤16 KB). Returns null on EOF before any bytes. */
    private fun readHead(input: InputStream): Pair<Pair<String, String>, Map<String, String>>? {
        val bytes = java.io.ByteArrayOutputStream(1024)
        var matched = 0
        val end = "\r\n\r\n".toByteArray()
        while (bytes.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else throw BadRequest("truncated head")
            bytes.write(b)
            matched = if (b == end[matched].toInt()) matched + 1
                else if (b == end[0].toInt()) 1 else 0
            if (matched == 4) break
        }
        if (matched < 4) throw BadRequest("head too large")
        val lines = String(bytes.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val req = lines[0].split(' ')
        if (req.size < 2) throw BadRequest("bad request line")
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] =
                line.substring(c + 1).trim()
        }
        return (req[0].uppercase() to req[1]) to headers
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotBlank() }.associate { kv ->
            val e = kv.indexOf('=')
            if (e < 0) urlDecode(kv, plusAsSpace = true) to ""
            else urlDecode(kv.substring(0, e), plusAsSpace = true) to
                urlDecode(kv.substring(e + 1), plusAsSpace = true)
        }

    // ------------------------------------------------------------ response --

    private fun respondPage(out: OutputStream) {
        respond(out, 200, listOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to page.size.toString(),
            "Cache-Control" to "no-store",
        ))
        out.write(page)
        out.flush()
    }

    private fun respondJson(out: OutputStream, status: Int, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        respond(out, status, listOf(
            "Content-Type" to "application/json",
            "Content-Length" to b.size.toString(),
            "Cache-Control" to "no-store",
        ))
        out.write(b)
        out.flush()
    }

    private fun respond(
        out: OutputStream, status: Int,
        headers: List<Pair<String, String>> = emptyList(),
        contentLength: Long = -1,
    ) {
        val sb = StringBuilder("HTTP/1.1 $status ${statusText(status)}\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(sanitizeHeader(v)).append("\r\n")
        }
        if (contentLength >= 0 && headers.none { it.first.equals("Content-Length", true) }) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
        207 -> "Multi-Status"; 301 -> "Moved Permanently"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
        411 -> "Length Required"; 412 -> "Precondition Failed"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"; else -> "?"
    }

    private fun sanitizeHeader(v: String) = v.replace("\r", "").replace("\n", "")

    // ------------------------------------------------------------ encoding --

    private fun errorJson(code: Int, msg: String) =
        "{\"code\":$code,\"message\":${jsonStr(msg)}}"

    private fun jsonStr(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"', '\\' -> append('\\').append(c)
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /** Percent-decode. [plusAsSpace] only for query params — in a path '+' is literal. */
    private fun urlDecode(s: String, plusAsSpace: Boolean = false): String {
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { bytes.write(h); i += 3; continue }
            }
            if (c == '+' && plusAsSpace) bytes.write(' '.code)
            else bytes.write(c.code and 0xFF)   // bytes are utf-8 decoded below
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun urlEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
                c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code) append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }

    companion object {
        private const val MAX_PIN_STRIKES = 5
        private const val PIN_LOCKOUT_MS = 60_000L
        private const val MAX_HEAD = 16 * 1024
        private const val PUSHBACK = 128 * 1024
        private const val MAX_FILES_PER_UPLOAD = 256
        /** HTTP staging ids live in their own range — no tid collisions. */
        private const val HTTP_STAGE_BASE = 1_000_000_000L
        /** Digest realm shown by Finder/Explorer auth prompts. */
        private const val DAV_REALM = "crossdrop"

        private val FALLBACK_PAGE = """
            <!doctype html><meta charset=utf-8><title>CrossDrop Vault</title>
            <body style="background:#0b1226;color:#dfe;font-family:sans-serif;display:grid;place-items:center;height:100vh">
            <p>vault.html resource missing — rebuild the app</p></body>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
