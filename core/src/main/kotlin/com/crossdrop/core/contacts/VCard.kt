package com.crossdrop.core.contacts

import java.io.ByteArrayOutputStream
import java.util.Base64

/** One address-book entry, as much of it as moves cleanly between Android and iOS. */
data class VContact(
    val formattedName: String = "",
    val family: String = "",
    val given: String = "",
    val middle: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val org: String = "",
    val department: String = "",
    val title: String = "",
    val phones: List<Labeled> = emptyList(),
    val emails: List<Labeled> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val urls: List<String> = emptyList(),
    /** "YYYY-MM-DD", or "--MM-DD" without a year. */
    val birthday: String? = null,
    val note: String = "",
    /** JPEG/PNG bytes. */
    val photo: ByteArray? = null,
) {
    data class Labeled(val value: String, val type: String = "")
    data class Address(
        val street: String = "", val city: String = "", val region: String = "",
        val postcode: String = "", val country: String = "", val type: String = "",
    )

    /** What a person would call this contact. */
    val displayName: String
        get() = formattedName.ifBlank {
            listOf(prefix, given, middle, family, suffix).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { org }.ifBlank { phones.firstOrNull()?.value ?: emails.firstOrNull()?.value ?: "" }

    /** Same person, by name and any shared phone or email — how imports skip duplicates. */
    fun sameAs(other: VContact): Boolean {
        if (normName(displayName) != normName(other.displayName)) return false
        val p1 = phones.map { normPhone(it.value) }.filter { it.isNotEmpty() }.toSet()
        val p2 = other.phones.map { normPhone(it.value) }.filter { it.isNotEmpty() }.toSet()
        val e1 = emails.map { it.value.trim().lowercase() }.toSet()
        val e2 = other.emails.map { it.value.trim().lowercase() }.toSet()
        if (p1.isEmpty() && p2.isEmpty() && e1.isEmpty() && e2.isEmpty()) return true
        return p1.intersect(p2).isNotEmpty() || e1.intersect(e2).isNotEmpty()
    }

    companion object {
        fun normName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        /** Last 9 digits: survives country codes and formatting differences. */
        fun normPhone(s: String) = s.filter { it.isDigit() }.takeLast(9)
    }
}

/**
 * vCard reading (2.1, 3.0, 4.0 — what Android, iOS, Google and Outlook export) and writing
 * (3.0, UTF-8, which every one of them imports).
 */
object VCard {

    fun write(contacts: List<VContact>): String = buildString { contacts.forEach { append(write(it)) } }

    fun write(c: VContact): String = buildString {
        fun line(s: String) { append(fold(s)); append("\r\n") }
        line("BEGIN:VCARD")
        line("VERSION:3.0")
        line("N:" + listOf(c.family, c.given, c.middle, c.prefix, c.suffix).joinToString(";") { esc(it) })
        line("FN:" + esc(c.displayName))
        if (c.nickname.isNotBlank()) line("NICKNAME:" + esc(c.nickname))
        if (c.org.isNotBlank() || c.department.isNotBlank())
            line("ORG:" + esc(c.org) + if (c.department.isNotBlank()) ";" + esc(c.department) else "")
        if (c.title.isNotBlank()) line("TITLE:" + esc(c.title))
        c.phones.forEach { line("TEL" + typeParam(it.type) + ":" + esc(it.value)) }
        c.emails.forEach { line("EMAIL" + typeParam(it.type, "INTERNET") + ":" + esc(it.value)) }
        c.addresses.forEach { a ->
            line("ADR" + typeParam(a.type) + ":;;" + listOf(a.street, a.city, a.region, a.postcode, a.country).joinToString(";") { esc(it) })
        }
        c.urls.forEach { line("URL:" + esc(it)) }
        c.birthday?.let { line("BDAY:$it") }
        if (c.note.isNotBlank()) line("NOTE:" + esc(c.note))
        c.photo?.let { p ->
            val kind = if (p.size > 3 && p[0] == 0x89.toByte() && p[1] == 'P'.code.toByte()) "PNG" else "JPEG"
            line("PHOTO;ENCODING=b;TYPE=$kind:" + Base64.getEncoder().encodeToString(p))
        }
        line("END:VCARD")
    }

    private fun typeParam(type: String, extra: String? = null): String {
        val parts = listOfNotNull(extra, type.uppercase().takeIf { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } })
        return if (parts.isEmpty()) "" else ";TYPE=" + parts.joinToString(",")
    }

    /** 75-octet lines, continuations start with a space (RFC 6350 §3.2). */
    private fun fold(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return s
        val out = StringBuilder()
        var lineBytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val n = ch.toByteArray(Charsets.UTF_8).size
            val limit = if (out.isEmpty()) 75 else 74
            if (lineBytes + n > limit) { out.append("\r\n "); lineBytes = 0 }
            out.append(ch); lineBytes += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    // ---------------------------------------------------------------- read --

    fun parse(text: String): List<VContact> {
        val out = ArrayList<VContact>()
        var cur: MutableMap<String, MutableList<Prop>>? = null
        for (raw in unfold(text)) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val prop = parseLine(line) ?: continue
            when {
                prop.name == "BEGIN" && prop.value.equals("VCARD", true) -> cur = LinkedHashMap()
                prop.name == "END" && prop.value.equals("VCARD", true) -> {
                    cur?.let { out += toContact(it) }
                    cur = null
                }
                cur != null -> cur.getOrPut(prop.name) { mutableListOf() } += prop
            }
        }
        return out.filter { it.displayName.isNotBlank() }
    }

    private class Prop(val name: String, val params: Map<String, List<String>>, val value: String) {
        fun types(): List<String> = (params["TYPE"].orEmpty() + params[""].orEmpty()).map { it.uppercase() }
    }

    /** Joins folded lines; vCard 2.1 quoted-printable soft breaks ("=" at line end) too. */
    private fun unfold(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>()
        for (l in lines) {
            val prev = out.lastOrNull()
            when {
                (l.startsWith(" ") || l.startsWith("\t")) && prev != null -> out[out.size - 1] = prev + l.substring(1)
                prev != null && prev.endsWith("=") && isQuotedPrintable(prev) -> out[out.size - 1] = prev.dropLast(1) + l
                else -> out += l
            }
        }
        return out
    }

    private fun isQuotedPrintable(line: String): Boolean =
        line.substringBefore(':').uppercase().contains("QUOTED-PRINTABLE")

    private fun parseLine(line: String): Prop? {
        // Split at the first ':' that isn't inside a quoted parameter value.
        var inQuote = false
        var colon = -1
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') inQuote = !inQuote
            if (ch == ':' && !inQuote) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        var value = line.substring(colon + 1)
        val parts = head.split(';')
        // "item1.TEL" (Apple groups) → "TEL"
        val name = parts[0].substringAfterLast('.').uppercase()
        val params = LinkedHashMap<String, MutableList<String>>()
        for (p in parts.drop(1)) {
            val k = p.substringBefore('=', "").uppercase()
            val v = p.substringAfter('=', p).trim('"')
            // vCard 2.1 bare types: "TEL;CELL;HOME:" — kept under the empty key.
            v.split(',').forEach { params.getOrPut(if (p.contains('=')) k else "") { mutableListOf() } += it }
        }
        val encoding = (params["ENCODING"].orEmpty() + params[""].orEmpty()).map { it.uppercase() }
        val charset = params["CHARSET"]?.firstOrNull()
        if ("QUOTED-PRINTABLE" in encoding) value = decodeQuotedPrintable(value, charset)
        return Prop(name, params, value)
    }

    private fun decodeQuotedPrintable(s: String, charset: String?): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { out.write(b); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        val cs = runCatching { charset?.let { charset(it) } }.getOrNull() ?: Charsets.UTF_8
        return String(out.toByteArray(), cs)
    }

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Splits on unescaped ';' (structured values like N and ADR). */
    private fun components(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { cur.append(c).append(s[i + 1]); i += 2; continue }
            if (c == ';') { out += unesc(cur.toString()); cur.clear() } else cur.append(c)
            i++
        }
        out += unesc(cur.toString())
        return out
    }

    private val labelTypes = setOf("CELL", "MOBILE", "HOME", "WORK", "FAX", "PAGER", "MAIN", "IPHONE", "OTHER")

    private fun label(p: Prop): String =
        p.types().firstOrNull { it in labelTypes }?.let { if (it == "MOBILE" || it == "IPHONE") "CELL" else it } ?: ""

    private fun toContact(m: Map<String, List<Prop>>): VContact {
        val n = m["N"]?.firstOrNull()?.let { components(it.value) }.orEmpty()
        val org = m["ORG"]?.firstOrNull()?.let { components(it.value) }.orEmpty()
        val photo = m["PHOTO"]?.firstOrNull()?.let { p ->
            val v = p.value.trim()
            when {
                v.startsWith("data:", true) -> runCatching { Base64.getMimeDecoder().decode(v.substringAfter(",")) }.getOrNull()
                v.startsWith("http", true) -> null
                else -> runCatching { Base64.getMimeDecoder().decode(v) }.getOrNull()
            }
        }
        return VContact(
            formattedName = m["FN"]?.firstOrNull()?.let { unesc(it.value) }.orEmpty().trim(),
            family = n.getOrElse(0) { "" }.trim(),
            given = n.getOrElse(1) { "" }.trim(),
            middle = n.getOrElse(2) { "" }.trim(),
            prefix = n.getOrElse(3) { "" }.trim(),
            suffix = n.getOrElse(4) { "" }.trim(),
            nickname = m["NICKNAME"]?.firstOrNull()?.let { unesc(it.value) }.orEmpty(),
            org = org.getOrElse(0) { "" },
            department = org.getOrElse(1) { "" },
            title = m["TITLE"]?.firstOrNull()?.let { unesc(it.value) }.orEmpty(),
            phones = m["TEL"].orEmpty().map { VContact.Labeled(unesc(it.value).removePrefix("tel:").trim(), label(it)) }
                .filter { it.value.isNotBlank() },
            emails = m["EMAIL"].orEmpty().map { VContact.Labeled(unesc(it.value).trim(), label(it)) }
                .filter { it.value.isNotBlank() },
            addresses = m["ADR"].orEmpty().map { p ->
                val a = components(p.value)
                VContact.Address(
                    street = listOf(a.getOrElse(1) { "" }, a.getOrElse(2) { "" }).filter { it.isNotBlank() }.joinToString("\n"),
                    city = a.getOrElse(3) { "" }, region = a.getOrElse(4) { "" },
                    postcode = a.getOrElse(5) { "" }, country = a.getOrElse(6) { "" }, type = label(p),
                )
            }.filter { listOf(it.street, it.city, it.region, it.postcode, it.country).any { s -> s.isNotBlank() } },
            urls = m["URL"].orEmpty().map { unesc(it.value).trim() }.filter { it.isNotBlank() },
            birthday = m["BDAY"]?.firstOrNull()?.value?.let(::normBirthday),
            note = m["NOTE"]?.firstOrNull()?.let { unesc(it.value) }.orEmpty(),
            photo = photo,
        )
    }

    /** "1990-05-17", "19900517", "--0517", "1990-05-17T00:00:00Z" → "1990-05-17" / "--05-17". */
    private fun normBirthday(v: String): String? {
        val s = v.trim().substringBefore('T')
        Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").find(s)?.let { val (y, mo, d) = it.destructured; return "$y-$mo-$d" }
        Regex("""^--(\d{2})-?(\d{2})$""").find(s)?.let { val (mo, d) = it.destructured; return "--$mo-$d" }
        return null
    }
}
