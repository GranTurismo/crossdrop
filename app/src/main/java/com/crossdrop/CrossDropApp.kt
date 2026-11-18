package com.crossdrop

import android.app.Application
import com.crossdrop.di.AppContainer

class CrossDropApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The UI language follows the phone's language list (and Android 13+'s per-app language).
        // The in-app language list ("Automatic" or a language) is stored here and wins over the phone's language.
        val prefs = getSharedPreferences("crossdrop", MODE_PRIVATE)
        com.crossdrop.core.i18n.I18n.install(
            source = { lang -> runCatching { assets.open("l10n/$lang.json").bufferedReader().use { it.readText() } }.getOrNull() },
            preferred = { resources.configuration.locales.toLanguageTags() },
            savedChoice = prefs.getString("language", null),
            persist = { prefs.edit().putString("language", it ?: "auto").apply() },
        )
        com.crossdrop.ui.language.LanguageReactivity.install()
        container = AppContainer(this)
        container.start()
    }
}
