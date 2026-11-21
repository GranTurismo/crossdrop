package com.crossdrop.core.protocol

import java.text.Normalizer

/**
 * Turning a name chosen by another device into one that is safe to create as a file here. The name is untrusted text.
 */
object FileNames {
    /** At most this many characters, and at most [MAX_BYTES] UTF-8 bytes (file systems count bytes: 255, and room for " (n)"). */
    const val MAX_CHARS = 96
    const val MAX_BYTES = 200

    private val unsafe = Regex("[^\\p{L}\\p{M}\\p{N}\\p{So}._ -]")

    /**
     * What survives: letters, marks, digits and symbols (emoji) of every script, plus `.`, `_`, space and `-`, normalised to NFC
     * (an NFD name from a Mac and its NFC twin are the same name). Everything else, which includes path separators, `:`
     * (an NTFS alternate data stream), wildcard and shell characters, control characters and the invisible direction
     * overrides used to disguise extensions, becomes `_`. A name never ends in a dot or a space (Windows drops them, so
     * `a.` would alias `a`), is never empty, and never has a directory part.
     */
    fun sanitize(name: String): String {
        fun tidy(s: String) = s.trimEnd('.', ' ').ifBlank { "file" }
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = Normalizer.normalize(base, Normalizer.Form.NFC).replace(unsafe, "_")
        return tidy(tidy(clip(tidy(cleaned))))
    }

    private fun clip(s: String): String {
        var out = if (s.length > MAX_CHARS) s.substring(0, MAX_CHARS) else s
        while (out.toByteArray(Charsets.UTF_8).size > MAX_BYTES) out = out.dropLast(1)
        // never cut a surrogate pair in half
        if (out.isNotEmpty() && Character.isHighSurrogate(out.last())) out = out.dropLast(1)
        return out
    }
}

/**
 * Keeps two different incoming files from being given the same place. Names are cleaned (so two different originals can
 * come out equal, `a:b` and `a_b`), and the delta path writes straight over whatever it finds at its target: without
 * this, the second file of an offer would silently replace the first. The first original to claim a place keeps it; a
 * different original that wants it gets `name (1)`, and every later question about either gets the same answer.
 * Memory only, bounded.
 */
class NameClaims(private val max: Int = 4_096) {
    private val byOriginal = LinkedHashMap<String, String>()
    private val owner = HashMap<String, String>()

    /**
     * The place for [original] (what the sender called it: folder and name), preferring [wanted]. [taken] says whether a place
     * is already occupied by something that must not be replaced (the person's own file).
     */
    @Synchronized
    fun claim(original: String, wanted: String, taken: (String) -> Boolean = { false }): String {
        byOriginal[original]?.let { known ->
            if (!taken(known)) return known
            // Something that is not ours sits there now (the person replaced or edited the file): a new place.
            byOriginal.remove(original); owner.remove(known)
        }
        var place = wanted
        var n = 1
        while (owner[place].let { it != null && it != original } || taken(place)) place = numbered(wanted, n++)
        byOriginal[original] = place
        owner[place] = original
        while (byOriginal.size > max) {
            val eldest = byOriginal.entries.first()
            byOriginal.remove(eldest.key); owner.remove(eldest.value)
        }
        return place
    }

    companion object {
        /** `a.txt` → `a (1).txt`; `a` → `a (1)`; only the last path segment changes. */
        fun numbered(path: String, n: Int): String {
            val slash = path.lastIndexOf('/')
            val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
            val name = path.substring(slash + 1)
            val dot = name.lastIndexOf('.')
            return dir + if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }
    }
}
