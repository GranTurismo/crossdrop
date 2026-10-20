import Foundation
import Darwin

/// Best IPv4 to put inside a QR share URL: the Wi-Fi interface's address, falling back to a
/// hotspot bridge or any private-range address — never loopback, link-local or CGNAT space.
public enum LocalIp {
    public static func wifiIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var found: [(name: String, ip: String, octets: [UInt8])] = []
        var cur: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cur {
            defer { cur = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let octets: [UInt8] = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                var a = $0.pointee.sin_addr.s_addr
                return withUnsafeBytes(of: &a) { Array($0) }
            }
            // 169.254/16 link-local, 100.64/10 carrier-grade NAT: not reachable by a phone on the LAN.
            if octets[0] == 169 && octets[1] == 254 { continue }
            if octets[0] == 100 && (64...127).contains(octets[1]) { continue }
            found.append((String(cString: p.pointee.ifa_name), octets.map(String.init).joined(separator: "."), octets))
        }

        func isPrivate(_ o: [UInt8]) -> Bool {
            o[0] == 10 || (o[0] == 172 && (16...31).contains(o[1])) || (o[0] == 192 && o[1] == 168)
        }
        return found.first { $0.name == "en0" }?.ip
            ?? found.first { $0.name.hasPrefix("en") }?.ip
            ?? found.first { $0.name.hasPrefix("ap") || $0.name.hasPrefix("bridge") }?.ip
            ?? found.first { isPrivate($0.octets) && !$0.name.hasPrefix("utun") }?.ip
    }
}
