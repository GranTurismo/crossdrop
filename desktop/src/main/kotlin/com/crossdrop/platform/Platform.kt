package com.crossdrop.platform

import com.crossdrop.core.i18n.tr

/**
 * Which desktop OS we are running on, plus the handful of words the UI changes
 * with it ("Mac" vs "PC", "Finder" vs "File Explorer"). Everything that has to
 * behave differently per OS asks here instead of sniffing `os.name` itself, so
 * the decision is testable ([of] takes the raw property value).
 */
enum class Platform {
    MAC, WINDOWS, OTHER;

    // The words below are read at call time (never cached) so they follow the UI language.

    /** What to call this machine in prose: "Turn this Mac into a drive". */
    val computerNoun: String
        get() = when (this) {
            MAC -> "Mac"
            WINDOWS -> tr("PC")
            OTHER -> tr("computer")
        }

    /** The OS file manager, for "Show in …" buttons. */
    val fileManager: String
        get() = when (this) {
            MAC -> "Finder"
            WINDOWS -> tr("File Explorer")
            OTHER -> tr("file manager")
        }

    /** Where the app lives while its window is closed. */
    val trayName: String
        get() = when (this) {
            MAC -> tr("menu bar")
            else -> tr("system tray")
        }

    companion object {
        fun of(osName: String?): Platform {
            val n = osName.orEmpty()
            return when {
                n.startsWith("Mac", ignoreCase = true) -> MAC
                n.startsWith("Windows", ignoreCase = true) -> WINDOWS
                else -> OTHER
            }
        }

        val current: Platform by lazy { of(System.getProperty("os.name")) }
        val isMac: Boolean get() = current == MAC
        val isWindows: Boolean get() = current == WINDOWS
    }
}
