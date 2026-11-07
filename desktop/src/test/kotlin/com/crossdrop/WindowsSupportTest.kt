package com.crossdrop

import com.crossdrop.net.LocalIp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.platform.AppDirs
import com.crossdrop.platform.ES_CONTINUOUS
import com.crossdrop.platform.ES_SYSTEM_REQUIRED
import com.crossdrop.platform.ExecutionStateApi
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SingleInstance
import com.crossdrop.platform.SleepGuard
import com.crossdrop.platform.SystemActions
import com.crossdrop.storage.InboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Windows port's logic, exercised on any host by injecting the platform
 * (the real Win32 / Explorer / NTFS calls can only be proven on Windows itself —
 * see the CI workflow, which runs this same suite on a Windows runner).
 */
class WindowsSupportTest {

    // ------------------------------------------------------------ Platform --

    @Test
    fun `os name maps to platform`() {
        assertEquals(Platform.MAC, Platform.of("Mac OS X"))
        assertEquals(Platform.WINDOWS, Platform.of("Windows 11"))
        assertEquals(Platform.WINDOWS, Platform.of("Windows Server 2022"))
        assertEquals(Platform.OTHER, Platform.of("Linux"))
        assertEquals(Platform.OTHER, Platform.of(null))
        assertEquals("PC", Platform.WINDOWS.computerNoun)
        assertEquals("File Explorer", Platform.WINDOWS.fileManager)
        assertEquals("Finder", Platform.MAC.fileManager)
    }

    @Test
    fun `app data lives where each OS expects it`() {
        val home = File("/users/me")
        assertEquals(File("/users/me/Library/Application Support/CrossDrop"),
            AppDirs.baseDir(Platform.MAC, home, null))
        assertEquals(File("/users/me/.crossdrop"), AppDirs.baseDir(Platform.OTHER, home, null))
        assertEquals(File("D:/Local/CrossDrop"), AppDirs.baseDir(Platform.WINDOWS, home, "D:/Local"))
        // LOCALAPPDATA unset or blank → the standard location under the profile
        assertEquals(File("/users/me/AppData/Local", "CrossDrop"), AppDirs.baseDir(Platform.WINDOWS, home, null))
        assertEquals(File("/users/me/AppData/Local", "CrossDrop"), AppDirs.baseDir(Platform.WINDOWS, home, "  "))
    }

    @Test
    fun `reveal selects the file in the native file manager`() {
        val f = File("/tmp/a b/c.txt")
        assertEquals(listOf("open", "-R", f.absolutePath), SystemActions.revealCommand(Platform.MAC, f))
        assertEquals(listOf("explorer.exe", "/select,", f.absolutePath), SystemActions.revealCommand(Platform.WINDOWS, f))
        assertNull(SystemActions.revealCommand(Platform.OTHER, f))
    }

    // ------------------------------------------------------------- LocalIp --

    private fun c(name: String, display: String, ip: String) =
        LocalIp.Candidate(name, display, InetAddress.getByName(ip) as Inet4Address)

    @Test
    fun `windows picks wifi over hyper-v, vmware, vpn and bluetooth adapters`() {
        val picked = LocalIp.choose(listOf(
            c("eth0", "Hyper-V Virtual Ethernet Adapter", "172.28.80.1"),       // WSL / Hyper-V switch
            c("eth1", "VMware Virtual Ethernet Adapter for VMnet8", "192.168.150.1"),
            c("eth2", "TAP-Windows Adapter V9", "10.8.0.2"),
            c("net3", "Bluetooth Device (Personal Area Network)", "192.168.44.1"),
            c("wlan0", "Intel(R) Wi-Fi 6 AX201 160MHz", "192.168.1.23"),
        ))
        assertEquals("192.168.1.23", picked)
    }

    @Test
    fun `windows prefers a lan-looking adapter and ignores link-local`() {
        assertEquals("10.0.0.7", LocalIp.choose(listOf(
            c("net9", "Realtek PCIe GbE Family Controller", "169.254.10.4"),    // APIPA — not routable
            c("net8", "Realtek PCIe GbE Family Controller", "10.0.0.7"),
        )))
    }

    @Test
    fun `the only nic inside a hyper-v guest still counts`() {
        // Azure / Hyper-V VMs expose exactly this; excluding it would mean "no network".
        assertEquals("10.1.0.4", LocalIp.choose(listOf(c("eth0", "Microsoft Hyper-V Network Adapter", "10.1.0.4"))))
    }

    @Test
    fun `only virtual adapters means offline, as before`() {
        assertNull(LocalIp.choose(listOf(c("eth0", "Hyper-V Virtual Ethernet Adapter", "172.28.80.1"))))
        assertNull(LocalIp.choose(emptyList()))
    }

