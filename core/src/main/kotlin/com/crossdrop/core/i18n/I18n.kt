package com.crossdrop.core.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Translation runtime shared by Android and desktop (the iOS app has a Swift twin, `I18n.swift`).
 *
 * The English text IS the key: `tr("Send to {0}", name)`. A language is one JSON file, `l10n/<lang>.json`
 * (`"English text": "translation"`, or a plural object `{"one": "...", "other": "..."}`), shared by every
 * platform; a missing entry falls back to the English text in the code, so a gap shows English, never a blank.
 * Placeholders are positional: `{0}`, `{1}`, … and `{n}` for a plural's count.
 *
 * The language follows the phone: [preferred] is the user's language list in order and the first one we ship wins.
 */
object I18n {
    /** Languages with a catalog, English (the source) first. Portuguese is Brazilian, Spanish is neutral Latin American. */
    val supported = listOf("en", "ka", "hi", "id", "ar", "fa", "es", "pt", "ru", "tr")

    private val rtl = setOf("ar", "fa")

    /** Where catalogs come from: the JSON text of `l10n/<language>.json`, or null if there is none. */
    fun interface Source { fun read(language: String): String? }

    @Volatile private var source: Source? = null

    /** The user's languages, best first, as comma-separated BCP-47 tags ("ka-GE,en-US"). Set once per platform. */
    @Volatile var preferred: () -> String = { java.util.Locale.getDefault().toLanguageTag() }

    private val catalogs = ConcurrentHashMap<String, Catalog>()
    @Volatile private var lastTags: String? = null
    @Volatile private var lastLanguage: String = "en"

    /** The user's own pick from the in-app language list; null means "automatic" (follow the phone). */
    @Volatile private var choice: String? = null
    @Volatile private var persist: ((String?) -> Unit)? = null

    /**
     * UI hooks. [onRead] runs on every language lookup, so a UI toolkit can subscribe the code that is reading it
     * (Compose reads a snapshot state there, making every `tr()` in a composable refresh by itself); [onChanged]
     * runs after the user picks another language.
     */
    @Volatile var onRead: (() -> Unit)? = null
    @Volatile var onChanged: (() -> Unit)? = null

    /** Native names for the language list: each in its own script, so someone who can't read the current language can still find theirs. */
    val nativeNames: Map<String, String> = linkedMapOf(
        "en" to "English", "ka" to "ქართული", "hi" to "हिन्दी", "id" to "Bahasa Indonesia", "ar" to "العربية",
        "fa" to "فارسی", "es" to "Español", "pt" to "Português (Brasil)", "ru" to "Русский", "tr" to "Türkçe",
    )

    fun install(
        source: Source,
        preferred: (() -> String)? = null,
        savedChoice: String? = null,
        persist: ((String?) -> Unit)? = null,
    ) {
        this.source = source
        if (preferred != null) this.preferred = preferred
        this.choice = savedChoice?.takeIf { it in supported }
        this.persist = persist
        catalogs.clear()
        lastTags = null
    }

    /** Where the pick is stored; platforms that can only reach their settings later (desktop) bind it after [install]. */
    fun setPersist(persist: ((String?) -> Unit)?) { this.persist = persist }

    /** The saved pick (a language code) or null for "automatic". Also a UI subscription point, like [language]. */
    fun choice(): String? { onRead?.invoke(); return choice }

    /** Pick a language from the in-app list, or null for "automatic" (follow the phone). The UI refreshes at once. */
    fun setChoice(code: String?) {
        val next = code?.takeIf { it in supported }
        if (next == choice) return
        choice = next
        lastTags = null
        persist?.invoke(next)
        onChanged?.invoke()
    }

    fun nativeName(code: String): String = nativeNames[code] ?: code

    /** The language the UI is in right now: the user's pick, else the first of the phone's languages we have, else English. */
    fun language(): String {
        onRead?.invoke()
        choice?.let { return it }
        val tags = preferred()
        if (tags == lastTags) return lastLanguage
        val chosen = resolve(tags)
        lastLanguage = chosen
        lastTags = tags
        return chosen
    }

    fun resolve(tags: String): String {
        for (tag in tags.split(',')) {
            val code = normalize(tag) ?: continue
            if (code in supported) return code
        }
        return "en"
    }

