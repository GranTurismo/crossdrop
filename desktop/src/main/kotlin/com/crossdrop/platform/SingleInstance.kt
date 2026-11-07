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
 * port and exits. Only the word "show" is acted on, and only to raise the window.
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
    fun acquire(onActivate: () -> Unit): Boolean = try {
        acquireOrSignal(onActivate)
    } catch (e: Exception) {
        Log.w("SingleInstance", "single-instance protection unavailable", e)
        release()
        true
    }

    private fun acquireOrSignal(onActivate: () -> Unit): Boolean {
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
            signalRunningInstance()
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
        File(dir, PORT_FILE).writeText(srv.localPort.toString())
        Thread({ serve(srv, onActivate) }, "crossdrop-single-instance").apply { isDaemon = true }.start()
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

    private fun serve(srv: ServerSocket, onActivate: () -> Unit) {
        while (!srv.isClosed) {
            try {
                srv.accept().use { s ->
                    s.soTimeout = 2_000
                    if (s.getInputStream().bufferedReader().readLine()?.trim() == "show") {
                        onActivate()
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
    private fun signalRunningInstance() {
        repeat(20) {
            val port = runCatching { File(dir, PORT_FILE).readText().trim().toInt() }.getOrNull()
            if (port != null && runCatching {
                    Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                        s.soTimeout = 1_000
                        s.getOutputStream().apply { write("show\n".toByteArray()); flush() }
                        s.getInputStream().bufferedReader().readLine()?.trim() == "ok"
                    }
                }.getOrDefault(false)
            ) return
            Thread.sleep(100)
        }
    }

    private companion object {
        const val LOCK_FILE = "instance.lock"
        const val PORT_FILE = "instance.port"
    }
}
