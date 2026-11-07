package com.crossdrop.net

import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.vault.VaultFs
import java.io.File

/** Wrap a local [File] as a streamable [OutgoingFile]; null when unreadable or a directory. */
internal fun File.toOutgoingFile(): OutgoingFile? =
    if (!isFile || !canRead()) null
    else OutgoingFile(FileMeta(name, length(), VaultFs.mimeTypeOf(name))) { inputStream() }
