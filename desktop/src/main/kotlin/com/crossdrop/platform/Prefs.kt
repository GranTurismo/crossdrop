package com.crossdrop.platform

import java.io.File
import java.util.Properties

/** Tiny persistent key/value store (the SharedPreferences of the desktop app). */
class Prefs(private val file: File = File(AppDirs.support, "settings.properties")) {
    private val props = Properties().apply {
        runCatching { if (file.exists()) file.inputStream().use(::load) }
    }

    @Synchronized fun getString(key: String): String? = props.getProperty(key)
    @Synchronized fun getBoolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    @Synchronized fun putString(key: String, value: String) { props.setProperty(key, value); save() }
    @Synchronized fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "CrossDrop settings") }
            tmp.renameTo(file)
        }
    }
}
