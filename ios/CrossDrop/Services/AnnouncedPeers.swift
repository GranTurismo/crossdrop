import Foundation
@preconcurrency import CrossDropCore

/// Devices known by address instead of by Bonjour (the Swift twin of Kotlin `AnnouncedPeers`): one that announced
/// itself to us (ANNOUNCE), and the phone we reached through a scanned code. They show on the radar like any sighting,
/// are probed for liveness (`probeable`), and drop out when nobody refreshes them for `ttl` (a guest re-announces every
/// 15 s while it is on the link).
///
/// Anyone on a network could already advertise any name over Bonjour, so an announcement is trusted the same way: it
/// only makes a device visible; sending still goes through the normal accept. Entries are capped so a noisy device
/// can't flood the radar. `AppContainer` merges these after Bonjour, so for a device seen both ways its own advert speaks.
@MainActor
final class AnnouncedPeers {
    /// Called whenever the set changed.
    var onChange: (() -> Void)?
    private(set) var peers: [String: Peer] = [:]

    private let ttl: TimeInterval
    private let max: Int
    private let clock: () -> Date
    private var timer: Timer?

    static let maxName = 64

    init(ttl: TimeInterval = 60, max: Int = 32, clock: @escaping () -> Date = Date.init) {
        self.ttl = ttl; self.max = max; self.clock = clock
    }

    func start() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: ttl / 4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.prune() }
        }
    }

    /// Adds or refreshes a device. Ignores ids and ports no CrossDrop build would send.
    func add(instanceId: String, name: String, host: String, port: Int) {
        guard Self.isId(instanceId), (1...65535).contains(port), !host.isEmpty else { return }
        let now = clock()
        var fresh = peers.filter { now.timeIntervalSince($0.value.lastSeen) <= ttl }
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxName))
        fresh[instanceId] = Peer(instanceId: instanceId, name: clean.isEmpty ? Peer.unnamed : clean, host: host, port: port,
                                 sources: [.bonjour], lastSeen: now, probeable: true)
        while fresh.count > max, let oldest = fresh.values.min(by: { $0.lastSeen < $1.lastSeen }) {
            fresh[oldest.instanceId] = nil
        }
        peers = fresh
        onChange?()
    }

    func remove(_ instanceId: String) {
        if peers.removeValue(forKey: instanceId) != nil { onChange?() }
    }

    func prune() {
        let now = clock()
        let fresh = peers.filter { now.timeIntervalSince($0.value.lastSeen) <= ttl }
        if fresh.count != peers.count { peers = fresh; onChange?() }
    }

    /// `[0-9a-fA-F]{8,64}`: the per-launch ids CrossDrop builds use.
    static func isId(_ s: String) -> Bool {
        (8...64).contains(s.utf8.count) && s.utf8.allSatisfy {
            (0x30...0x39).contains($0) || (0x41...0x46).contains($0) || (0x61...0x66).contains($0)
        }
    }
}

/// The transfer listener's answer to ANNOUNCE and SHAKE: both callbacks run on the engine's thread (hop to the main
/// thread for UI state).
final class AnnounceRelay: ControlHandler, @unchecked Sendable {
    typealias Heard = (_ name: String, _ instanceId: String, _ host: String, _ port: Int) -> Void
    private let onAnnounce: Heard
    private let onShake: Heard?
    init(_ onAnnounce: @escaping Heard, onShake: Heard? = nil) {
        self.onAnnounce = onAnnounce
        self.onShake = onShake
    }
    func announced(name: String, instanceId: String, host: String, port: Int) {
        onAnnounce(name, instanceId, host, port)
    }
    func shaken(name: String, instanceId: String, host: String, port: Int) {
        onShake?(name, instanceId, host, port)
    }
}
