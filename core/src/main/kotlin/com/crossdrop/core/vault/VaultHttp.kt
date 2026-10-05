package com.crossdrop.core.vault

import com.crossdrop.core.vault.VaultFs.VaultError
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
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
 * When the vault runs `auth=pin`, everything except `/`, `/api/info` and
 * OPTIONS requires `X-Vault-Pin: <pin>` or `?pin=`; misses get a JSON 401 the
 * page turns into its PIN prompt. Every request is one-shot
 * (`Connection: close`) — browsers parallelize across sockets fine.
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
            val (method, rawTarget) = head.first
            val headers = head.second

            val q = rawTarget.indexOf('?')
            val rawPath = (if (q >= 0) rawTarget.substring(0, q) else rawTarget)
            val query = if (q >= 0) rawTarget.substring(q + 1) else ""
            val params = parseQuery(query)

            when {
                method == "OPTIONS" -> respond(out, 204)
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
                    handleUpload(out, input, headers, params["path"] ?: "/")
                }
                else -> throw VaultError(404, "not found")
            }
        } catch (e: VaultError) {
            runCatching { respondJson(out, e.code, errorJson(e.code, e.message ?: "error")) }
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
        if (sent != p) throw VaultError(401, "pin required")
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
        val f = fs.resolve(path)
        if (!f.isFile) throw VaultError(404, "not found")
        val size = f.length()
        var start = 0L
        var end = size - 1
        var status = 200
        if (rangeHeader != null) {
            val r = parseRange(rangeHeader, size)
                ?: throw VaultError(416, "unsatisfiable range")
            start = r.first; end = r.second; status = 206
        }
        val len = if (size == 0L) 0 else end - start + 1
        val mime = fs.mimeOf(f.name)
        val headers = buildList {
            add("Accept-Ranges" to "bytes")
            add("Content-Type" to mime)
            add("Content-Length" to len.toString())
            add("Cache-Control" to "no-store")
            add("Content-Disposition" to
                "${if (download) "attachment" else "inline"}; filename*=UTF-8''${urlEncode(f.name)}")
            if (status == 206) add("Content-Range" to "bytes $start-$end/$size")
        }
        respond(out, status, headers, contentLength = len)
        if (headOnly || len == 0L) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
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

        fs.resolve(dirPath).let {                       // target dir must exist & be a dir
            if (!it.isDirectory) throw VaultError(400, "bad target dir")
        }

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
        val lines = bytes.toString(Charsets.ISO_8859_1).split("\r\n")
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
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"
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
        return bytes.toString(Charsets.UTF_8)
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
        private const val MAX_HEAD = 16 * 1024
        private const val PUSHBACK = 128 * 1024
        private const val MAX_FILES_PER_UPLOAD = 256
        /** HTTP staging ids live in their own range — no tid collisions. */
        private const val HTTP_STAGE_BASE = 1_000_000_000L

        private val FALLBACK_PAGE = """
            <!doctype html><meta charset=utf-8><title>CrossDrop Vault</title>
            <body style="background:#0b1226;color:#dfe;font-family:sans-serif;display:grid;place-items:center;height:100vh">
            <p>vault.html resource missing — rebuild the app</p></body>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
