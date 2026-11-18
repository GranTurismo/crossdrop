import Foundation

/// Which interface addresses mean "this device is on a network other devices could share". Kept apart from
/// the `getifaddrs` loop in the app so the rule can be tested. Mirrors the intent of the Android/desktop checks:
/// a network counts even with no internet and even when it hands out public or IPv6-only addresses (some
/// campuses and carriers do); only unusable addresses don't.
public enum LocalAddress {
    /// `host` is the IPv4 address as a host-order integer (192.168.1.5 = 0xC0A80105).
    public static func isShareableIPv4(_ host: UInt32) -> Bool {
        let a = host >> 24, b = (host >> 16) & 0xFF
        if host == 0 { return false }                  // unspecified
        if a == 127 { return false }                   // loopback
        if a == 169 && b == 254 { return false }       // link-local: what an interface gets when DHCP failed
        if a >= 224 { return false }                   // multicast / reserved
        return true
    }

    /// `bytes` are the 16 bytes of the IPv6 address.
    public static func isShareableIPv6(_ bytes: [UInt8]) -> Bool {
        guard bytes.count == 16 else { return false }
        if bytes.allSatisfy({ $0 == 0 }) { return false }                                   // ::
        if bytes[0..<15].allSatisfy({ $0 == 0 }) && bytes[15] == 1 { return false }         // ::1
        if bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80 { return false }                   // fe80::/10 link-local
        if bytes[0] == 0xFF { return false }                                                // multicast
        return true                                                                         // global or unique-local
    }
}
