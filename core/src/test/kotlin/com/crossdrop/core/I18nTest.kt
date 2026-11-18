package com.crossdrop.core

import com.crossdrop.core.i18n.I18n
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    private val files = mapOf(
        "ka" to """{"Skip":"გამოტოვება","Send to {0}":"გაუგზავნე {0}-ს","{n} files":{"one":"{n} ფაილი","other":"{n} ფაილი"}}""",
        "ru" to """{"{n} files":{"one":"{n} файл","few":"{n} файла","many":"{n} файлов","other":"{n} файла"}}""",
        "ar" to """{"{n} files":{"zero":"لا ملفات","one":"ملف","two":"ملفان","few":"{n} ملفات","many":"{n} ملفًا","other":"{n} ملف"}}""",
    )
    private fun use(tags: String) = I18n.install({ files[it] }, { tags })

    @After fun reset() = I18n.install({ null }, { "en" })

    @Test fun `english is the key and needs no catalog`() {
        use("en-US")
        assertEquals("Skip", tr("Skip"))
        assertEquals("Send to Mia", tr("Send to {0}", "Mia"))
    }

    @Test fun `a translated entry wins and placeholders are filled`() {
        use("ka-GE")
        assertEquals("გამოტოვება", tr("Skip"))
        assertEquals("გაუგზავნე Mia-ს", tr("Send to {0}", "Mia"))
    }

    @Test fun `a missing entry falls back to the english text`() {
        use("ka")
        assertEquals("Something new", tr("Something new"))
        assertEquals("Hi Mia", tr("Hi {0}", "Mia"))
    }

    @Test fun `the first supported language in the list wins`() {
        assertEquals("ru", I18n.resolve("fr-FR,ru-RU,en-US"))
        assertEquals("en", I18n.resolve("en-US,ru-RU"))
        assertEquals("en", I18n.resolve("fr-FR,de-DE"))
        assertEquals("en", I18n.resolve(""))
    }

    @Test fun `regional and legacy tags map to the shipped language`() {
        assertEquals("pt", I18n.resolve("pt-PT"))
        assertEquals("pt", I18n.resolve("pt_BR"))
        assertEquals("id", I18n.resolve("in-ID"))
        assertEquals("ar", I18n.resolve("ar-EG"))
        assertEquals("fa", I18n.resolve("fa-IR"))
        assertEquals("es", I18n.resolve("es-419"))
    }

    @Test fun `english plurals use the code's own forms`() {
        use("en")
        assertEquals("1 file", trPlural(1, "1 file", "{n} files"))
        assertEquals("3 files", trPlural(3, "1 file", "{n} files"))
    }

    @Test fun `russian has three forms`() {
        use("ru")
        assertEquals("1 файл", trPlural(1, "1 file", "{n} files"))
        assertEquals("21 файл", trPlural(21, "1 file", "{n} files"))
        assertEquals("2 файла", trPlural(2, "1 file", "{n} files"))
        assertEquals("24 файла", trPlural(24, "1 file", "{n} files"))
        assertEquals("5 файлов", trPlural(5, "1 file", "{n} files"))
        assertEquals("11 файлов", trPlural(11, "1 file", "{n} files"))
        assertEquals("112 файлов", trPlural(112, "1 file", "{n} files"))
    }

    @Test fun `arabic has six forms`() {
        use("ar")
        assertEquals("لا ملفات", trPlural(0, "1 file", "{n} files"))
        assertEquals("ملف", trPlural(1, "1 file", "{n} files"))
        assertEquals("ملفان", trPlural(2, "1 file", "{n} files"))
        assertEquals("7 ملفات", trPlural(7, "1 file", "{n} files"))
        assertEquals("15 ملفًا", trPlural(15, "1 file", "{n} files"))
        assertEquals("100 ملف", trPlural(100, "1 file", "{n} files"))
    }

    @Test fun `plural categories follow the language`() {
        assertEquals("other", I18n.pluralCategory("id", 1))
        assertEquals("one", I18n.pluralCategory("hi", 0))
        assertEquals("one", I18n.pluralCategory("pt", 0))
        assertEquals("other", I18n.pluralCategory("es", 0))
        assertEquals("one", I18n.pluralCategory("tr", 1))
        assertEquals("other", I18n.pluralCategory("tr", 2))
    }

    @Test fun `a plural with no catalog entry keeps the english forms`() {
        use("ka")
        assertEquals("1 device", trPlural(1, "1 device", "{n} devices"))
        assertEquals("4 devices", trPlural(4, "1 device", "{n} devices"))
    }

    @Test fun `only arabic and persian are right to left`() {
        assertTrue(I18n.isRtl("ar")); assertTrue(I18n.isRtl("fa"))
        assertFalse(I18n.isRtl("he")); assertFalse(I18n.isRtl("ru")); assertFalse(I18n.isRtl("en"))
    }

    @Test fun `braces that are not placeholders survive`() {
        use("en")
        assertEquals("a {b} c", tr("a {b} c", "x"))
        assertEquals("{0}", tr("{0}"))
    }

    @Test fun `the in-app pick beats the phone's language and can return to automatic`() {
        use("ru-RU")
        assertEquals("ru", I18n.language())
        I18n.setChoice("ka")
        assertEquals("ka", I18n.language())
        assertEquals("გამოტოვება", tr("Skip"))
        I18n.setChoice(null)
        assertEquals("ru", I18n.language())
    }

    @Test fun `a pick is stored, announced and survives a restart`() {
        var stored: String? = "unset"
        var changes = 0
        I18n.install({ files[it] }, { "en" }, savedChoice = null, persist = { stored = it })
        I18n.onChanged = { changes++ }
        I18n.setChoice("ar")
        assertEquals("ar", stored)
        assertEquals(1, changes)
        I18n.setChoice("ar")                                   // same pick: nothing to store or announce
        assertEquals(1, changes)
        I18n.install({ files[it] }, { "en" }, savedChoice = stored)
        assertEquals("ar", I18n.language())
        assertEquals("ar", I18n.choice())
        assertTrue(I18n.isRtl())
        I18n.onChanged = null
    }

    @Test fun `an unknown pick is ignored`() {
        use("en")
        I18n.setChoice("xx")
        assertEquals(null, I18n.choice())
        I18n.install({ null }, { "en" }, savedChoice = "xx")
        assertEquals("en", I18n.language())
    }

    @Test fun `lookups tell the ui layer who is reading`() {
        use("en")
        var reads = 0
        I18n.onRead = { reads++ }
        tr("Skip"); I18n.isRtl(); I18n.choice()
        I18n.onRead = null
        assertTrue(reads >= 3)
    }

    @Test fun `every shipped language has a native name`() {
        for (code in I18n.supported) assertTrue(code, I18n.nativeName(code).isNotBlank() && I18n.nativeName(code) != code || code == "en")
    }

    @Test fun `a broken catalog falls back to english instead of crashing`() {
        I18n.install({ "not json" }, { "ka" })
        assertEquals("Skip", tr("Skip"))
    }
}
