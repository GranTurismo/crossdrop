package com.crossdrop

import com.crossdrop.net.LocalIp
import com.crossdrop.net.VaultHostManager
import com.crossdrop.platform.AppDirs
import com.crossdrop.platform.LinuxDesktop
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SystemActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files

/**
 * The Linux port's decisions, run on any host by injecting the platform and the environment. What can only be
 * proven on a desktop (zenity, D-Bus, systemd) is checked by the Linux CI job, which installs the package and
 * starts the app under a virtual display.
 */
class LinuxSupportTest {

    private val home = File("/home/me")

    // ---------------------------------------------------------------- dirs --

    @Test
    fun `app data follows XDG_DATA_HOME, else local share, and ignores a relative value`() {
        assertEquals(File("/home/me/.local/share/CrossDrop"), AppDirs.baseDir(Platform.LINUX, home, null, null))
        assertEquals(File("/home/me/.local/share/CrossDrop"), AppDirs.baseDir(Platform.LINUX, home, null, ""))
        assertEquals(File("/data/xdg/CrossDrop"), AppDirs.baseDir(Platform.LINUX, home, null, "/data/xdg"))
        assertEquals(File("/home/me/.local/share/CrossDrop"), AppDirs.baseDir(Platform.LINUX, home, null, "relative/dir"))
        // the dev-only fallback for unknown systems is unchanged
        assertEquals(File("/home/me/.crossdrop"), AppDirs.baseDir(Platform.OTHER, home, null, "/data/xdg"))
    }

    private fun downloads(config: String?, env: Map<String, String> = emptyMap()) =
        AppDirs.downloadsDir(Platform.LINUX, home, env::get, { config }) { null }

    @Test
    fun `downloads comes from user-dirs, translated or moved`() {
        assertEquals(File("/home/me/Downloads"), downloads(null))                                      // no file at all
        assertEquals(File("/home/me/Downloads"), downloads("XDG_DESKTOP_DIR=\"\$HOME/Desktop\"\n"))    // no download line
        assertEquals(File("/home/me/Téléchargements"), downloads("# comment\nXDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n"))
        assertEquals(File("/mnt/big/dl"), downloads("XDG_DOWNLOAD_DIR=\"/mnt/big/dl\"\n"))
        assertEquals(File("/home/me/My Files"), downloads("  XDG_DOWNLOAD_DIR = \"\$HOME/My Files\"  \n"))
    }

    @Test
    fun `a switched-off or nonsense downloads entry never makes home the inbox parent`() {
        assertEquals(File("/home/me/Downloads"), downloads("XDG_DOWNLOAD_DIR=\"\$HOME\"\n"))       // how xdg switches it off
        assertEquals(File("/home/me/Downloads"), downloads("XDG_DOWNLOAD_DIR=\"relative\"\n"))
        assertEquals(File("/home/me/Downloads"), downloads("XDG_DOWNLOAD_DIR=\"\"\n"))
    }

    @Test
    fun `user-dirs lives under XDG_CONFIG_HOME when that is set`() {
        val seen = mutableListOf<File>()
        AppDirs.downloadsDir(Platform.LINUX, home, mapOf("XDG_CONFIG_HOME" to "/cfg")::get, { seen += it; null }) { null }
        AppDirs.downloadsDir(Platform.LINUX, home, emptyMap<String, String>()::get, { seen += it; null }) { null }
        assertEquals(listOf(File("/cfg/user-dirs.dirs"), File("/home/me/.config/user-dirs.dirs")), seen)
    }

    // ------------------------------------------------------ words + commands --

    @Test
    fun `linux words`() {
        assertEquals("computer", Platform.LINUX.computerNoun)
        assertEquals("file manager", Platform.LINUX.fileManager)
        assertEquals("system tray", Platform.LINUX.trayName)
    }

    @Test
    fun `reveal asks the file manager over dbus, with the path as a proper file uri`() {
        val cmd = SystemActions.revealCommand(Platform.LINUX, File("/tmp/a b/c,d.txt"))!!
        assertEquals("dbus-send", cmd.first())
        assertTrue("org.freedesktop.FileManager1.ShowItems" in cmd)
        assertTrue("--dest=org.freedesktop.FileManager1" in cmd)
        // spaces percent-encoded, the comma that would split a dbus array escaped, three slashes after file:
        assertTrue(cmd.toString(), "array:string:file:///tmp/a%20b/c%2Cd.txt" in cmd)
    }

    @Test
    fun `keep-awake is a systemd inhibitor that ends with our process`() {
        val cmd = LinuxDesktop.inhibitCommand(4242)
        assertEquals("systemd-inhibit", cmd.first())
        assertTrue("--what=sleep:idle" in cmd)
        assertTrue("--mode=block" in cmd)
        assertEquals(listOf("tail", "--pid=4242", "-f", "/dev/null"), cmd.takeLast(4))
    }

