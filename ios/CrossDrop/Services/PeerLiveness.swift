import Foundation

/// Mirrors core's `PeerLiveness`. mDNS only retires a peer when the peer says goodbye, so a Mac that
/// sleeps, a phone that drops off Wi-Fi or a crashed app stays cached for an hour or more and the
/// radar keeps showing a device nobody can send to. This asks each `probeable` peer's listener directly.
///
/// A peer is admitted once a probe succeeds (a stale record never flashes onto the radar) and hidden
/// after `dropAfterMisses` consecutive failures (one lost packet doesn't make a device blink). Peers
/// that can't be probed — builds that don't advertise `probe`, manual/debug peers — are trusted.
@MainActor
final class PeerLiveness {
    /// Called when the set of reachable peers changed.
    var onChange: (() -> Void)?

    private let interval: TimeInterval
    private let dropAfterMisses: Int
    private let probe: (Peer) async -> Bool
    private var sighted: () -> [Peer] = { [] }
    private var reachable: Set<String> = []
    private var misses: [String: Int] = [:]
    private var loop: Task<Void, Never>?
    private var sweeping = false
    private var sweepAgain = false

    init(interval: TimeInterval = 4, dropAfterMisses: Int = 2,
         probe: @escaping (Peer) async -> Bool = { p in
             guard let host = p.host else { return false }
             return await NetProbe.reachable(host: host, port: p.port)
         }) {
        self.interval = interval
        self.dropAfterMisses = dropAfterMisses
        self.probe = probe
    }

    /// Whether the radar should show `peer` right now.
    func admits(_ peer: Peer) -> Bool {
        !peer.probeable || !peer.isReachable || reachable.contains(peer.instanceId)
    }

    func start(sighted: @escaping () -> [Peer]) {
        self.sighted = sighted
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.sweep()
                try? await Task.sleep(nanoseconds: UInt64((self?.interval ?? 4) * 1_000_000_000))
            }
        }
    }

    func stop() { loop?.cancel(); loop = nil }

    /// Probe right away — a peer just appeared, don't make it wait for the next tick.
    func poke() {
        guard loop != nil else { return }
        Task { await sweep() }
    }

    private func sweep() async {
        if sweeping { sweepAgain = true; return }
        sweeping = true
        defer { sweeping = false }
        repeat {
            sweepAgain = false
            let targets = sighted().filter { $0.probeable && $0.isReachable }
            let results = await withTaskGroup(of: (String, Bool).self) { group in
                for p in targets { group.addTask { [probe] in (p.instanceId, await probe(p)) } }
                var out: [(String, Bool)] = []
                for await r in group { out.append(r) }
                return out
            }
            apply(results, targets: Set(targets.map(\.instanceId)))
        } while sweepAgain
    }

    private func apply(_ results: [(String, Bool)], targets: Set<String>) {
        var up = reachable
        misses = misses.filter { targets.contains($0.key) }
        for (id, ok) in results {
            if ok { misses[id] = nil; up.insert(id); continue }
            let n = (misses[id] ?? 0) + 1
            misses[id] = n
            if !up.contains(id) || n >= dropAfterMisses { up.remove(id) }
        }
        up.formIntersection(targets)
        if up != reachable { reachable = up; onChange?() }
    }
}
