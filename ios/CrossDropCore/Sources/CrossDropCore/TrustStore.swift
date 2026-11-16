import Foundation

/// Memory-only "accept once, then keep receiving" state, both directions — mirrors the Kotlin
/// `TrustStore`. Receiver side: `grant` mints a `TrustGrant` for an ACCEPT, `match` finds the grant a
/// later offer proves it holds (right kind, not lapsed, transfer id not seen before). Sender side:
/// `remember` keeps grants from peers, `credential` hands the matching one to the next offer.
public final class TrustStore: @unchecked Sendable {   // all state behind `lock`
    public struct Granted {
        public let grant: TrustGrant
        let token: [UInt8]
        public let peerName: String
        var lastUsed: Date
        var seen: Set<String> = []
        public var id: String { grant.id }
        public var kind: String { grant.kind }
    }

    private let lock = NSLock()
    private var granted: [String: Granted] = [:]
    private var held: [String: (TrustGrant, Date)] = [:]
    private let now: () -> Date

    public init(now: @escaping () -> Date = Date.init) { self.now = now }

    // MARK: receiver

    public func grant(kind: String, peerName: String, ttl: TimeInterval) -> TrustGrant {
        var token = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, token.count, &token)
        var idBytes = [UInt8](repeating: 0, count: 9)
        _ = SecRandomCopyBytes(kSecRandomDefault, idBytes.count, &idBytes)
        let id = idBytes.map { String(format: "%02x", $0) }.joined()
        let g = TrustGrant(id: id, token: Data(token).base64EncodedString(), kind: kind, ttlMs: Int64(ttl * 1000))
        lock.lock(); granted[id] = Granted(grant: g, token: token, peerName: peerName, lastUsed: now()); lock.unlock()
        return g
    }

    public func match(_ request: InboundRequest) -> Granted? {
        guard let id = request.offer.trustId, let kind = request.offer.kind else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard var g = granted[id] else { return nil }
        let t = now()
        if t.timeIntervalSince(g.lastUsed) * 1000 > Double(g.grant.ttlMs) { granted[id] = nil; return nil }
        guard g.kind == kind, request.provesTrust(token: g.token) else { return nil }
        guard g.seen.insert(request.offer.transferId).inserted else { return nil }
        g.lastUsed = t
        granted[id] = g
        return g
    }

    public func revoke(_ id: String) { lock.lock(); granted[id] = nil; lock.unlock() }

    public func revoke(kind: String) {
        lock.lock(); granted = granted.filter { $0.value.kind != kind }; lock.unlock()
    }

    // MARK: sender

    public func remember(peerKey: String, grant: TrustGrant) {
        lock.lock(); held["\(peerKey)|\(grant.kind)"] = (grant, now()); lock.unlock()
    }

    public func credential(peerKey: String, kind: String) -> TrustCredential? {
        let key = "\(peerKey)|\(kind)"
        lock.lock(); defer { lock.unlock() }
        guard let (g, last) = held[key] else { return nil }
        let t = now()
        if t.timeIntervalSince(last) * 1000 > Double(g.ttlMs) { held[key] = nil; return nil }
        held[key] = (g, t)
        return TrustCredential(g)
    }

    public func forget(peerKey: String, kind: String) {
        lock.lock(); held["\(peerKey)|\(kind)"] = nil; lock.unlock()
    }
}