    @Test
    fun `pickers - kde prefers kdialog, everything else zenity, both stay as fallbacks`() {
        val gnome = LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FILES, "Send", home, "ubuntu:GNOME")
        assertEquals(listOf("zenity", "kdialog"), gnome.map { it.first() })
        val kde = LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FILES, "Send", home, "KDE")
        assertEquals(listOf("kdialog", "zenity"), kde.map { it.first() })
        assertEquals(listOf("zenity", "kdialog"), LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FILES, "x", home, null).map { it.first() })

        val z = gnome[0]
        assertTrue("--title=Send" in z && "--multiple" in z && "--separator=\n" in z)
        assertTrue("--directory" !in z)
        val zFolder = LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FOLDER, "Share", home, null)[0]
        assertTrue("--directory" in zFolder && "--multiple" !in zFolder)
        val kFolder = LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FOLDER, "Share", home, "KDE")[0]
        assertTrue("--getexistingdirectory" in kFolder)
        assertTrue("--getopenfilename" in kde[0] && "--separate-output" in kde[0])
    }

    @Test
    fun `a title can never be taken for an option`() {
        // the title travels as --title=… (zenity) or after --title (kdialog), so a title like "--help" stays data
        val cmds = LinuxDesktop.pickerCommands(LinuxDesktop.Pick.FILES, "--help", home, null)
        assertTrue("--title=--help" in cmds[0])
        val k = cmds[1]
        assertEquals("--help", k[k.indexOf("--title") + 1])
    }

    @Test
    fun `picker output keeps only files that exist`() {
        val dir = Files.createTempDirectory("pick").toFile()
        val a = File(dir, "a b.txt").apply { writeText("x") }
        val b = File(dir, "b.txt").apply { writeText("y") }
        val out = "${a.path}\n\n  ${b.path}  \n/definitely/not/here.txt\n"
        assertEquals(listOf(a, b), LinuxDesktop.parsePicked(out))
        assertEquals(emptyList<File>(), LinuxDesktop.parsePicked(""))
        dir.deleteRecursively()
    }

    @Test
    fun `finding a tool on PATH`() {
        val bin = Files.createTempDirectory("bin").toFile()
        File(bin, "zenity").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(bin, "notexec").apply { writeText("x"); setExecutable(false) }
        val path = "/nonexistent${File.pathSeparator}${bin.path}${File.pathSeparator}"
        assertTrue(LinuxDesktop.onPath("zenity", path))
        assertFalse(LinuxDesktop.onPath("notexec", path))
        assertFalse(LinuxDesktop.onPath("kdialog", path))
        assertFalse(LinuxDesktop.onPath("zenity", null))
        bin.deleteRecursively()
    }

    // ------------------------------------------------- running the pickers --

    /** A stand-in for zenity/kdialog: a script that sleeps a moment, then prints [out] and exits with [code]. */
    private fun fakePicker(dir: File, name: String, out: String, code: Int, sleep: String = "0.4"): List<String> {
        val f = File(dir, name).apply {
            writeText("#!/bin/sh\nsleep $sleep\nprintf '%s' '$out'\nexit $code\n")
            setExecutable(true)
        }
        return listOf(f.path)
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        javax.swing.SwingUtilities.invokeAndWait { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }

    @Test
    fun `a picker returns the chosen files, and cancel means empty rather than unavailable`() {
        val dir = Files.createTempDirectory("fakepick").toFile()
        val a = File(dir, "a.txt").apply { writeText("x") }
        assertEquals(listOf(a), LinuxDesktop.runPicker(listOf(fakePicker(dir, "ok", a.path + "\n", 0, "0"))))
        assertEquals(emptyList<File>(), LinuxDesktop.runPicker(listOf(fakePicker(dir, "cancel", "", 1, "0"))))
        // not installed at all -> null, so the caller can use its Swing chooser
        assertNull(LinuxDesktop.runPicker(listOf(listOf(File(dir, "missing").path))))
        assertNull(LinuxDesktop.runPicker(emptyList()))
        dir.deleteRecursively()
    }

    @Test
    fun `a crashed picker falls through to the next one`() {
        val dir = Files.createTempDirectory("fakepick").toFile()
        val a = File(dir, "a.txt").apply { writeText("x") }
        val picked = LinuxDesktop.runPicker(listOf(
            fakePicker(dir, "crash", "", 255, "0"),
            fakePicker(dir, "ok", a.path, 0, "0"),
        ))
        assertEquals(listOf(a), picked)
        // every picker broken: unavailable, never a hang or an exception
        assertNull(LinuxDesktop.runPicker(listOf(fakePicker(dir, "crash2", "", 2, "0"))))
        dir.deleteRecursively()
    }

    @Test
    fun `waiting for a picker on the ui thread keeps the ui thread's events flowing`() {
        val dir = Files.createTempDirectory("fakepick").toFile()
        val a = File(dir, "a.txt").apply { writeText("x") }
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        var picked: List<File>? = null
        onUiThread {
            // Posted while the dialog is open: it can only run before runPicker returns if events are being pumped.
            javax.swing.SwingUtilities.invokeLater { ran.set(true) }
            picked = LinuxDesktop.runPicker(listOf(fakePicker(dir, "slow", a.path, 0, "0.6")))
            assertTrue("the window would have frozen while the dialog was open", ran.get())
        }
        assertEquals(listOf(a), picked)
        dir.deleteRecursively()
    }

    @Test
    fun `a picker that ends instantly cannot strand the ui thread`() {
        // exit() before enter() is the race the wait loop has to survive; run it many times.
        val dir = Files.createTempDirectory("fakepick").toFile()
        val quick = fakePicker(dir, "quick", "", 1, "0")
        onUiThread { repeat(60) { assertEquals(emptyList<File>(), LinuxDesktop.runPicker(listOf(quick))) } }
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------- network --

    private fun c(name: String, ip: String) = LocalIp.Candidate(name, "", InetAddress.getByName(ip) as Inet4Address)

    @Test
    fun `linux picks the real nic over docker, libvirt, vpn and container bridges`() {
        val picked = LocalIp.choose(listOf(
            c("docker0", "172.17.0.1"), c("br-3f2a9c1d7e", "172.18.0.1"), c("virbr0", "192.168.122.1"),
            c("lxdbr0", "10.131.2.1"), c("podman0", "10.88.0.1"), c("cni0", "10.244.0.1"), c("flannel.1", "10.244.0.0"),
            c("wg0", "10.66.66.2"), c("tailscale0", "100.101.102.103"), c("zt5u4abcde", "10.147.17.5"),
            c("veth1a2b3c", "172.19.0.1"), c("vnet0", "192.168.123.1"), c("kube-ipvs0", "10.96.0.1"),
            c("wlp2s0", "192.168.1.40"),
        ))
        assertEquals("192.168.1.40", picked)
    }

    @Test
    fun `systemd predictable names count as lan`() {
        for (name in listOf("enp3s0", "eno1", "ens33", "enx00e04c680001", "wlp2s0", "wlo1", "wlan0", "eth0")) {
            // a virtual-looking private address listed first must lose to a lan-looking one
            assertEquals(name, "10.0.0.5", LocalIp.choose(listOf(c("br-abc", "10.9.9.9"), c(name, "10.0.0.5"))))
        }
        // a plain bridge (br0) is how servers and VM hosts attach their one real nic, so it must still work
        assertEquals("192.168.0.9", LocalIp.choose(listOf(c("br0", "192.168.0.9"))))
    }

    // ---------------------------------------------------------- drive roots --

    private fun forbidden(path: String, home: File = this.home, support: File = File("/home/me/.local/share/CrossDrop")) =
        VaultHostManager.isForbiddenRoot(File(path), home, support, Platform.LINUX) { null }

    @Test
    fun `linux refuses system folders, dotfolders, other homes and its own state`() {
        for (p in listOf("/", "/home/me", "/home", "/etc", "/etc/ssh", "/usr/share", "/proc/1", "/sys/class",
            "/dev", "/boot", "/root", "/run/user/1000", "/bin", "/lib64",
            "/home/me/.ssh", "/home/me/.config/google-chrome", "/home/me/.gnupg/private-keys-v1.d", "/home/me/.mozilla",
            "/home/other", "/home/other/Documents", "/home/me/.local/share/CrossDrop/inbox")) {
            assertTrue(p, forbidden(p))
        }
        for (p in listOf("/home/me/Documents", "/home/me/Downloads/CrossDrop", "/home/me/my.stuff", "/mnt/data", "/media/me/USB",
            "/run/media/me/USB", "/srv/files", "/opt/shared", "/tmp/share", "/var/www")) {
            assertFalse(p, forbidden(p))
        }
    }

    @Test
    fun `root's own home is not locked by the root-folder rule`() {
        val rootHome = File("/root")
        assertTrue(forbidden("/root", rootHome, File("/root/.local/share/CrossDrop")))                // the whole home
        assertFalse(forbidden("/root/Documents", rootHome, File("/root/.local/share/CrossDrop")))
        assertTrue(forbidden("/root/.ssh", rootHome, File("/root/.local/share/CrossDrop")))
        assertTrue(forbidden("/etc", rootHome, File("/root/.local/share/CrossDrop")))
    }

    @Test
    fun `linux needs no windows name rules and no mac library rule`() {
        assertFalse(forbidden("/home/me/Library/Mail"))
        assertFalse(forbidden("/home/me/AppData/stuff"))
        assertNull(SystemActions.revealCommand(Platform.OTHER, File("/x")))
    }
}
