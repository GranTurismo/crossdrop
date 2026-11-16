package com.crossdrop.platform

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Keeps one CrossDrop per user session. The app hides to the tray when its
 * window is closed, so launching it again (Start menu, desktop shortcut) must
 * surface the running copy rather than start a second one that fights for the
 * same ports and advertises a duplicate device.
 *
 * The first instance holds an exclusive lock on `instance.lock` (released by the
 * OS if we crash) and listens on a loopback-only socket whose port is published
 * in `instance.port`. A later instance fails the lock, sends `show\n` to that
 * port — followed by `file <absolute path>` lines for files it was launched with
 * (Explorer "Send to", Open With) and an `end` line — and exits. The running copy
 * raises its window and puts those files on the radar to pick devices for; it
 * never sends anything on its own.
 *
 * Loopback is shared by every user on a machine (terminal servers), so file lines
 * are only taken when the `show` line carries the random secret written next to
 * the port in the per-user port file; network (UNC) paths are refused — even
 * checking one would authenticate to that server — and lines are length-capped.
 */
class SingleInstance(private val dir: File = AppDirs.support) {
    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * @return true when this process is the primary instance (keep running);
     * false when another is already running and has been asked to show itself.
     * If the guard itself cannot work (read-only profile, security software blocking
     * loopback sockets) this fails open and returns true: two windows beat no window.
     */
    @Synchronized
    fun acquire(files: List<File> = emptyList(), onActivate: (List<File>) -> Unit): Boolean = try {
        acquireOrSignal(files, onActivate)
    } catch (e: Exception) {
        Log.w("SingleInstance", "single-instance protection unavailable", e)
        release()
        true
    }

    private fun acquireOrSignal(files: List<File>, onActivate: (List<File>) -> Unit): Boolean {
        dir.mkdirs()
        val file = RandomAccessFile(File(dir, LOCK_FILE), "rw")
        val got = try {
            file.channel.tryLock()          // null → another process holds it
        } catch (_: OverlappingFileLockException) {
            null                            // another SingleInstance in this very JVM holds it
        } catch (e: Exception) {
            file.close()
            throw e
        }
        if (got == null) {
            file.close()
            signalRunningInstance(files)
            return false
        }
        raf = file
        lock = got

        val srv = try {
            ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            release()
            throw e
        }
        server = srv
        val secret = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        File(dir, PORT_FILE).writeText("${srv.localPort} $secret")
        Thread({ serve(srv, secret, onActivate) }, "crossdrop-single-instance").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { raf?.close() }
        runCatching { File(dir, PORT_FILE).delete() }
        server = null; lock = null; raf = null
    }

    private fun serve(srv: ServerSocket, secret: String, onActivate: (List<File>) -> Unit) {
        while (!srv.isClosed) {
            try {
                srv.accept().use { s ->
                    s.soTimeout = 2_000
                    val input = s.getInputStream()
                    val first = readLine(input)?.trim() ?: return@use
                    if (first == "show" || first.startsWith("show ")) {
                        val trusted = constantTimeEquals(first.removePrefix("show").trim(), secret)
                        val files = ArrayList<File>()
                        // Older launchers send "show" alone and wait for "ok": a short idle ends the list.
                        s.soTimeout = 300
                        while (trusted && files.size < MAX_FILES) {
                            val line = runCatching { readLine(input) }.getOrNull() ?: break
                            if (line == "end") break
                            if (line.startsWith("file ")) {
                                val path = line.removePrefix("file ")
                                if (!isNetworkPath(path)) files += File(path)
                            }
                        }
                        onActivate(files)
                        // The reply proves to the caller that this port is CrossDrop, not a stranger
                        // that happens to have inherited a stale port number.
                        s.getOutputStream().apply { write("ok\n".toByteArray()); flush() }
                    }
                }
            } catch (_: Exception) {
                // closed, timed out, or a client that hung up — keep serving unless closed
            }
        }
    }

    /**
     * The primary may still be starting (lock held, port not yet written — or a stale port file from a
     * crashed run still on disk), so retry briefly until a listener answers "ok".
     */
    private fun signalRunningInstance(files: List<File>) {
        repeat(20) {
            val parts = runCatching { File(dir, PORT_FILE).readText().trim().split(' ') }.getOrNull()
            val port = parts?.firstOrNull()?.toIntOrNull()
            val secret = parts?.getOrNull(1).orEmpty()
            val message = buildString {
                append("show $secret\n")
                files.take(MAX_FILES).forEach { append("file ").append(it.absolutePath.replace("\n", "")).append('\n') }
                append("end\n")
            }
            if (port != null && runCatching {
                    Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                        s.soTimeout = 1_000
                        s.getOutputStream().apply { write(message.toByteArray(Charsets.UTF_8)); flush() }
                        s.getInputStream().bufferedReader().readLine()?.trim() == "ok"
                    }
                }.getOrDefault(false)
            ) return
            Thread.sleep(100)
        }
    }

    /** One line, at most [MAX_LINE] bytes (a client streaming without newlines can't exhaust memory). */
    private fun readLine(input: java.io.InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8).trimEnd('\r')
            if (buf.size() >= MAX_LINE) throw java.io.IOException("line too long")
            buf.write(b)
        }
    }

    private fun constantTimeEquals(a: String, b: String) =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray()) && b.isNotEmpty()

    private companion object {
        const val MAX_LINE = 8_192
        const val LOCK_FILE = "instance.lock"
        const val PORT_FILE = "instance.port"
        const val MAX_FILES = 1_000
    }
}

/** `\\server\share` or `//server/share`: anything Windows would reach over SMB. */
internal fun isNetworkPath(path: String): Boolean =
    path.startsWith("\\\\") || path.startsWith("//")
