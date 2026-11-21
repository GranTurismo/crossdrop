package com.crossdrop.core.transfer

import com.crossdrop.core.Platforms
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.protocol.ProtocolException
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a transfer stopped, in terms a person can act on. */
enum class FailureKind {
    /** Nothing answered: the other device isn't running CrossDrop, is asleep, or is on another network. */
    UNREACHABLE,
    /** It answered, then went away mid-way. */
    CONNECTION_LOST,
    /** The bytes that arrived did not match (BLAKE3/SHA-256 check). */
    INTEGRITY,
    /** This device's storage is full. */
    DISK_FULL,
    /** The file to send could not be read (moved, deleted, permission withdrawn). */
    SOURCE_UNREADABLE,
    /** The other side did not speak our protocol (an older build, or not CrossDrop at all). */
    INCOMPATIBLE,
    /** Anything else: the raw message is all there is. */
    OTHER,
}

/**
 * Turns the exception a transfer died with into a plain-language [Advice]: what happened, and what to try.
 * The raw message (often "Connection reset") stays in the logs; people see this.
 */
object FailureAdvice {
    /** [title] says what happened; [hint] (when there is one) says what to try. */
    data class Advice(val kind: FailureKind, val title: String, val hint: String?)

    /**
     * Classify by the exception chain. [explicit] wins when the engine already knows (an integrity
     * mismatch has no exception). [connected] is whether the secure channel was up when it failed:
     * a timeout before that means nothing was listening, after that means the other side vanished.
     */
    fun kindOf(cause: Throwable?, explicit: FailureKind? = null, connected: Boolean = true, sending: Boolean = true): FailureKind {
        if (explicit != null) return explicit
        val chain = generateSequence(cause) { it.cause?.takeIf { c -> c !== it } }.take(6).toList()
        if (chain.isEmpty()) return FailureKind.OTHER
        if (chain.any { diskFull(it) }) return FailureKind.DISK_FULL
        if (chain.any { it is com.crossdrop.core.protocol.SourceChangedException }) return if (sending) FailureKind.SOURCE_UNREADABLE else FailureKind.OTHER
        if (chain.any { it is ProtocolException && ("hash check" in it.message.orEmpty() || "integrity" in it.message.orEmpty()) }) return FailureKind.INTEGRITY
        // Only a handshake that goes wrong means "the other side doesn't speak our protocol"; the same exception type is
        // also how a corrupt frame or a changed source is reported later on.
        if (!connected && chain.any { it is ProtocolException }) return FailureKind.INCOMPATIBLE
        val network = chain.any {
            it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                it is SocketTimeoutException || it is SocketException || it is EOFException
        }
        if (network) return if (connected) FailureKind.CONNECTION_LOST else FailureKind.UNREACHABLE
        if (sending && chain.any { it is FileNotFoundException || it is SecurityException }) return FailureKind.SOURCE_UNREADABLE
        return FailureKind.OTHER
    }

    /**
     * The advice for a failed session with [peerName] (running on [peerOs] when known). [rawMessage] is kept
     * as the title for [FailureKind.OTHER], so nothing the engine says is ever hidden.
     */
    fun explain(
        cause: Throwable?,
        rawMessage: String?,
        peerName: String,
        peerOs: String? = null,
        explicit: FailureKind? = null,
        connected: Boolean = true,
        sending: Boolean = true,
    ): Advice {
        val kind = kindOf(cause, explicit, connected, sending)
        return when (kind) {
            FailureKind.UNREACHABLE -> Advice(
                kind, tr("Couldn't reach {0}", peerName),
                tr("Open CrossDrop on that device and make sure you're both on the same Wi-Fi."),
            )
            FailureKind.CONNECTION_LOST -> Advice(
                kind, tr("Lost the connection to {0}", peerName),
                if (peerOs == Platforms.IOS) iosNote(peerName)
                else tr("The other device may have gone to sleep, left Wi-Fi or closed CrossDrop. Keep CrossDrop open on both, then try again: what already arrived is kept."),
            )
            FailureKind.INTEGRITY -> Advice(
                kind, tr("The file arrived damaged"),
                tr("Nothing was saved. Try again, ideally with both devices close to the router."),
            )
            FailureKind.DISK_FULL -> Advice(
                kind, tr("Not enough space on this device"),
                tr("Free up some space, then try again. What already arrived is kept."),
            )
            FailureKind.SOURCE_UNREADABLE -> Advice(
                kind, tr("Couldn't read the file"),
                tr("It may have been moved or deleted. Pick it again and retry."),
            )
            FailureKind.INCOMPATIBLE -> Advice(
                kind, tr("Couldn't talk to {0}", peerName),
                tr("Update CrossDrop on both devices and try again."),
            )
            FailureKind.OTHER -> Advice(kind, rawMessage?.takeIf { it.isNotBlank() } ?: tr("transfer failed"), null)
        }
    }

    /** Said before and after a send to an iPhone or iPad: they only receive while CrossDrop is on screen. */
    fun iosNote(peerName: String): String =
        tr("{0} is an iPhone or iPad: it only receives while CrossDrop is open and the screen is on.", peerName)

    /** The hint under a declined send: the receiver tapped No, or the offer timed out unanswered. */
    fun declinedHint(): String =
        tr("They said no, or didn't answer in time. Ask them to look at their screen, then try again.")

    /**
     * Only the system's own wording, at the end of the message: a file's path or name (which the other device chooses) is
     * often part of an I/O error's text, and a folder called "disk full" must not make an unrelated failure read as one.
     */
    private fun diskFull(t: Throwable): Boolean {
        val m = t.message?.lowercase()?.trimEnd() ?: return false
        return listOf("no space left on device", "not enough space on the disk").any { m.endsWith(it) || m.endsWith("$it)") }
    }
}
