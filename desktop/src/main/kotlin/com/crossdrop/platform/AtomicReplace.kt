package com.crossdrop.platform

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Put [tmp] in place of [target], replacing it. `File.renameTo` can't do this on Windows — it refuses
 * when the target exists, so every save after the first one was silently dropped there.
 */
fun replaceFile(tmp: File, target: File) {
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
