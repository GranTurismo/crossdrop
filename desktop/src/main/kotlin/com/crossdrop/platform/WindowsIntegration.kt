package com.crossdrop.platform

import java.io.File

/**
 * Explorer integration for the MSI/EXE build: a "CrossDrop" entry in the per-user "Send to" menu,
 * so right-click → Send to → CrossDrop hands files to the app (they wait on the radar for a device
 * pick, see SingleInstance). Created once; if the user deletes it, it stays deleted.
 *
 * Not for the Microsoft Store (MSIX) build: writes to AppData are redirected into the package there,
 * so Explorer would never see the shortcut.
 */
object WindowsIntegration {
    private const val PREF = "sendto_installed"

    /** The jpackage launcher (CrossDrop.exe), or null when not running from an installed build. */
    internal fun launcherPath(): File? =
        System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    internal fun isPackaged(launcher: File): Boolean =
        launcher.path.contains("\\WindowsApps\\", ignoreCase = true)

    fun ensureSendTo(prefs: Prefs) {
        if (!Platform.isWindows || prefs.getBoolean(PREF, false)) return
        val exe = launcherPath() ?: return
        if (isPackaged(exe)) return
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return
        val lnk = File(appData, "Microsoft\\Windows\\SendTo\\CrossDrop.lnk")
        Thread({
            val ok = runCatching { createShortcut(lnk, exe) }.getOrDefault(false)
            if (ok) prefs.putBoolean(PREF, true)
        }, "crossdrop-sendto").apply { isDaemon = true }.start()
    }

    /** PowerShell + WScript.Shell, the stock way to write a .lnk without native code. */
    private fun createShortcut(lnk: File, exe: File): Boolean {
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val script = "\$s=(New-Object -ComObject WScript.Shell).CreateShortcut(${q(lnk.path)});" +
            "\$s.TargetPath=${q(exe.path)};\$s.IconLocation=${q(exe.path + ",0")};" +
            "\$s.Description='Send with CrossDrop';\$s.Save()"
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor() == 0 && lnk.isFile
    }
}
