import Foundation
@preconcurrency import CrossDropCore

/// Which of "My devices" are on the radar right now — the Swift twin of the Kotlin `PairedPresence`. A device's name and
/// address are not proof of who it is (its id changes on every launch and anyone can use any name), so every device that
/// appears on the radar is asked once, over a short connection, whether it holds one of our pairings (`pairCheck`: proven
/// both ways, nothing linkable to strangers). The answer is kept for as long as that radar entry lives at that address.
/// Costs nothing while this device holds no pairing.
@MainActor
final class PairedPresence: ObservableObject {
    /// Pairing id → the radar entry that proved it holds that pairing.
    @Published private(set) var present: [String: Peer] = [:]

    private let engine: TransferEngine
    private let pairings: PairingStore
    private struct Verdict { let pairId: String?; let endpoint: String }   // pairId nil = answered "not one of mine"
    private var verdict: [String: Verdict] = [:]          // radar id → what that device proved, and where from
    private var failedAt: [String: Date] = [:]            // radar id → when a check got no answer
    private var inFlight = Set<String>()
    private var radar: [Peer] = []
    private var pairIds = Set<String>()
    private var timer: Timer?
    nonisolated private let gate = DispatchSemaphore(value: 4)
    static let retry: TimeInterval = 20
    static let checkTimeout: TimeInterval = 3

    init(engine: TransferEngine, pairings: PairingStore) {
        self.engine = engine; self.pairings = pairings
        // A check that got no answer is tried again; nothing else needs a timer.
        timer = Timer.scheduledTimer(withTimeInterval: Self.retry, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.evaluate() }
        }
    }

    /// The radar changed.
    func update(radar peers: [Peer]) { radar = peers; evaluate() }

    /// The pairings changed (paired, unpaired).
    func pairingsChanged() { evaluate() }

    private static func endpoint(_ p: Peer) -> String { "\(p.host ?? ""):\(p.port)" }

    private func evaluate() {
        let ids = Set(pairings.devices.map(\.id))
        // Pairings changed: a device that said "not mine" may be mine now, and one that proved a pairing that is gone must
        // prove itself again.
        if ids != pairIds { verdict = verdict.filter { $0.value.pairId.map(ids.contains) ?? false } }
        pairIds = ids
        let candidates = radar.filter { $0.isReachable && $0.vault == nil }
        let here = Set(candidates.map(\.instanceId))
        verdict = verdict.filter { here.contains($0.key) }
        failedAt = failedAt.filter { here.contains($0.key) }
        // A proof was given from one address; the same radar id showing up somewhere else is not covered by it.
        for p in candidates where verdict[p.instanceId].map({ $0.endpoint != Self.endpoint(p) }) == true { verdict[p.instanceId] = nil }
        publish(candidates)
        guard !ids.isEmpty else { return }
        let now = Date()
        for p in candidates where verdict[p.instanceId] == nil && !inFlight.contains(p.instanceId) {
            if let f = failedAt[p.instanceId], now.timeIntervalSince(f) < Self.retry { continue }
            inFlight.insert(p.instanceId)
            check(p)
        }
    }

    private func check(_ p: Peer) {
        let secrets = pairings.secrets()
        let engine = self.engine, gate = self.gate
        guard let host = p.host, !secrets.isEmpty else { inFlight.remove(p.instanceId); return }
        let port = p.port, endpoint = Self.endpoint(p), id = p.instanceId, timeout = Self.checkTimeout
        Thread.detachNewThread { [weak self] in
            gate.wait()
            let answer = Result { try engine.control(host: host, port: port, connectTimeout: 2, readTimeout: timeout) { try $0.pairCheck(secrets) } }
            gate.signal()
            DispatchQueue.main.async {
                guard let self else { return }
                self.inFlight.remove(id)
                switch answer {
                case .success(let found):
                    self.verdict[id] = Verdict(pairId: found?.id, endpoint: endpoint)
                    if let found { self.pairings.seen(found.id) }
                case .failure:
                    self.failedAt[id] = Date()
                }
                self.publish(self.radar.filter { $0.isReachable && $0.vault == nil })
            }
        }
    }

    private func publish(_ candidates: [Peer]) {
        var next: [String: Peer] = [:]
        for p in candidates {
            guard let v = verdict[p.instanceId], v.endpoint == Self.endpoint(p), let pid = v.pairId, pairIds.contains(pid) else { continue }
            next[pid] = p
        }
        // A radar entry's last-seen time changes all the time; only who is here (and where) matters.
        func key(_ m: [String: Peer]) -> [String: String] { m.mapValues { "\($0.instanceId)@\(Self.endpoint($0))" } }
        if key(next) != key(present) { present = next }
    }
}
