package com.crossdrop.core.contacts

import com.crossdrop.core.protocol.MAX_INLINE_TEXT

/**
 * Sending a few contact cards on their own, outside a "Switch phones" move: one `.vcf` file in an
 * offer of kind `contact`, plus a photo-less copy inline so the receiver can show who it is before
 * accepting. Older receivers simply get the `.vcf` file.
 */
object ContactShare {
    /** A shared `.vcf` bigger than this goes as a plain file — that's a backup, not a few cards. */
    const val MAX_CARD_FILE = 8L * 1024 * 1024

    private val vcardMimes = setOf("text/vcard", "text/x-vcard", "text/directory")

    fun isVCard(name: String?, mime: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (m != null && m in vcardMimes) return true
        val n = name?.trim()?.lowercase() ?: return false
        return n.endsWith(".vcf") || n.endsWith(".vcard")
    }

    /** The prompt's copy of [vcf]: the cards without photos, or null when even that is too long. */
    fun preview(vcf: String): String? =
        stripPhotos(vcf).takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_INLINE_TEXT }

    private val binaryProps = setOf("PHOTO", "LOGO", "SOUND", "KEY")

    /** Drops PHOTO, LOGO, SOUND and KEY properties together with their folded continuation lines. */
    fun stripPhotos(vcf: String): String {
        val out = StringBuilder(minOf(vcf.length, MAX_INLINE_TEXT * 2))
        var skipping = false
        for (line in vcf.split('\n')) {
            val continuation = line.startsWith(" ") || line.startsWith("\t")
            if (continuation) {
                if (!skipping) out.append(line).append('\n')
                continue
            }
            val name = line.substringBefore(':').substringBefore(';').substringAfterLast('.').trim().uppercase()
            skipping = name in binaryProps
            if (!skipping) out.append(line).append('\n')
        }
        // split() leaves one element after a trailing newline; don't grow the text by one.
        if (!vcf.endsWith('\n') && out.isNotEmpty()) out.setLength(out.length - 1)
        return out.toString()
    }

    /** "Jane Doe.vcf", or "3 contacts.vcf". */
    fun fileName(contacts: List<VContact>): String {
        val base = if (contacts.size == 1) safeName(contacts.first().displayName) else "${contacts.size} contacts"
        return "$base.vcf"
    }

    /** What the prompt and history call these cards: "Jane Doe", "Jane Doe and 2 others". */
    fun title(contacts: List<VContact>): String = when (contacts.size) {
        0 -> "Contact"
        1 -> contacts.first().displayName
        2 -> "${contacts[0].displayName} and ${contacts[1].displayName}"
        else -> "${contacts.first().displayName} and ${contacts.size - 1} others"
    }

    private fun safeName(s: String): String =
        s.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trim('.').take(60).ifBlank { "Contact" }
}
