package com.crossdrop.platform

import com.crossdrop.core.i18n.tr
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.CountDownLatch

/** Thin wrappers over the OS's native affordances: file manager, clipboard, file dialogs, sleep. */
object SystemActions {

    fun open(file: File) {
        // On Linux AWT's Desktop only works when GTK/GNOME libraries happen to be loadable; xdg-open always is the OS answer.
        if (Platform.current == Platform.LINUX && LinuxDesktop.xdgOpen(file.absolutePath)) return
        runCatching { Desktop.getDesktop().open(file) }
            .onFailure { Log.w("System", "open failed", it) }
    }

    fun openUrl(url: String) {
        if (Platform.current == Platform.LINUX && LinuxDesktop.xdgOpen(url)) return
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { Log.w("System", "browse failed", it) }
    }

    /**
     * Command that selects [file] in the OS file manager, or null when the OS
     * has no "select this file" verb (callers then just open the folder).
     * `explorer.exe /select,` wants the path as its own argument — Java quotes
     * it for us — and exits 1 even on success, so the exit code is ignored.
     */
    internal fun revealCommand(platform: Platform, file: File): List<String>? = when (platform) {
        Platform.MAC -> listOf("open", "-R", file.absolutePath)
        Platform.WINDOWS -> listOf("explorer.exe", "/select,", file.absolutePath)
        Platform.LINUX -> LinuxDesktop.showItemsCommand(file)
        Platform.OTHER -> null
    }

    /** Select [file] in a Finder / File Explorer / Linux file-manager window. */
    fun reveal(file: File) {
        if (Platform.current == Platform.LINUX) { LinuxDesktop.reveal(file, ::open); return }
        val cmd = revealCommand(Platform.current, file)
        if (cmd != null) runCatching { ProcessBuilder(cmd).start() }
            .onFailure { open(file.parentFile ?: file) }
        else open(file.parentFile ?: file)
    }

    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /** Native multi-select file chooser; empty list when cancelled. */
    fun pickFiles(parent: Frame? = null, title: String = tr("Choose files")): List<File> {
        if (Platform.isLinux) {
            // zenity / kdialog when installed; otherwise a Swing chooser (AWT's FileDialog is a Motif box on Linux).
            LinuxDesktop.runPicker(LinuxDesktop.pickerCommands(
                LinuxDesktop.Pick.FILES, title, File(System.getProperty("user.home")), System.getenv("XDG_CURRENT_DESKTOP")))
                ?.let { return it }
            useSystemLookAndFeel()
            val chooser = javax.swing.JFileChooser().apply { isMultiSelectionEnabled = true; dialogTitle = title }
            return if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION)
                chooser.selectedFiles.toList() else emptyList()
        }
        val dlg = FileDialog(parent, title, FileDialog.LOAD).apply { isMultipleMode = true }
        dlg.isVisible = true   // blocks until dismissed (modal)
        return dlg.files?.toList().orEmpty()
    }

    /**
     * Native folder chooser. macOS honours the directory flag on [FileDialog];
     * Windows' AWT FileDialog has no folder mode, so it gets a Swing chooser
     * restyled with the system look-and-feel (Explorer-like rather than Metal);
     * Linux tries zenity / kdialog first, then the same Swing chooser.
     */
    fun pickFolder(parent: Frame? = null, title: String = tr("Choose a folder to share")): File? {
        if (Platform.isLinux) {
            LinuxDesktop.runPicker(LinuxDesktop.pickerCommands(
                LinuxDesktop.Pick.FOLDER, title, File(System.getProperty("user.home")), System.getenv("XDG_CURRENT_DESKTOP")))
                ?.let { return it.firstOrNull() }
        }
        if (!Platform.isMac) {
            if (Platform.isWindows || Platform.isLinux) useSystemLookAndFeel()
            val chooser = javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
            }
            return if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION)
                chooser.selectedFile else null
        }
        val key = "apple.awt.fileDialogForDirectories"
        System.setProperty(key, "true")
        try {
            val dlg = FileDialog(parent, title, FileDialog.LOAD)
            dlg.isVisible = true
            return dlg.files?.firstOrNull() ?: dlg.directory?.let { d ->
                dlg.file?.let { File(d, it) }
            }
        } finally {
            System.setProperty(key, "false")
        }
    }

    @Volatile private var systemLafSet = false
    private fun useSystemLookAndFeel() {
        if (systemLafSet) return
        systemLafSet = true
        runCatching {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        }
    }
}

