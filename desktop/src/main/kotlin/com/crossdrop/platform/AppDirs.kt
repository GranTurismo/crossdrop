package com.crossdrop.platform

import java.io.File

/**
 * Where CrossDrop keeps its state, following each OS's convention:
 * macOS `~/Library/Application Support/CrossDrop`, Windows
 * `%LOCALAPPDATA%\CrossDrop` (local, not roaming — inbox files and the vault
 * must not follow the user's profile to other PCs), and a dot-directory under
 * the home folder elsewhere (dev only).
 */
object AppDirs {
    val isMac: Boolean get() = Platform.isMac

    /** Pure path rule behind [support], so every OS's answer is unit-testable. */
    internal fun baseDir(platform: Platform, home: File, localAppData: String?): File = when (platform) {
        Platform.MAC -> File(home, "Library/Application Support/CrossDrop")
        Platform.WINDOWS -> File(
            localAppData?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path,
            "CrossDrop",
        )
        Platform.OTHER -> File(home, ".crossdrop")
    }

    val support: File by lazy {
        val home = File(System.getProperty("user.home"))
        val base = System.getProperty("crossdrop.home")?.let(::File)
            ?: baseDir(Platform.current, home, System.getenv("LOCALAPPDATA"))
        base.apply { mkdirs() }
    }

    val inbox: File get() = File(support, "inbox").apply { mkdirs() }
    val vault: File get() = File(support, "vault").apply { mkdirs() }
    val driveStaging: File get() = File(support, "drive-staging").apply { mkdirs() }
}
