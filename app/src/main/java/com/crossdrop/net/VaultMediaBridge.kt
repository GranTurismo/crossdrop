package com.crossdrop.net

import android.util.Log
import com.crossdrop.core.vault.VaultClient
import java.io.Closeable
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Loopback HTTP → vault bridge: binds `127.0.0.1:0` and translates
 * `GET /raw?path=…` (with `Range:`) into `VaultClient.pull` calls, so media
 * players (ExoPlayer/VideoView/browser) can stream+seek vault files straight
 * over the encrypted session without downloading them first.
 *
 * The client serializes operations internally ([VaultClient.opLock]), so
 * parallel player connections just queue — perfectly fine for playback.
 */
class VaultMediaBridge(private val client: VaultClient) : Closeable {

    @Volatile var port = 0
        private set
    private var listener: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "vault-media").apply { isDaemon = true }
    }

    fun start() {
        if (listener != null) return
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        listener = ss
        port = ss.localPort
        thread(name = "vault-media-accept", isDaemon = true) {
            while (!ss.isClosed) {
                runCatching { ss.accept() }.getOrNull()?.let { pool.execute { serve(it) } }
                    ?: break
            }
        }
    }

    /** Loopback URL for streaming vault-relative [path] (optionally `?dl=1`). */
    fun urlFor(path: String): String =
        "http://127.0.0.1:$port/raw?path=${encPath(path)}"

    private fun encPath(p: String) =
        java.net.URLEncoder.encode(p, "UTF-8").replace("+", "%20")

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val input = PushbackInputStream(socket.getInputStream(), 8)
            val out = socket.getOutputStream()
            val head = readHead(input) ?: return
            val target = head.first.substringBefore(' ').drop(1)
                .substringAfter("GET ", "")
            // minimal parse: "METHOD SP target SP ver"
            val parts = head.first.split(' ')
            val uri = parts.getOrNull(1) ?: return
            val query = uri.substringAfter('?', "")
            val path = query.substringAfter("path=", "")
                .substringBefore('&').let(::urlDecode)
            if (path.isEmpty() || !uri.startsWith("/raw")) {
                respond(out, 404); return
            }
            val attr = client.stat(path)
            val size = attr.size
            val range = head.second["range"]
            var start = 0L; var end = size - 1; var status = 200
            if (range != null && range.startsWith("bytes=")) {
                val r = parseRange(range.removePrefix("bytes="), size)
                if (r == null) { respond(out, 416, "Content-Range" to "bytes */$size"); return }
                start = r.first; end = r.second; status = 206
            }
            val len = if (size == 0L) 0 else end - start + 1
            val hdrs = buildString {
                append("HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\n")
                append("Content-Type: ${attr.mime ?: "application/octet-stream"}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $len\r\n")
                if (status == 206) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(hdrs.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            if (len > 0) {
                client.pull(path, offset = start, length = len, out = out)
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "bridge serve failed", e)
            runCatching { respond(socket.getOutputStream(), 500) }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseRange(spec: String, size: Long): Pair<Long, Long>? {
        if (size == 0L || ',' in spec) return null
        val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
        val a = spec.substring(0, dash).trim(); val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                (size - n).coerceAtLeast(0) to size - 1
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

    private fun readHead(input: InputStream): Pair<String, Map<String, String>>? {
        val bytes = java.io.ByteArrayOutputStream(512)
        var matched = 0
        val end = "\r\n\r\n".toByteArray()
        while (bytes.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else return null
            bytes.write(b)
            matched = if (b == end[matched].toInt()) matched + 1
                else if (b == end[0].toInt()) 1 else 0
            if (matched == 4) break
        }
        val lines = String(bytes.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] =
                line.substring(c + 1).trim()
        }
        return lines[0] to headers
    }

    private fun respond(out: java.io.OutputStream, status: Int,
                        vararg extra: Pair<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status ${status.toHttp()}\r\n")
        extra.forEach { sb.append(it.first).append(": ").append(it.second).append("\r\n") }
        sb.append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun Int.toHttp() = when (this) {
        404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "Error"
    }

    private fun urlDecode(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                s.substring(i + 1, i + 3).toIntOrNull(16)
                    ?.let { bytes.write(it); i += 3; return@let }
                    ?: bytes.write('%'.code).also { i++ }
            } else { bytes.write(c.code and 0xFF); i++ }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    override fun close() {
        runCatching { listener?.close() }
        listener = null
        pool.shutdownNow()
    }

    private companion object { val TAG = "VaultMediaBridge" }
}
