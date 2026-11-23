package com.crossdrop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.security.MessageDigest

/** Text on the clipboard; [secret] when the app that copied it asked clipboard tools to leave it alone (a password manager). */
class ClipRead(val text: String, val secret: Boolean)

/** The computer's clipboard, text only (clipboard sharing with "My devices"). */
interface ClipboardSource {
    /** A number that changes whenever the clipboard does, without reading it; null where there is none (then the text is compared). */
    fun stamp(): Long?
    /** The text on the clipboard; null when it holds no text. Throws when the clipboard is busy (Windows: another app has it open). */
    fun read(): ClipRead?
    fun write(text: String)
}

/**
 * Watches [source] while [active] and reports each new copy to [onCopy] (never what was there when watching began, never a
 * password manager's copy, never text this app wrote itself through [write]). Polls: macOS and Windows have a change
 * counter, so the text is only read after a real copy (newer macOS versions ask the person before an app reads what
 * another app copied); Linux has none, so the text is read each time.
 */
class ClipboardWatcher(
    private val source: ClipboardSource,
    private val active: StateFlow<Boolean>,
    private val onCopy: (String) -> Unit,
    private val intervalMs: Long = 700,
) {
    private val lock = Any()
    private var lastStamp: Long? = null
    private var lastHash: String? = null

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        var watching = false
        while (isActive) {
            if (!active.value) {
                watching = false
                active.first { it }
            }
            if (!watching) { baseline(); watching = true }
            delay(intervalMs)
            if (active.value) runCatching { poll() }
        }
    }

    /** Put text that arrived from another device on the clipboard, without reporting it back as a copy. */
    fun write(text: String) {
        synchronized(lock) {
            lastHash = hash(text)
            source.write(text)
            lastStamp = runCatching { source.stamp() }.getOrNull()
        }
    }

    /** What is on the clipboard when watching begins was copied before: it isn't shared. */
    private fun baseline() = synchronized(lock) {
        val s = runCatching { source.stamp() }.getOrNull()
        lastStamp = s
        // With a counter there is no need to read anything (no prompt on newer macOS); without one, remember the text.
        lastHash = if (s != null) null else runCatching { source.read()?.text?.let(::hash) }.getOrNull()
    }

    private fun poll() {
        val copied = synchronized(lock) {
            val s = source.stamp()
            if (s != null && s == lastStamp) return
            val r = source.read()          // throws while busy: the counter isn't taken, so the next tick tries again
            lastStamp = s
            val h = r?.text?.let(::hash)
            if (r == null || h == lastHash) return
            lastHash = h
            r.takeUnless { it.secret }?.text
        } ?: return
        onCopy(copied)
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** The real clipboard, through AWT, plus each system's own change counter and "don't share this" markers. */
object SystemClipboard : ClipboardSource {
    private val clipboard by lazy { Toolkit.getDefaultToolkit().systemClipboard }

    override fun stamp(): Long? = when (Platform.current) {
        Platform.MAC -> MacPasteboard.changeCount()
        Platform.WINDOWS -> WinClipboard.sequence()
        else -> null
    }

    override fun read(): ClipRead? {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        val text = clipboard.getData(DataFlavor.stringFlavor) as? String ?: return null
        return ClipRead(text, secret = marked())
    }

    override fun write(text: String) {
        // Windows refuses while another app holds the clipboard open; it lets go within moments.
        repeat(5) { attempt ->
            try { clipboard.setContents(StringSelection(text), null); return } catch (e: IllegalStateException) {
                if (attempt == 4) throw e
                Thread.sleep(60)
            }
        }
    }

    /** The copying app said "keep this off clipboard histories and other devices". */
    private fun marked(): Boolean = runCatching {
        when (Platform.current) {
            Platform.MAC -> MacPasteboard.hasAnyType(MAC_SECRET_TYPES)
            Platform.WINDOWS -> WinClipboard.hasAnyFormat(WINDOWS_SECRET_FORMATS)
            Platform.LINUX -> LinuxSecretHint.present(clipboard)
            else -> false
        }
    }.getOrDefault(false)

    /** nspasteboard.org markers: 1Password, Bitwarden, KeePassXC and others put these on passwords and one-time codes. */
    private val MAC_SECRET_TYPES = listOf(
        "org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType", "org.nspasteboard.AutoGeneratedType",
    )

    /**
     * Windows: what password managers add so clipboard history and cloud clipboard skip a copy. `CanUploadToCloudClipboard`
     * only ever appears to say "don't" (0): uploading is the default.
     */
    private val WINDOWS_SECRET_FORMATS = listOf(
        "ExcludeClipboardContentFromMonitorProcessing", "CanUploadToCloudClipboard", "Clipboard Viewer Ignore",
    )
}

/** NSPasteboard through the Objective-C runtime (JNA): the change counter and the declared types, neither of which reads the content. */
private object MacPasteboard {
    private val send by lazy { com.sun.jna.Function.getFunction("objc", "objc_msgSend") }
    private fun fn(name: String) = com.sun.jna.Function.getFunction("objc", name)
    private fun cls(name: String) = fn("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String) = fn("sel_registerName").invokePointer(arrayOf(name))
    private fun general() = send.invokePointer(arrayOf(cls("NSPasteboard"), sel("generalPasteboard")))

    fun changeCount(): Long = send.invokeLong(arrayOf(general(), sel("changeCount")))

    fun hasAnyType(names: List<String>): Boolean {
        val pool = fn("objc_autoreleasePoolPush").invokePointer(arrayOf())
        try {
            val types = send.invokePointer(arrayOf(general(), sel("types"))) ?: return false
            return names.any { name ->
                val s = send.invokePointer(arrayOf(cls("NSString"), sel("stringWithUTF8String:"), name))
                // BOOL comes back in the low byte only
                (send.invokeInt(arrayOf(types, sel("containsObject:"), s)) and 0xff) != 0
            }
        } finally {
            fn("objc_autoreleasePoolPop").invokeVoid(arrayOf(pool))
        }
    }
}

private interface User32Clipboard : com.sun.jna.win32.StdCallLibrary {
    fun GetClipboardSequenceNumber(): Int
    fun RegisterClipboardFormatW(name: com.sun.jna.WString): Int
    fun IsClipboardFormatAvailable(format: Int): Boolean
}

/** Win32 clipboard bits AWT doesn't expose. Neither call opens the clipboard. */
private object WinClipboard {
    private val api: User32Clipboard? by lazy {
        runCatching { com.sun.jna.Native.load("user32", User32Clipboard::class.java) }
            .onFailure { Log.w("Clipboard", "user32 unavailable", it) }.getOrNull()
    }
    private val formats = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun sequence(): Long? = api?.GetClipboardSequenceNumber()?.toLong()?.and(0xffffffffL)

    fun hasAnyFormat(names: List<String>): Boolean {
        val u = api ?: return false
        return names.any { n ->
            val id = formats.getOrPut(n) { u.RegisterClipboardFormatW(com.sun.jna.WString(n)) }
            id != 0 && u.IsClipboardFormatAvailable(id)
        }
    }
}

/**
 * KDE's convention (Klipper, KeePassXC): a `x-kde-passwordManagerHint` target saying `secret`. AWT only lists targets it
 * has a flavor for, so one is mapped to that target name. Best effort: not verified on a Linux desktop.
 */
private object LinuxSecretHint {
    private const val NATIVE = "x-kde-passwordManagerHint"
    private val flavor by lazy {
        DataFlavor("application/x-crossdrop-password-hint;class=java.io.InputStream", "password hint").also { f ->
            (java.awt.datatransfer.SystemFlavorMap.getDefaultFlavorMap() as? java.awt.datatransfer.SystemFlavorMap)?.let {
                it.addUnencodedNativeForFlavor(f, NATIVE)
                it.addFlavorForUnencodedNative(NATIVE, f)
            }
        }
    }

    fun present(clipboard: java.awt.datatransfer.Clipboard): Boolean = clipboard.isDataFlavorAvailable(flavor)
}
