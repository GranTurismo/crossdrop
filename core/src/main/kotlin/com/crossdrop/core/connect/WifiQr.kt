package com.crossdrop.core.connect

/**
 * The text of a "join this Wi-Fi" QR code (`WIFI:T:WPA;S:name;P:password;;`). Phone cameras on Android
 * and iPhone read it and offer to join, so the other person needs no app and types nothing.
 */
object WifiQr {
    /** [security] is "WPA" (WPA2 and WPA3-transition), "SAE" (WPA3 only) or "nopass". */
    fun payload(ssid: String, password: String?, security: String = "WPA"): String = buildString {
        val open = password.isNullOrEmpty() || security == "nopass"
        append("WIFI:T:").append(if (open) "nopass" else security).append(";S:").append(field(ssid)).append(';')
        if (!open) append("P:").append(field(password!!)).append(';')
        append(';')
    }

    /** Escapes the characters the format reserves; hex-looking text is quoted so readers don't take it for raw bytes. */
    private fun field(value: String): String {
        val escaped = buildString {
            for (c in value) {
                if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
                append(c)
            }
        }
        val looksHex = value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return if (looksHex) "\"$escaped\"" else escaped
    }
}