/**
 * Keeps the machine from napping the process while something is being served or
 * moved — the desktop analogue of Android's WifiLock + WakeLock.
 *
 *  - macOS: `caffeinate -i -w <pid>`, which dies with us if we crash.
 *  - Linux: a `systemd-inhibit` (sleep + idle) that lasts as long as our pid ([LinuxDesktop.inhibitCommand]);
 *    without systemd it does nothing.
 *  - Windows: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` on a
 *    dedicated thread (the state is per-thread and ends with it, so a crash or
 *    quit releases it automatically). Only idle *sleep* is blocked — the
 *    display may still turn off, which is what a transfer wants.
 */
object SleepGuard {
    private var proc: Process? = null
    private var awake: ExecutionStateThread? = null

    @Synchronized fun hold(active: Boolean) {
        when (Platform.current) {
            Platform.MAC -> holdProcess(active) { listOf("/usr/bin/caffeinate", "-i", "-w", it.toString()) }
            Platform.WINDOWS -> holdWindows(active, Kernel32Api::instance)
            Platform.LINUX -> holdProcess(active, LinuxDesktop::inhibitCommand)
            Platform.OTHER -> Unit
        }
    }

    /** Keep a helper process alive while [active]; [command] gets our pid so the helper ends with us. */
    private fun holdProcess(active: Boolean, command: (Long) -> List<String>) {
        if (active == (proc?.isAlive == true)) return
        if (!active) { proc?.destroy(); proc = null; return }
        proc = runCatching {
            ProcessBuilder(command(ProcessHandle.current().pid()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull()
    }

    internal fun holdWindows(active: Boolean, api: () -> ExecutionStateApi?) {
        if (active == (awake != null)) return
        if (!active) { awake?.release(); awake = null; return }
        val a = api() ?: return          // kernel32 unavailable — best effort, never fatal
        awake = ExecutionStateThread(a).also { it.start() }
    }
}

/** The one Win32 call [SleepGuard] needs, as an interface so tests can fake it. */
internal interface ExecutionStateApi {
    fun setExecutionState(flags: Int): Int
}

internal const val ES_SYSTEM_REQUIRED = 0x00000001
internal const val ES_CONTINUOUS = 0x80000000.toInt()

/**
 * Sets the keep-awake state, parks until [release], then clears it — all on the
 * same thread, because `ES_CONTINUOUS` is scoped to the calling thread.
 */
internal class ExecutionStateThread(private val api: ExecutionStateApi) : Thread("crossdrop-keep-awake") {
    private val released = CountDownLatch(1)
    init { isDaemon = true }

    override fun run() {
        api.setExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)
        try { released.await() } catch (_: InterruptedException) { /* fall through and clear */ }
        api.setExecutionState(ES_CONTINUOUS)
    }

    fun release() = released.countDown()
}

private interface Kernel32 : com.sun.jna.win32.StdCallLibrary {
    fun SetThreadExecutionState(esFlags: Int): Int
}

private object Kernel32Api {
    val instance: ExecutionStateApi? by lazy {
        runCatching {
            val k = com.sun.jna.Native.load("kernel32", Kernel32::class.java)
            object : ExecutionStateApi {
                override fun setExecutionState(flags: Int) = k.SetThreadExecutionState(flags)
            }
        }.onFailure { Log.w("SleepGuard", "kernel32 unavailable", it) }.getOrNull()
    }
}
