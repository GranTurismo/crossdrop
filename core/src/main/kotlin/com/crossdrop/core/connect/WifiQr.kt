package com.crossdrop.core.connect

import com.crossdrop.core.Endpoint

/**
 * What a scanned Wi-Fi code says: the network, and (when CrossDrop made the code) where the phone that
 * made it is listening, so the scanner can connect straight to it without waiting for discovery.
 */
data class JoinCode(
    val ssid: String,
    val password: String?,
    /** "WPA" (WPA2, WPA3 transition), "SAE" (WPA3 only), "WEP" or "nopass". */
    val security: String,
    val hidden: Boolean = false,
    /** The host's transfer listener on that network; null for an ordinary router code. */
    val host: Endpoint? = null,
    /** The host's per-launch id, so its radar node merges with any mDNS sighting of it. */
    val instanceId: String? = null,
)

/**
 * The text of a "join this Wi-Fi" QR code (`WIFI:T:WPA;S:name;P:password;;`). Phone cameras on Android
 * and iPhone read it and offer to join, so the other person needs no app and types nothing.
 *
 * CrossDrop adds one field of its own, `CD:<ipv4>/<port>/<instance id>;`, before the closing `;;`. Readers
 * look up the fields they know by key and skip the rest (ZXing, Android's own parser), so phone cameras
 * still join; CrossDrop's scanner uses it to reach the host directly.
 */
object WifiQr {
    /** [security] is "WPA" (WPA2 and WPA3-transition), "SAE" (WPA3 only) or "nopass". */
    fun payload(
        ssid: String,
        password: String?,
        security: String = "WPA",
        host: String? = null,
        port: Int = 0,
        instanceId: String? = null,
    ): String = buildString {
        val open = password.isNullOrEmpty() || security == "nopass"
        append("WIFI:T:").append(if (open) "nopass" else security).append(";S:").append(field(ssid)).append(';')
        if (!open) append("P:").append(field(password!!)).append(';')
        if (host != null && IPV4.matches(host) && port in 1..65535) {
            append("CD:").append(host).append('/').append(port)
            if (instanceId != null && HEX_ID.matches(instanceId)) append('/').append(instanceId)
            append(';')
        }
        append(';')
    }

    /** Reads any `WIFI:` code (CrossDrop's or a router's); null when [text] isn't one. */
    fun parse(text: String): JoinCode? {
        val t = text.trim()
        if (!t.regionMatches(0, "WIFI:", 0, 5, ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        for (f in split(t.substring(5))) {
            val colon = f.indexOf(':').takeIf { it > 0 } ?: continue
            fields.putIfAbsent(f.substring(0, colon).uppercase(), f.substring(colon + 1))
        }
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = fields["P"]?.takeIf { it.isNotEmpty() }
        val security = when (fields["T"]?.uppercase()) {
            null, "" -> if (password == null) "nopass" else "WPA"
            "NOPASS" -> "nopass"
            "WPA", "WPA2", "WPA/WPA2" -> "WPA"
            "SAE", "WPA3" -> "SAE"
            "WEP" -> "WEP"
            else -> "WPA"
        }
        var host: Endpoint? = null
        var id: String? = null
        fields["CD"]?.split('/')?.let { parts ->
            val ip = parts.getOrNull(0)
            val port = parts.getOrNull(1)?.toIntOrNull()
            if (ip != null && IPV4.matches(ip) && port != null && port in 1..65535) {
                host = Endpoint(ip, port)
                id = parts.getOrNull(2)?.takeIf { HEX_ID.matches(it) }
            }
        }
        return JoinCode(ssid, if (security == "nopass") null else password, security, fields["H"].equals("true", true), host, id)
    }

    /**
     * Splits the body at unescaped `;`, undoing `\` escapes, and drops the quotes around a value that was
     * written in quotes (hex-looking passwords).
     */
    private fun split(body: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quotedStart = -1        // where the current value's unescaped opening quote sits
        var lastQuoteEnd = false    // the last char appended was an unescaped quote
        var i = 0
        fun flush() {
            var s = cur.toString()
            val colon = s.indexOf(':')
            if (quotedStart == colon + 1 && lastQuoteEnd && s.length - quotedStart >= 2) {
                s = s.substring(0, quotedStart) + s.substring(quotedStart + 1, s.length - 1)
            }
            if (s.isNotEmpty()) out += s
            cur.setLength(0); quotedStart = -1; lastQuoteEnd = false
        }
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { cur.append(body[i + 1]); lastQuoteEnd = false; i += 2; continue }
                c == ';' -> flush()
                c == '"' -> {
                    if (quotedStart < 0 && cur.lastOrNull() == ':' && cur.indexOf(":") == cur.length - 1) quotedStart = cur.length
                    cur.append(c); lastQuoteEnd = true
                }
                else -> { cur.append(c); lastQuoteEnd = false }
            }
            i++
        }
        flush()
        return out
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

    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}""")
    private val HEX_ID = Regex("[0-9a-fA-F]{8,64}")
}
