package com.crossdrop.core.share

import com.crossdrop.core.transfer.OutgoingFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Zero-dependency micro HTTP server: serves a self-contained web client plus
 * the shared files to any browser on the LAN — the "no app required" path.
 *
 * All routes live under a random capability token (`http://ip:port/<token>/`),
 * so only whoever scanned the QR knows the address. Nothing is served without
 * the token (404, not 403 — the surface is invisible).
 *
 *   GET /<token>/               single-file HTML client
 *   GET /<token>/manifest.json  {device, files[], zip}
 *   GET /<token>/status.json    live download counters (the page polls this)
 *   GET /<token>/f/<i>          file i (GET or HEAD, Content-Disposition)
 *   GET /<token>/all.zip        all files as an uncompressed ZIP
 */
class HttpShareServer(
    private val files: List<OutgoingFile>,
    private val deviceName: String,
    private val token: String,
    private val onEvent: (Event) -> Unit = {},
) : Closeable {

    enum class Event { CLIENT_CONNECTED, DOWNLOAD_STARTED, DOWNLOAD_DONE, DOWNLOAD_ABORTED }

    data class Stats(val downloadsStarted: Int, val downloadsDone: Int, val bytesServed: Long)

    var port: Int = 0; private set

    private val started = AtomicInteger()
    private val done = AtomicInteger()
    private val servedBytes = AtomicLong()

    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "crossdrop-share").apply { isDaemon = true }
    }
    @Volatile private var running = false

    fun stats() = Stats(started.get(), done.get(), servedBytes.get())

    fun start() {
        if (listener != null) return
        val server = ServerSocket(0)
        listener = server
        port = server.localPort
        running = true
        acceptThread = Thread({
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(socket) } }
            }
        }, "crossdrop-share-accept").apply { isDaemon = true; start() }
    }

    override fun close() {
        running = false
        runCatching { listener?.close() }
        listener = null
        pool.shutdown()
    }

    // ------------------------------------------------------------ requests --

    private fun handle(socket: Socket) {
        socket.soTimeout = IO_TIMEOUT_MS
        socket.tcpNoDelay = true
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val head = readHead(input) ?: return
            val parts = head.split("\r\n")
            val req = parts[0].split(" ")
            if (req.size < 2) return
            val method = req[0].uppercase()
            val rawPath = req[1].substringBefore('?')
            val path = urlDecode(rawPath)
            if (method != "GET" && method != "HEAD") {
                respond(output, 405, "Method Not Allowed", "text/plain", ByteArray(0), headOnly = true)
                return
            }
            route(path, method == "HEAD", output)
        }
    }

    private fun route(path: String, headOnly: Boolean, output: OutputStream) {
        when {
            path == "/$token" -> {
                // normalize to trailing-slash so relative links resolve under the token
                respond(output, 301, "Moved", "text/plain", ByteArray(0), headOnly,
                    extra = mapOf("Location" to "/$token/"))
            }
            path == "/$token/" || path == "/$token/index.html" -> {
                onEvent(Event.CLIENT_CONNECTED)
                respond(output, 200, "OK", "text/html; charset=utf-8", page(), headOnly)
            }
            path == "/$token/manifest.json" ->
                respond(output, 200, "OK", "application/json", manifest().toByteArray(Charsets.UTF_8), headOnly)
            path == "/$token/status.json" -> {
                val s = stats()
                val json = """{"started":${s.downloadsStarted},"done":${s.downloadsDone},"bytes":${s.bytesServed}}"""
                respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8), headOnly)
            }
            path.startsWith("/$token/f/") -> {
                val i = path.removePrefix("/$token/f/").toIntOrNull() ?: -1
                if (i !in files.indices) { notFound(output, headOnly); return }
                sendFile(files[i], headOnly, output)
            }
            path == "/$token/all.zip" -> sendZip(headOnly, output)
            else -> notFound(output, headOnly)
        }
    }

    private fun manifest(): String {
        val zip = files.size > 1 && files.all { it.meta.size in 0 until ZIP64_LIMIT }
        return Json.encodeToString(
            ShareManifest(
                device = deviceName,
                files = files.mapIndexed { i, f ->
                    ShareFileEntry(i, f.meta.name, f.meta.size, f.meta.mime)
                },
                zip = zip,
            )
        )
    }

    // ------------------------------------------------------------- responses --

    private fun notFound(output: OutputStream, headOnly: Boolean) =
        respond(output, 404, "Not Found", "text/plain", "not found".toByteArray(), headOnly)

    private fun respond(
        output: OutputStream, code: Int, reason: String, contentType: String,
        body: ByteArray, headOnly: Boolean, extra: Map<String, String> = emptyMap(),
    ) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun sendFile(file: OutgoingFile, headOnly: Boolean, output: OutputStream) {
        val meta = file.meta
        // CR/LF in a filename or MIME would split response headers.
        val safeName = meta.name.replace(Regex("[\r\n\\\\\"]"), "_")
        val safeMime = meta.mime?.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+*/-]+")) }
            ?: "application/octet-stream"
        val utf8Name = rfc5987(meta.name)
        val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(safeMime).append("\r\n")
        sb.append("Content-Length: ").append(meta.size).append("\r\n")
        sb.append("Content-Disposition: attachment; filename=\"").append(safeName)
            .append("\"; filename*=UTF-8''").append(utf8Name).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (headOnly) { output.flush(); return }

        onEvent(Event.DOWNLOAD_STARTED)
        started.incrementAndGet()
        val ok = runCatching {
            file.open().use { pump(it, output, servedBytes) }
            output.flush()
        }.isSuccess
        if (ok) { done.incrementAndGet(); onEvent(Event.DOWNLOAD_DONE) }
        else onEvent(Event.DOWNLOAD_ABORTED)
    }

    /** All files as an uncompressed (STORED) zip — one tap for multi-file shares. */
    private fun sendZip(headOnly: Boolean, output: OutputStream) {
        if (files.isEmpty() || files.any { it.meta.size < 0 || it.meta.size >= ZIP64_LIMIT }) {
            respond(output, 413, "Too Large", "text/plain", "zip unavailable".toByteArray(), headOnly)
            return
        }
        if (headOnly) {
            respond(output, 200, "OK", "application/zip", ByteArray(0), headOnly = true)
            return
        }
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write("Content-Disposition: attachment; filename=\"crossdrop.zip\"\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))

        onEvent(Event.DOWNLOAD_STARTED)
        started.incrementAndGet()
        val ok = runCatching {
            val entries = dedupeNames(files.map { it.meta.name })
            // STORED with real header values (no data descriptors) — the most
            // compatible zip subset: java.util.zip, OS archivers, mobile
            // files apps all read it. Cost is one extra pass per file.
            val sums = files.map { crcOf(it) }
            val central = ByteArrayOutputStream()
            var offset = 0L
            for ((i, file) in files.withIndex()) {
                val name = entries[i].toByteArray(Charsets.UTF_8)
                val (crc, size) = sums[i]
                val local = ByteArrayOutputStream(30 + name.size)
                le32(local, 0x04034B50); le16(local, 20); le16(local, 0x0800) // UTF-8 names
                le16(local, 0); le16(local, dosTime()); le16(local, dosDate())
                le32(local, crc.toInt()); le32(local, size.toInt()); le32(local, size.toInt())
                le16(local, name.size); le16(local, 0); local.write(name)
                output.write(local.toByteArray())

                val written = pump(file.open(), output, servedBytes)

                le32(central, 0x02014B50); le16(central, 20); le16(central, 20)
                le16(central, 0x0800); le16(central, 0); le16(central, dosTime()); le16(central, dosDate())
                le32(central, crc.toInt()); le32(central, written.toInt()); le32(central, written.toInt())
                le16(central, name.size); le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0)
                le32(central, 0); le32(central, offset.toInt()); central.write(name)
                offset += local.size() + written
            }
            val cd = central.toByteArray()
            val eocd = ByteArrayOutputStream(22)
            le32(eocd, 0x06054B50); le16(eocd, 0); le16(eocd, 0)
            le16(eocd, files.size); le16(eocd, files.size)
            le32(eocd, cd.size); le32(eocd, offset.toInt()); le16(eocd, 0)
            output.write(cd); output.write(eocd.toByteArray()); output.flush()
        }.isSuccess
        if (ok) { done.incrementAndGet(); onEvent(Event.DOWNLOAD_DONE) }
        else onEvent(Event.DOWNLOAD_ABORTED)
    }

    // ----------------------------------------------------------------- util --

    /** Read up to blank line, capped — returns the raw header block or null. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var b: Int
        var tail = 0
        while (buf.size() < MAX_HEAD) {
            b = input.read()
            if (b < 0) throw EOFException("client closed")
            buf.write(b)
            tail = if (b == '\r'.code || b == '\n'.code) tail + 1 else 0
            if (tail == 4) break
            if (b == '\n'.code && buf.size() >= 2 &&
                buf.toByteArray()[buf.size() - 2] == '\n'.code.toByte()) break
        }
        return buf.toString(Charsets.ISO_8859_1).takeIf { it.isNotBlank() }
    }

    private fun urlDecode(s: String): String {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                // Tolerate malformed escapes — keep the '%' literally.
                runCatching { out.write(s.substring(i + 1, i + 3).toInt(16)) }
                    .onSuccess { i += 3 }
                    .onFailure { out.write(c.code); i++ }
            } else { out.write(c.code); i++ }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun rfc5987(name: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&+-.^_`|~"
        val sb = StringBuilder()
        name.toByteArray(Charsets.UTF_8).forEach { b ->
            val ch = b.toInt().toChar()
            if (ch in keep) sb.append(ch) else sb.append('%').append("%02X".format(b))
        }
        return sb.toString()
    }

    private fun dedupeNames(names: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return names.map { raw ->
            val clean = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
            var n = clean
            var i = 1
            while (!seen.add(n)) {
                n = "${clean.substringBeforeLast('.')} ($i).${clean.substringAfterLast('.', "")}"
                i++
            }
            n
        }
    }

    private fun page(): ByteArray =
        (HttpShareServer::class.java.getResourceAsStream("/share.html")
            ?: error("share.html missing from classpath")).use { it.readBytes() }

    private fun pump(input: InputStream, output: OutputStream, counter: AtomicLong): Long {
        val buf = ByteArray(CHUNK); var n: Int; var total = 0L
        while (input.read(buf).also { n = it } >= 0) {
            output.write(buf, 0, n); total += n; counter.addAndGet(n.toLong())
        }
        return total
    }

    /** First pass for a zip entry: CRC-32 + true length (metadata can lie). */
    private fun crcOf(file: OutgoingFile): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        file.open().use {
            val buf = ByteArray(CHUNK); var n: Int
            while (it.read(buf).also { n = it } >= 0) { crc.update(buf, 0, n); size += n }
        }
        return crc.value to size
    }

    private fun le16(out: OutputStream, v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    private fun le32(out: OutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
    private fun dosTime(): Int { val c = java.util.Calendar.getInstance(); return (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or (c.get(java.util.Calendar.MINUTE) shl 5) or (c.get(java.util.Calendar.SECOND) / 2) }
    private fun dosDate(): Int { val c = java.util.Calendar.getInstance(); return ((c.get(java.util.Calendar.YEAR) - 1980) shl 9) or ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or c.get(java.util.Calendar.DAY_OF_MONTH) }

    @Serializable
    private data class ShareManifest(val device: String, val files: List<ShareFileEntry>, val zip: Boolean)

    @Serializable
    private data class ShareFileEntry(val i: Int, val name: String, val size: Long, val mime: String?)

    private companion object {
        const val MAX_HEAD = 16 * 1024
        const val CHUNK = 64 * 1024
        const val IO_TIMEOUT_MS = 120_000
        const val ZIP64_LIMIT = 0xFFFF_FFFFL
    }
}
