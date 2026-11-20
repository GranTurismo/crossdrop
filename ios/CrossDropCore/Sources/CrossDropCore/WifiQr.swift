import Foundation

/// What a scanned Wi-Fi code says: the network, and (when CrossDrop made the code) where the phone that made it is
/// listening, so the scanner can connect straight to it without waiting for discovery. Mirrors Kotlin `JoinCode`.
public struct JoinCode: Equatable {
    public var ssid: String
    public var password: String?
    /// "WPA" (WPA2, WPA3 transition), "SAE" (WPA3 only), "WEP" or "nopass".
    public var security: String
    public var hidden: Bool
    /// The host's transfer listener on that network (IPv4); nil for an ordinary router code.
    public var host: String?
    /// The listener's port; 0 when `host` is nil.
    public var port: Int
    /// The host's per-launch id, so its radar node merges with any Bonjour sighting of it.
    public var instanceId: String?

    public init(ssid: String, password: String?, security: String, hidden: Bool = false,
                host: String? = nil, port: Int = 0, instanceId: String? = nil) {
        self.ssid = ssid; self.password = password; self.security = security; self.hidden = hidden
        self.host = host; self.port = port; self.instanceId = instanceId
    }
}

/// The text of a "join this Wi-Fi" QR code (`WIFI:T:WPA;S:name;P:password;;`), the Swift twin of Kotlin `WifiQr`
/// (keep the rules in step). Phone cameras read it and offer to join.
///
/// CrossDrop adds one field of its own, `CD:<ipv4>/<port>/<instance id>;`, before the closing `;;`. Readers look up
/// the fields they know by key and skip the rest, so phone cameras still join; CrossDrop's scanner uses it to reach
/// the host directly.
public enum WifiQr {
    /// `security` is "WPA" (WPA2 and WPA3-transition), "SAE" (WPA3 only) or "nopass".
    public static func payload(ssid: String, password: String?, security: String = "WPA",
                               host: String? = nil, port: Int = 0, instanceId: String? = nil) -> String {
        let open = (password ?? "").isEmpty || security == "nopass"
        var out = "WIFI:T:" + (open ? "nopass" : security) + ";S:" + field(ssid) + ";"
        if !open, let password { out += "P:" + field(password) + ";" }
        if let host, isIPv4(host), (1...65535).contains(port) {
            out += "CD:\(host)/\(port)"
            if let instanceId, isHexId(instanceId) { out += "/" + instanceId }
            out += ";"
        }
        out += ";"
        return out
    }

    /// Reads any `WIFI:` code (CrossDrop's or a router's); nil when `text` isn't one.
    public static func parse(_ text: String) -> JoinCode? {
        let t = Array(text.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars)
        guard t.count >= 5, String(String.UnicodeScalarView(t[0..<5])).uppercased() == "WIFI:" else { return nil }
        var fields: [String: String] = [:]
        for f in split(Array(t[5...])) {
            guard let colon = f.firstIndex(of: ":"), colon > 0 else { continue }
            let key = String(String.UnicodeScalarView(f[0..<colon])).uppercased()
            if fields[key] == nil { fields[key] = String(String.UnicodeScalarView(f[(colon + 1)...])) }
        }
        guard let ssid = fields["S"], !ssid.isEmpty else { return nil }
        let password = fields["P"].flatMap { $0.isEmpty ? nil : $0 }
        let security: String
        switch fields["T"]?.uppercased() ?? "" {
        case "": security = password == nil ? "nopass" : "WPA"
        case "NOPASS": security = "nopass"
        case "WPA", "WPA2", "WPA/WPA2": security = "WPA"
        case "SAE", "WPA3": security = "SAE"
        case "WEP": security = "WEP"
        default: security = "WPA"
        }
        var host: String?
        var port = 0
        var id: String?
        if let cd = fields["CD"] {
            let parts = cd.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
            let ip = parts.first
            let p = parts.count > 1 ? Int(parts[1]) : nil
            if let ip, isIPv4(ip), let p, (1...65535).contains(p) {
                host = ip; port = p
                id = parts.count > 2 && isHexId(parts[2]) ? parts[2] : nil
            }
        }
        return JoinCode(ssid: ssid, password: security == "nopass" ? nil : password, security: security,
                        hidden: fields["H"]?.lowercased() == "true", host: host, port: port, instanceId: id)
    }

    /// Splits the body at unescaped `;`, undoing `\` escapes, and drops the quotes around a value that was written
    /// in quotes (hex-looking passwords). Works on scalars, like the Kotlin version works on chars.
    private static func split(_ body: [Unicode.Scalar]) -> [[Unicode.Scalar]] {
        var out: [[Unicode.Scalar]] = []
        var cur: [Unicode.Scalar] = []
        var quotedStart = -1        // where the current value's unescaped opening quote sits
        var lastQuoteEnd = false    // the last scalar appended was an unescaped quote
        func flush() {
            var s = cur
            let colon = s.firstIndex(of: ":") ?? -1
            if quotedStart == colon + 1 && lastQuoteEnd && s.count - quotedStart >= 2 {
                s = Array(s[0..<quotedStart]) + Array(s[(quotedStart + 1)..<(s.count - 1)])
            }
            if !s.isEmpty { out.append(s) }
            cur.removeAll(); quotedStart = -1; lastQuoteEnd = false
        }
        var i = 0
        while i < body.count {
            let c = body[i]
            if c == "\\" && i + 1 < body.count {
                cur.append(body[i + 1]); lastQuoteEnd = false; i += 2; continue
            } else if c == ";" {
                flush()
            } else if c == "\"" {
                if quotedStart < 0, cur.last == ":", cur.firstIndex(of: ":") == cur.count - 1 { quotedStart = cur.count }
                cur.append(c); lastQuoteEnd = true
            } else {
                cur.append(c); lastQuoteEnd = false
            }
            i += 1
        }
        flush()
        return out
    }

    /// Escapes the characters the format reserves; hex-looking text is quoted so readers don't take it for raw bytes.
    private static func field(_ value: String) -> String {
        var escaped = ""
        for c in value.unicodeScalars {
            if c == "\\" || c == ";" || c == "," || c == ":" || c == "\"" { escaped.append("\\") }
            escaped.unicodeScalars.append(c)
        }
        let looksHex = !value.isEmpty && value.unicodeScalars.allSatisfy(isHexScalar)
        return looksHex ? "\"\(escaped)\"" : escaped
    }

    private static func isHexScalar(_ c: Unicode.Scalar) -> Bool {
        ("0"..."9").contains(c) || ("a"..."f").contains(c) || ("A"..."F").contains(c)
    }

    /// `[0-9a-fA-F]{8,64}`.
    static func isHexId(_ s: String) -> Bool {
        let u = s.unicodeScalars
        return (8...64).contains(u.count) && u.allSatisfy(isHexScalar)
    }

    /// Same pattern as Kotlin's (`(25[0-5]|2[0-4]\d|1?\d?\d)` four times), ASCII digits only like Java's `\d`.
    private static let ipv4 = try! NSRegularExpression(
        pattern: "^(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}$")

    static func isIPv4(_ s: String) -> Bool {
        let r = NSRange(s.startIndex..., in: s)
        return ipv4.firstMatch(in: s, range: r).map { $0.range == r } ?? false
    }
}
