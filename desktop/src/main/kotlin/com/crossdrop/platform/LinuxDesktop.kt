package com.crossdrop.platform

import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The freedesktop.org conventions CrossDrop leans on under Linux. Everything that decides *what to run*
 * or *where to look* is a pure function (unit-tested on any host); the few functions that really start a
 * process are at the bottom. Every external tool is optional: a missing one means the next fallback, never a crash.
 *
 *  - `user-dirs.dirs` says where Downloads is (translated or moved folders).
 *  - `xdg-open` opens files and links; the file manager's D-Bus `ShowItems` selects a file in it.
 *  - `zenity` (GNOME) or `kdialog` (KDE) give native pickers — AWT's own dialog is a Motif relic on Linux.
 *  - `systemd-inhibit` keeps the machine from sleeping mid-transfer.
 */
internal object LinuxDesktop {

    // ---------------------------------------------------------------- Downloads --

    /**
     * Downloads according to `$XDG_CONFIG_HOME/user-dirs.dirs` (`XDG_DOWNLOAD_DIR="$HOME/Téléchargements"`), or null to use
     * `~/Downloads`. The file points the folder at `$HOME` itself when the user switched the folder off: that is no
     * place to drop a `CrossDrop` folder, so it counts as "not set".
     */
    fun downloadsDir(home: File, env: (String) -> String?, readFile: (File) -> String?): File? {
        val config = env("XDG_CONFIG_HOME")?.takeIf { File(it).isAbsolute } ?: File(home, ".config").path
        val text = readFile(File(config, "user-dirs.dirs")) ?: return null
        val raw = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"") ?: return null
        val path = when {
            raw == "\$HOME" -> home.path
            raw.startsWith("\$HOME/") -> File(home, raw.removePrefix("\$HOME/")).path
            else -> raw
        }.replace("\\\"", "\"").replace("\\\\", "\\")
        if (path.isBlank() || !File(path).isAbsolute || File(path) == home) return null
        return File(path)
    }

    // -------------------------------------------------------------------- reveal --

    /** Ask the file manager (any that implements the freedesktop FileManager1 service) to select [file]. */
    fun showItemsCommand(file: File): List<String> = listOf(
        "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
        "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
        // dbus-send splits array elements at ',' and the file manager wants file:///…; java's URI leaves ',' alone.
        "array:string:file://" + file.toURI().rawPath.replace(",", "%2C"), "string:",
    )

    // ---------------------------------------------------------------- keep awake --

    /** Holds a sleep + idle inhibitor for as long as [pid] lives: `tail --pid` exits when we do, so a crash lets go too. */
    fun inhibitCommand(pid: Long): List<String> = listOf(
        "systemd-inhibit", "--what=sleep:idle", "--who=CrossDrop", "--why=Sending or receiving files", "--mode=block",
        "tail", "--pid=$pid", "-f", "/dev/null",
    )

    // ------------------------------------------------------------------- pickers --

    enum class Pick { FILES, FOLDER }

    /**
     * Picker commands to try in order. KDE sessions prefer kdialog, everything else zenity; the other one stays as a
     * fallback (a GNOME box with kdialog installed, or the reverse). All of them print one absolute path per line.
     */
    fun pickerCommands(kind: Pick, title: String, home: File, currentDesktop: String?): List<List<String>> {
        val zenity = buildList {
            add("zenity"); add("--file-selection"); add("--title=$title")
            if (kind == Pick.FOLDER) add("--directory") else { add("--multiple"); add("--separator=\n") }
            add("--filename=${home.path}/")
        }
        val kdialog = buildList {
            add("kdialog"); add("--title"); add(title)
            if (kind == Pick.FILES) { add("--multiple"); add("--separate-output") }
            add(if (kind == Pick.FOLDER) "--getexistingdirectory" else "--getopenfilename")
            add(home.path)
        }
        val kde = currentDesktop.orEmpty().split(':').any { it.equals("KDE", ignoreCase = true) }
        return if (kde) listOf(kdialog, zenity) else listOf(zenity, kdialog)
    }

    /** What a picker printed -> existing files (one path per line; a picker may add blank lines). */
    fun parsePicked(output: String): List<File> =
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map(::File).filter { it.exists() }.toList()

    // ------------------------------------------------------------ real processes --

    /** Start `xdg-open`; false when it isn't installed (the caller then tries AWT's `Desktop`). */
    fun xdgOpen(target: String): Boolean = runCatching {
        ProcessBuilder("xdg-open", target)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)

    /**
     * Select [file] in the file manager; when no file manager answers on D-Bus, open its folder instead.
     * Runs on its own thread: a `dbus-send` waiting for a file manager to start must not hold up the UI.
     */
    fun reveal(file: File, openFolder: (File) -> Unit) {
        Thread({
            val shown = runCatching {
                val p = ProcessBuilder(showItemsCommand(file))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS).also { if (!it) p.destroyForcibly() } && p.exitValue() == 0
            }.getOrDefault(false)
            if (!shown) openFolder(file.parentFile ?: file)
        }, "crossdrop-reveal").apply { isDaemon = true }.start()
    }

    /**
     * Run a native picker. Returns the chosen files (empty = the user cancelled) or null when no picker is installed
     * or working, so the caller can use a Swing chooser instead.
     *
     * The caller is usually the UI thread. Waiting for the dialog there would freeze the window — and a window that
     * stops answering the window manager's pings gets a "not responding" prompt after a few seconds — so the wait
     * pumps AWT events (a secondary loop) while a helper thread runs the process.
     */
    fun runPicker(commands: List<List<String>>): List<File>? {
        val installed = commands.filter { onPath(it.first()) }
        if (installed.isEmpty()) return null
        return pumpingEvents {
            for (cmd in installed) {
                val p = runCatching {
                    ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                }.getOrNull() ?: continue
                val out = p.inputStream.bufferedReader().readText()
                when (p.waitFor()) {
                    0 -> return@pumpingEvents parsePicked(out)
                    1 -> return@pumpingEvents emptyList()      // "cancel" for both zenity and kdialog
                    else -> continue                           // a crashed or confused picker: try the next one
                }
            }
            null
        }
    }

    internal fun onPath(command: String, path: String? = System.getenv("PATH")): Boolean =
        if ('/' in command) File(command).let { it.isFile && it.canExecute() }       // an explicit path needs no PATH
        else path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }
            .any { File(it, command).let { f -> f.isFile && f.canExecute() } }

    /** Run [work] on a helper thread and wait for it without blocking AWT's event dispatch. */
    private fun <T> pumpingEvents(work: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val returned = AtomicBoolean(false)
        if (!EventQueue.isDispatchThread()) {
            return work()      // nothing to pump: a plain wait is fine
        }
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        Thread({
            result.set(runCatching(work))
            // exit() does nothing until enter() is running; keep trying until enter() has returned.
            while (!returned.get()) { loop.exit(); Thread.sleep(5) }
        }, "crossdrop-picker").apply { isDaemon = true }.start()
        loop.enter()
        returned.set(true)
        return result.get().getOrThrow()
    }
}
