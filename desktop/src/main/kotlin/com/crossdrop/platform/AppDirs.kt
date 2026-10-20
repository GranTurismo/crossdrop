package com.crossdrop.platform

import java.io.File

/**
 * Where CrossDrop keeps its state. macOS convention is
 * `~/Library/Application Support/CrossDrop`; other OSes (dev only) fall back
 * to a dot-directory under the home folder.
 */
object AppDirs {
    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    val support: File by lazy {
        val home = File(System.getProperty("user.home"))
        val base = System.getProperty("crossdrop.home")?.let(::File)
            ?: if (isMac) File(home, "Library/Application Support/CrossDrop") else File(home, ".crossdrop")
        base.apply { mkdirs() }
    }

    val inbox: File get() = File(support, "inbox").apply { mkdirs() }
    val vault: File get() = File(support, "vault").apply { mkdirs() }
    val driveStaging: File get() = File(support, "drive-staging").apply { mkdirs() }
}