    @Test
    fun `mac behaviour is unchanged`() {
        assertEquals("192.168.1.5", LocalIp.choose(listOf(
            c("utun3", "utun3", "10.8.0.2"),
            c("awdl0", "awdl0", "169.254.8.8"),
            c("bridge100", "bridge100", "192.168.64.1"),
            c("en0", "en0", "192.168.1.5"),
        )))
    }

    // ----------------------------------------------------------- file names --

    @Test
    fun `received file names are safe on windows`() {
        val s = InboxStore.Companion::sanitizeName
        // device names open the device, not a file — with or without an extension
        assertEquals("_CON", s("CON"))
        assertEquals("_nul.txt", s("nul.txt"))
        assertEquals("_com1.log", s("com1.log"))
        assertEquals("_LPT9", s("LPT9"))
        assertEquals("_CON", s("CON."))
        assertEquals("console.txt", s("console.txt"))
        assertEquals("Com10", s("Com10"))                 // only COM1–COM9 are reserved
        // trailing dots/spaces are dropped by Win32 and would alias another name
        assertEquals("report.pdf", s("report.pdf."))
        assertEquals("a b", s("a b "))
        // separators, drive letters, and NTFS alternate data streams never survive
        assertEquals("evil.txt", s("..\\..\\evil.txt"))
        assertEquals("a.txt", s("C:\\Users\\x\\a.txt"))
        assertEquals("a.txt", s("../../a.txt"))
        assertEquals("doc_stream.txt", s("doc:stream.txt"))
        assertEquals("file", s("..."))
        assertEquals("file", s(""))
        // truncation must not expose a fresh trailing dot
        val cut = s("a".repeat(95) + ".b")
        assertEquals("a".repeat(95), cut)
        assertTrue(s("x".repeat(300)).length <= 96)
    }

    @Test
    fun `collision rename never leaves a trailing dot`() {
        val dir = Files.createTempDirectory("inbox").toFile()
        val inbox = InboxStore(dir)
        File(dir, "README").writeText("x")
        File(dir, "a.txt").writeText("x")
        assertEquals("README (1)", inbox.uniqueFile("README").name)
        assertEquals("a (1).txt", inbox.uniqueFile("a.txt").name)
        File(dir, "a (1).txt").writeText("x")
        assertEquals("a (2).txt", inbox.uniqueFile("a.txt").name)
        dir.deleteRecursively()
    }

    // ------------------------------------------------------ drive-root policy --

    private val noEnv: (String) -> String? = { null }

    // isShareable() hands the policy canonical paths; do the same so the POSIX-style fixtures
    // below stay comparable on a Windows host, where "/h/u" canonicalizes to "C:\h\u".
    private fun forbidden(p: String, platform: Platform, env: (String) -> String? = noEnv) =
        VaultHostManager.isForbiddenRoot(
            File(p).canonicalFile, File("/h/u").canonicalFile,
            File("/h/u/AppData/Local/CrossDrop").canonicalFile, platform, env)

    @Test
    fun `windows refuses system folders, appdata, drive roots and the profile`() {
        val env = mapOf("SystemRoot" to "/win", "ProgramFiles" to "/pf", "ProgramData" to "/pd")::get
        assertFalse(forbidden("/h/u/Documents", Platform.WINDOWS, env))
        assertFalse(forbidden("/data/photos", Platform.WINDOWS, env))
        assertTrue(forbidden("/", Platform.WINDOWS, env))                       // drive root
        assertTrue(forbidden("/h/u", Platform.WINDOWS, env))                    // whole profile
        assertTrue(forbidden("/h", Platform.WINDOWS, env))                      // ancestor of it (C:\Users)
        assertTrue(forbidden("/h/u/AppData/Roaming/x", Platform.WINDOWS, env))
        assertTrue(forbidden("/win/System32", Platform.WINDOWS, env))
        assertTrue(forbidden("/pf/App", Platform.WINDOWS, env))
        assertTrue(forbidden("/pd", Platform.WINDOWS, env))
        assertTrue(forbidden("/\$Recycle.Bin/S-1-5-21", Platform.WINDOWS, env))
        assertTrue(forbidden("/System Volume Information", Platform.WINDOWS, env))
        assertTrue(forbidden("/h/u/AppData/Local/CrossDrop/inbox", Platform.WINDOWS, env)) // our own state
        assertTrue(forbidden("/Windows.old/Users", Platform.WINDOWS, env))
        assertTrue(forbidden("/Recovery", Platform.WINDOWS, env))
        assertTrue(forbidden("/\$WINDOWS.~BT/Sources", Platform.WINDOWS, env))
    }

