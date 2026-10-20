import Foundation

/// Online-guessing brake shared by every PIN surface (HTTP/WebDAV/Digest and the native AEAD
/// session). The PIN is only 4 digits, so the limiter is what protects it:
///  - per client — 5 misses lock that client for a minute; IPv6 clients are keyed by their /64 (a
///    privacy-address rotation must not reset the counter), IPv4-mapped addresses by the IPv4;
///  - global — 60 misses inside one minute lock *everyone* for a minute, so spreading guesses over
///    many source addresses is capped too.
final class PinThrottle {
    static let maxStrikes = 5
    static let lockoutMs: Int64 = 60_000
    static let globalMaxStrikes = 60
    private static let maxTracked = 512

    private final class Entry { var count = 0; var lockedUntil: Int64 = 0; var lastSeen: Int64 = 0 }
    private let lock = NSLock()
    private var entries: [String: Entry] = [:]
    private var globalMisses: [Int64] = []
    private var globalLockedUntil: Int64 = 0

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Normalised client key: scope id dropped, IPv4-mapped IPv6 → IPv4, other IPv6 → /64.
    static func key(for ip: String) -> String {
        var s = ip
        if let pct = s.firstIndex(of: "%") { s = String(s[..<pct]) }
        let lower = s.lowercased()
        if lower.hasPrefix("::ffff:"), lower.contains(".") { return String(lower.dropFirst(7)) }
        guard lower.contains(":") else { return lower }
        // Expand "::" so the first four hextets are well-defined.
        var head = lower.components(separatedBy: "::")
        var groups: [String]
        if head.count == 2 {
            let left = head[0].isEmpty ? [] : head[0].components(separatedBy: ":")
            let right = head[1].isEmpty ? [] : head[1].components(separatedBy: ":")
            groups = left + [String](repeating: "0", count: max(0, 8 - left.count - right.count)) + right
        } else {
            groups = head.removeFirst().components(separatedBy: ":")
        }
        return groups.prefix(4).map { String($0.drop(while: { $0 == "0" })) }.joined(separator: ":") + "::/64"
    }

    /// Throws 429 while `ip` (or the whole PIN surface) is locked.
    func checkLocked(_ ip: String) throws {
        lock.lock(); defer { lock.unlock() }
        let now = nowMs()
        if now < globalLockedUntil { throw VaultError(429, "too many attempts — wait a minute") }
        if let e = entries[Self.key(for: ip)], now < e.lockedUntil { throw VaultError(429, "too many attempts — wait a minute") }
    }

    /// Records one credential check. Returns `ok`; throws 429 when already locked.
    @discardableResult
    func record(_ ip: String, ok: Bool) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        let now = nowMs()
        if now < globalLockedUntil { throw VaultError(429, "too many attempts — wait a minute") }
        let k = Self.key(for: ip)
        let e: Entry
        if let found = entries[k] { e = found } else {
            if entries.count >= Self.maxTracked { prune(now) }
            e = Entry(); entries[k] = e
        }
        e.lastSeen = now
        if now < e.lockedUntil { throw VaultError(429, "too many attempts — wait a minute") }
        if ok { e.count = 0; return true }
        e.count += 1
        if e.count >= Self.maxStrikes { e.count = 0; e.lockedUntil = now + Self.lockoutMs }
        globalMisses.append(now)
        globalMisses.removeAll { now - $0 > Self.lockoutMs }
        if globalMisses.count >= Self.globalMaxStrikes { globalMisses.removeAll(); globalLockedUntil = now + Self.lockoutMs }
        return false
    }

    private func prune(_ now: Int64) {
        entries = entries.filter { now < $0.value.lockedUntil || now - $0.value.lastSeen < Self.lockoutMs }
        if entries.count >= Self.maxTracked { entries.removeAll() }
    }
}