    /** "pt-BR" → "pt", legacy "in" → "id", "ka_GE" → "ka"; null for blanks. */
    fun normalize(tag: String): String? {
        val primary = tag.trim().lowercase().split('-', '_').firstOrNull().orEmpty()
        if (primary.isEmpty()) return null
        return when (primary) {
            "in" -> "id"
            "iw" -> "he"
            "fil" -> "tl"
            else -> primary
        }
    }

    fun isRtl(language: String = language()): Boolean = language in rtl

    /** Locale for dates and numbers: the app's language, with ASCII digits in every language (the UI never mixes digit sets). */
    fun locale(language: String = language()): java.util.Locale = java.util.Locale.forLanguageTag("$language-u-nu-latn")

    /** Scripts where Latin typographic tricks (negative letter-spacing, forced capitals) break the text. */
    fun isCursiveOrCaseless(language: String = language()): Boolean = language in setOf("ar", "fa", "hi", "ka")

    // ------------------------------------------------------------------ lookups

    fun tr(text: String, vararg args: Any?): String {
        val found = catalog(language()).strings[text] ?: text
        return format(found, null, args)
    }

    /** [one] and [other] are the English forms; [other] is the catalog key. `{n}` is replaced by [n]. */
    fun plural(n: Int, one: String, other: String, vararg args: Any?): String {
        val lang = language()
        val forms = catalog(lang).plurals[other]
        val category = pluralCategory(lang, n)
        val text = forms?.get(category) ?: forms?.get("other")
            ?: if (lang == "en" || forms == null) (if (n == 1) one else other) else other
        return format(text, n, args)
    }

    fun format(text: String, n: Int?, args: Array<out Any?>): String {
        if (n == null && args.isEmpty()) return text
        if (text.indexOf('{') < 0) return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val close = if (c == '{') text.indexOf('}', i) else -1
            if (close > i) {
                val name = text.substring(i + 1, close)
                val replacement: String? = when {
                    name == "n" && n != null -> n.toString()
                    name.isNotEmpty() && name.all { it.isDigit() } -> args.getOrNull(name.toInt())?.toString()
                    else -> null
                }
                if (replacement != null) { out.append(replacement); i = close + 1; continue }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun catalog(language: String): Catalog {
        if (language == "en") return Catalog.EMPTY            // English is the code itself
        return catalogs.getOrPut(language) {
            val text = runCatching { source?.read(language) }.getOrNull() ?: return@getOrPut Catalog.EMPTY
            runCatching { Catalog.parse(text) }.getOrDefault(Catalog.EMPTY)
        }
    }

    internal class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>) {
        companion object {
            val EMPTY = Catalog(emptyMap(), emptyMap())

            fun parse(json: String): Catalog {
                val root = Json.parseToJsonElement(json).jsonObject
                val strings = HashMap<String, String>()
                val plurals = HashMap<String, Map<String, String>>()
                for ((key, value) in root) {
                    when (value) {
                        is JsonPrimitive -> if (value.isString && value.content.isNotEmpty()) strings[key] = value.content
                        is JsonObject -> plurals[key] = value.mapNotNull { (cat, v) ->
                            (v as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.let { cat to it.content }
                        }.toMap()
                        else -> Unit
                    }
                }
                return Catalog(strings, plurals)
            }
        }
    }

    /** CLDR plural category for a whole number: zero, one, two, few, many or other. */
    fun pluralCategory(language: String, n: Int): String {
        val a = kotlin.math.abs(n)
        return when (language) {
            "ru" -> when {
                a % 10 == 1 && a % 100 != 11 -> "one"
                a % 10 in 2..4 && a % 100 !in 12..14 -> "few"
                else -> "many"
            }
            "ar" -> when {
                a == 0 -> "zero"
                a == 1 -> "one"
                a == 2 -> "two"
                a % 100 in 3..10 -> "few"
                a % 100 in 11..99 -> "many"
                else -> "other"
            }
            "hi", "fa", "pt" -> if (a == 0 || a == 1) "one" else "other"   // 0 counts as one in these languages
            "id" -> "other"
            else -> if (a == 1) "one" else "other"                         // en, es, tr, ka
        }
    }
}

/** Translate [text] (English, with `{0}` placeholders). */
fun tr(text: String, vararg args: Any?): String = I18n.tr(text, *args)

/** Plural: [one] and [other] are the English forms, `{n}` is the count. */
fun trPlural(n: Int, one: String, other: String, vararg args: Any?): String = I18n.plural(n, one, other, *args)
