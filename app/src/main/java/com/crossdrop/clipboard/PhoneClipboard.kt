package com.crossdrop.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.pairing.PairingStore
import com.crossdrop.net.ClipShare
import com.crossdrop.net.ClipboardSync
import com.crossdrop.net.CopyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's side of clipboard sharing with "My devices". Android lets an app read the clipboard only while one of its
 * windows has focus (Android 10+), so a copy made here goes out when CrossDrop comes to the front ([onFocus], recent
 * copies only) or when the person taps the Quick Settings tile ([shareNow], via [ClipboardShareActivity]). Copies from
 * the other devices are put on the clipboard straight away ([write]): writing is allowed in the background.
 */
class PhoneClipboard(
    private val app: Context,
    private val scope: CoroutineScope,
    private val pairings: PairingStore,
    /** The shared sync (it needs [write], so it is made after this). */
    private val syncOf: () -> ClipboardSync,
) {
    private val manager get() = app.getSystemService(ClipboardManager::class.java)
    private val prefs by lazy { app.getSharedPreferences("clipboard", Context.MODE_PRIVATE) }

    private val sync get() = syncOf()

    /** A copy from one of "My devices": onto this phone's clipboard, labelled as ours so it is never sent back out. */
    fun write(text: String) {
        manager?.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        debug("put ${text.length} chars from a device on the clipboard")
    }

    /**
     * CrossDrop came to the front: share what was copied since, if it is recent and nobody marked it sensitive (password
     * managers do, Android 13+). Each copy is looked at once (its timestamp is remembered, also across restarts).
     */
    fun onFocus() {
        if (!sync.active.value) return
        val m = manager ?: return
        val desc = runCatching { m.primaryClipDescription }.getOrNull() ?: return
        if (desc.label?.toString() == LABEL) return
        val stamp = desc.timestamp
        if (stamp == prefs.getLong(KEY_LAST, 0L)) return
        prefs.edit { putLong(KEY_LAST, stamp) }
        if (desc.isSensitive() || !desc.isText()) return
        // The clipboard keeps a copy for hours; only one made in the last few minutes is "what I just copied".
        if (stamp > 0 && System.currentTimeMillis() - stamp > AUTO_FRESH_MS) return
        val text = readText(m) ?: return
        debug("on focus: ${text.length} chars -> ${sync.copied(text)}")
    }

    /** The tile: share what is on the clipboard now, whatever it is, and say how it went. Needs window focus. */
    fun shareNow() {
        val m = manager
        val desc = runCatching { m?.primaryClipDescription }.getOrNull()
        val text = if (m != null && desc != null && desc.isText()) readText(m) else null
        desc?.let { d -> prefs.edit { putLong(KEY_LAST, d.timestamp) } }
        if (text == null) {
            toast(if (desc == null) tr("There's nothing on the clipboard") else tr("Only text can be shared"))
            return
        }
        if (pairings.devices.value.none { it.clipboard }) {
            toast(tr("Turn on Share clipboard for one of your devices in Settings → My devices"))
            return
        }
        toast(tr("Sharing your clipboard…"))
        scope.launch {
            val r = sync.shareNow(text)
            debug("tile: ${text.length} chars -> ${r.outcome} ${r.results.values}")
            val name = { id: String -> pairings.get(id)?.name ?: "?" }
            val sent = r.results.filterValues { it == ClipShare.SENT }.keys.map(name)
            val msg = when {
                r.outcome == CopyOutcome.TOO_LONG -> tr("That's too long to share")
                r.outcome == CopyOutcome.EMPTY -> tr("There's nothing on the clipboard")
                sent.isNotEmpty() -> tr("Clipboard sent to {0}", sent.joinToString(", "))
                else -> r.results.entries.firstNotNullOfOrNull { (id, v) ->
                    when (v) {
                        ClipShare.OFF_THERE -> tr("Clipboard sharing is off on {0}", name(id))
                        ClipShare.OLD_VERSION -> tr("Update CrossDrop on {0} first", name(id))
                        else -> null
                    }
                } ?: tr("Couldn't find {0}. Open CrossDrop there, on the same Wi-Fi.", r.results.keys.joinToString(", ") { name(it) })
            }
            withContext(Dispatchers.Main) { toast(msg) }
        }
    }

    private fun readText(m: ClipboardManager): String? = runCatching {
        val item = m.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        // Plain or styled text only: coercing a content URI would read whatever file it points to.
        (item.text ?: item.htmlText?.let { item.coerceToText(app) })?.toString()
    }.getOrNull()

    private fun ClipDescription.isText() =
        hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)

    private fun ClipDescription.isSensitive() = extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    /** Debug builds only, and never the text itself. */
    private fun debug(msg: String) { if (com.crossdrop.BuildConfig.DEBUG) android.util.Log.i("Clipboard", msg) }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** Our clips' label: a copy that came from another device. */
        const val LABEL = "CrossDrop"
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); password managers set it on older versions too. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val KEY_LAST = "last_stamp"
        /** How old a copy may be for opening CrossDrop to share it. */
        const val AUTO_FRESH_MS = 10 * 60_000L
    }
}