    @Test
    fun `windows refuses other users' profiles but allows Public`() {
        // home is /h/u, so its siblings under /h are the other profiles (C:\Users\<name>)
        assertTrue(forbidden("/h/other", Platform.WINDOWS))
        assertTrue(forbidden("/h/other/Documents", Platform.WINDOWS))
        assertTrue(forbidden("/h/Default/x", Platform.WINDOWS))
        assertFalse(forbidden("/h/Public/Shared", Platform.WINDOWS))
        assertFalse(forbidden("/h/PUBLIC/Shared", Platform.WINDOWS))
        assertFalse(forbidden("/h/u/Documents", Platform.WINDOWS))           // our own profile stays fine
        // a profile that is a drive's top folder must not make the whole drive off-limits
        assertFalse(VaultHostManager.isForbiddenRoot(
            File("/data/photos").canonicalFile, File("/me").canonicalFile,
            File("/me/AppData/Local/CrossDrop").canonicalFile, Platform.WINDOWS, noEnv))
    }

    @Test
    fun `mac still refuses library and keeps appdata shareable`() {
        assertTrue(forbidden("/h/u/Library/Mail", Platform.MAC))
        assertFalse(forbidden("/h/u/AppData", Platform.MAC))
        assertFalse(forbidden("/h/u/Documents", Platform.MAC))
    }

    // ----------------------------------------------------------- keep awake --

    private class FakeKernel : ExecutionStateApi {
        val calls = CopyOnWriteArrayList<Pair<Int, String>>()   // flags to calling thread
        override fun setExecutionState(flags: Int): Int {
            calls += flags to Thread.currentThread().name
            return 0
        }
    }

    private fun awaitCalls(k: FakeKernel, n: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (k.calls.size < n && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("expected $n execution-state calls", n, k.calls.size)
    }

    @Test
    fun `keep-awake sets and clears the state on one thread`() {
        val k = FakeKernel()
        SleepGuard.holdWindows(true) { k }
        awaitCalls(k, 1)
        SleepGuard.holdWindows(true) { k }                      // already held → no second thread
        SleepGuard.holdWindows(false) { k }
        awaitCalls(k, 2)
        SleepGuard.holdWindows(false) { k }                     // already released → no-op
        Thread.sleep(50)

        assertEquals(2, k.calls.size)
        assertEquals(ES_CONTINUOUS or ES_SYSTEM_REQUIRED, k.calls[0].first)
        assertEquals(ES_CONTINUOUS, k.calls[1].first)
        // ES_CONTINUOUS is scoped to the calling thread, so set and clear must share it
        assertEquals(k.calls[0].second, k.calls[1].second)
        assertEquals("crossdrop-keep-awake", k.calls[0].second)
    }

    @Test
    fun `keep-awake is best effort when kernel32 is unavailable`() {
        SleepGuard.holdWindows(true) { null }
        SleepGuard.holdWindows(false) { null }
    }

    // ------------------------------------------------------ single instance --

    @Test
    fun `an unusable state directory fails open rather than killing startup`() {
        val blocker = Files.createTempFile("not-a-dir", ".tmp").toFile()
        // "<a regular file>/sub" can never be created or locked
        val si = SingleInstance(File(blocker, "sub"))
        assertTrue("two windows beat no window", si.acquire { })
        si.release()
        blocker.delete()
    }

    @Test
    fun `a stranger on a stale port is not mistaken for the running instance`() {
        val dir = Files.createTempDirectory("single-stale").toFile()
        val raised = CountDownLatch(1)
        val first = SingleInstance(dir)
        assertTrue(first.acquire { raised.countDown() })

        // The recorded port now belongs to some unrelated process that accepts and hangs up.
        val stranger = java.net.ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        Thread({ while (!stranger.isClosed) runCatching { stranger.accept().close() } }).apply { isDaemon = true }.start()
        File(dir, "instance.port").writeText(stranger.localPort.toString())

        // The lock is still held, so the second launch is refused — but it must not have been
        // told "ok" by the stranger, and the real instance was (correctly) never signalled.
        val second = SingleInstance(dir)
        assertFalse(second.acquire { error("never primary") })
        assertFalse("nothing should have raised the first instance", raised.await(200, TimeUnit.MILLISECONDS))

        stranger.close()
        first.release()
        dir.deleteRecursively()
    }

    @Test
    fun `second launch is turned away and raises the first`() {
        val dir = Files.createTempDirectory("single").toFile()
        val raised = CountDownLatch(1)
        val first = SingleInstance(dir)
        assertTrue(first.acquire { raised.countDown() })

        val second = SingleInstance(dir)
        assertFalse("a second instance must not start", second.acquire { error("never primary") })
        assertTrue("first instance should have been asked to show", raised.await(3, TimeUnit.SECONDS))

        // once the first quits, the next launch becomes primary
        first.release()
        val third = SingleInstance(dir)
        assertTrue(third.acquire { })
        third.release()
        dir.deleteRecursively()
    }
}
