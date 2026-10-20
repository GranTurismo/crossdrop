package com.crossdrop.platform

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** Thin wrappers over macOS-native affordances: Finder, clipboard, file dialogs, sleep. */
object SystemActions {

    fun open(file: File) {
        runCatching { Desktop.getDesktop().open(file) }
            .onFailure { Log.w("System", "open failed", it) }
    }

    fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { Log.w("System", "browse failed", it) }
    }

    /** Select [file] in a Finder window. */
    fun reveal(file: File) {
        if (AppDirs.isMac) {
            runCatching { ProcessBuilder("open", "-R", file.absolutePath).start() }
        } else open(file.parentFile ?: file)
    }

    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /** Native multi-select file chooser; empty list when cancelled. */
    fun pickFiles(parent: Frame? = null, title: String = "Choose files"): List<File> {
        val dlg = FileDialog(parent, title, FileDialog.LOAD).apply { isMultipleMode = true }
        dlg.isVisible = true   // blocks until dismissed (modal)
        return dlg.files?.toList().orEmpty()
    }

    /** Native folder chooser (macOS honours the directory flag on FileDialog). */
    fun pickFolder(parent: Frame? = null, title: String = "Choose a folder to share"): File? {
        if (!AppDirs.isMac) {
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
}

/**
 * Keeps macOS from napping the process while something is being served or
 * moved — the desktop analogue of Android's WifiLock + WakeLock. Backed by
 * `caffeinate -i -w <pid>`, which dies with us if we crash.
 */
object SleepGuard {
    private var proc: Process? = null

    @Synchronized fun hold(active: Boolean) {
        if (active == (proc?.isAlive == true)) return
        if (!active) { proc?.destroy(); proc = null; return }
        if (!AppDirs.isMac) return
        proc = runCatching {
            ProcessBuilder("/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString())
                .redirectErrorStream(true).start()
        }.getOrNull()
    }
}
