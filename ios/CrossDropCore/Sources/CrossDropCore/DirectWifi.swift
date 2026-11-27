import Foundation

/// The CrossDrop Wi-Fi: the network an Android phone makes for "Connect directly" (a Wi-Fi Direct group). Its name and
/// password are the same on every phone, so CrossDrop on an iPhone joins it in one tap without a code. The Swift twin
/// of Kotlin `core/connect/DirectWifi.kt`: keep the values and rules in step.
///
/// The password is public (it is in this open-source code); it only keeps the network from being open. Transfers over
/// it are end-to-end encrypted and accepted on the receiving device as usual.
public enum DirectWifi {
    /// Android requires a Wi-Fi Direct network's name to start with "DIRECT-" and two letters or digits.
    public static let ssid = "DIRECT-CD-CrossDrop"
    public static let password = "crossdrop123"
    /// The address Android gives the phone that owns a Wi-Fi Direct group (and hands out the rest of its /24).
    public static let host = "192.168.49.1"
    /// While the network is on, the phone that made it also listens here, so a device that joined without a code
    /// knows where to announce itself.
    public static let port = 47474

    /// Whether `name` is a CrossDrop Wi-Fi.
    public static func isCrossDropWifi(_ name: String?) -> Bool {
        guard var n = name?.trimmingCharacters(in: .whitespaces) else { return false }
        if n.count >= 2, n.hasPrefix("\""), n.hasSuffix("\"") { n = String(n.dropFirst().dropLast()) }
        return n == ssid
    }

    /// What a one-tap join uses: the network, and where the phone that made it listens.
    public static func joinCode(host: String = host) -> JoinCode {
        JoinCode(ssid: ssid, password: password, security: "WPA", host: host, port: port)
    }

    /// Where the phone that made the network most likely is, from this device's own address on it: the `.1` of its
    /// /24 (192.168.49.1 on Android). Without a usable private IPv4 address, `host`.
    public static func hostFor(_ localIPv4: String?) -> String {
        guard let parts = localIPv4?.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false),
              parts.count == 4 else { return host }
        var bytes: [Int] = []
        for p in parts {
            guard let b = Int(p), (0...255).contains(b) else { return host }
            bytes.append(b)
        }
        let isPrivate = bytes[0] == 10 || (bytes[0] == 172 && (16...31).contains(bytes[1])) || (bytes[0] == 192 && bytes[1] == 168)
        guard isPrivate, bytes[3] != 1 else { return host }
        return "\(bytes[0]).\(bytes[1]).\(bytes[2]).1"
    }

    /// Whether `localIPv4` is on Android's Wi-Fi Direct subnet, i.e. this device is on a CrossDrop Wi-Fi now.
    public static func onGroupSubnet(_ localIPv4: String?) -> Bool {
        guard let ip = localIPv4 else { return false }
        return ip.hasPrefix("192.168.49.") && ip != host
    }
}
