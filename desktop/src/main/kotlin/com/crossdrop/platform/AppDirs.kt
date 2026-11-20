package com.crossdrop.platform

import java.io.File

/**
 * Where CrossDrop keeps its state, following each OS's convention:
 * macOS `~/Library/Application Support/CrossDrop`, Windows
 * `%LOCALAPPDATA%\CrossDrop` (local, not roaming — inbox files and the vault
 * must not follow the user's profile to other PCs), Linux `$XDG_DATA_HOME/CrossDrop`
 * (`~/.local/share/CrossDrop`), and a dot-directory under the home folder elsewhere (dev only).
 */
object AppDirs {
    val isMac: Boolean get() = Platform.isMac

    /** Pure path rule behind [support], so every OS's answer is unit-testable. */
    internal fun baseDir(platform: Platform, home: File, localAppData: String?, xdgDataHome: String? = null): File = when (platform) {
        Platform.MAC -> File(home, "Library/Application Support/CrossDrop")
        Platform.WINDOWS -> File(
            localAppData?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path,
            "CrossDrop",
        )
        // The XDG spec says a relative $XDG_DATA_HOME is invalid and must be ignored.
        Platform.LINUX -> File(
            xdgDataHome?.takeIf { File(it).isAbsolute } ?: File(home, ".local/share").path,
            "CrossDrop",
        )
        Platform.OTHER -> File(home, ".crossdrop")
    }

    /** Folder name inside Downloads. */
    const val INBOX_FOLDER = "CrossDrop"

    /**
     * The user's Downloads folder. On Windows it can be moved (OneDrive, another drive) — the
     * registry holds the real location; on Linux it is whatever `user-dirs.dirs` says (translated
     * folder names, a moved folder); everywhere else it is `~/Downloads`.
     * [registry] reads one `User Shell Folders` value (null when absent), [env] expands `%VARS%`,
     * [readFile] reads a small text file for the Linux lookup (null when absent).
     */
    internal fun downloadsDir(
        platform: Platform, home: File,
        env: (String) -> String? = System::getenv,
        readFile: (File) -> String? = { runCatching { it.readText() }.getOrNull() },
        registry: (String) -> String? = ::readUserShellFolder,
    ): File {
        if (platform == Platform.LINUX) LinuxDesktop.downloadsDir(home, env, readFile)?.let { return it }
        if (platform == Platform.WINDOWS) {
            val raw = registry(WINDOWS_DOWNLOADS_GUID)?.trim().orEmpty()
            val expanded = Regex("%([^%]+)%").replace(raw) { env(it.groupValues[1]) ?: it.value }
            // Anything still holding a %VAR% could not be resolved — don't create a folder named after it.
            if (expanded.isNotBlank() && '%' !in expanded) return File(expanded)
        }
        return File(home, "Downloads")
    }

    /** `<downloads>/CrossDrop`, or [fallback] when it can't be created and written to. */
    internal fun inboxIn(downloads: File, fallback: File): File {
        val dir = File(downloads, INBOX_FOLDER)
        val usable = runCatching { dir.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)
        return if (usable) dir else fallback.apply { mkdirs() }
    }

    private fun downloads(): File = downloadsDir(Platform.current, File(System.getProperty("user.home")))

    private const val WINDOWS_DOWNLOADS_GUID = "{374DE290-123F-4565-9164-39C4925E467B}"

    private fun readUserShellFolder(name: String): String? = runCatching {
        val p = ProcessBuilder(
            "reg", "query",
            """HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders""", "/v", name,
        ).redirectErrorStream(true).start()
        // One short line of output, so waiting first can't fill the pipe — and a hung reg.exe can't hang startup.
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        val out = p.inputStream.bufferedReader().readText()
        // "    {GUID}    REG_EXPAND_SZ    %USERPROFILE%\Downloads"
        out.lineSequence().firstOrNull { name in it }
            ?.substringAfter("REG_EXPAND_SZ", "")?.ifBlank { null }
            ?: out.lineSequence().firstOrNull { name in it }?.substringAfter("REG_SZ", "")?.ifBlank { null }
    }.getOrNull()

    val support: File by lazy {
        val home = File(System.getProperty("user.home"))
        val base = System.getProperty("crossdrop.home")?.let(::File)
            ?: baseDir(Platform.current, home, System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"))
        base.apply { mkdirs() }
    }

    /**
     * Where received files land: a `CrossDrop` folder inside the user's Downloads, so they are
     * one click away in Finder / File Explorer / the file manager and any app can open them. Falls back to the old
     * private folder only when Downloads can't be written to.
     */
    val inbox: File by lazy {
        System.getProperty("crossdrop.inbox")?.let(::File)?.apply { mkdirs() }
            ?: inboxIn(downloads(), legacyInbox)
    }

    /** Resume pool + delta indexes of the inbox. Bookkeeping stays out of the user's Downloads. */
    val inboxState: File get() = File(support, "inbox-state").apply { mkdirs() }

    /** The inbox before it moved to Downloads: files here are migrated across on first launch. */
    val legacyInbox: File get() = File(support, "inbox")

    val vault: File get() = File(support, "vault").apply { mkdirs() }
    val driveStaging: File get() = File(support, "drive-staging").apply { mkdirs() }
}
