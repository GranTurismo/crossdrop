import Foundation

/// Shake to connect: pairs this device's own shake with SHAKE messages from devices on the radar (the Swift twin of
/// Kotlin `ShakeMatcher`; keep the numbers and rules in step).
///
/// Every time is taken on this device's clock: when we were shaken, and when each SHAKE *arrived*. Two people shaking
/// together see each other's message land within a second or two of their own shake, so the phones' clocks never need
/// to agree. Whichever happens second (our shake, or their message) makes the match; each shake and each message is
/// used once, so one shake opens one device. Thread-safe.
public final class ShakeMatcher: @unchecked Sendable {
    public struct Match: Equatable, Sendable {
        public let instanceId: String
        public let name: String
        public let atMs: Int64
        public init(instanceId: String, name: String, atMs: Int64) {
            self.instanceId = instanceId; self.name = name; self.atMs = atMs
        }
    }

    /// How far apart our shake and their message may be.
    public static let windowMs: Int64 = 2_500
    static let maxRemote = 32

    /// This device's uptime in ms: only differences matter, and unlike the wall clock it never jumps.
    public static func uptimeMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    public let windowMs: Int64
    private let clock: () -> Int64
    private let lock = NSLock()
    private var localAt: Int64?
    /// Recent SHAKE messages, oldest first. A repeat from the same device refreshes its time and keeps its place.
    private var messages: [(instanceId: String, name: String, at: Int64)] = []

    public init(clock: @escaping () -> Int64 = ShakeMatcher.uptimeMs, windowMs: Int64 = ShakeMatcher.windowMs) {
        self.clock = clock; self.windowMs = windowMs
    }

    /// We were shaken: the device whose SHAKE arrived closest to now, if any arrived within the window.
    public func local() -> Match? {
        lock.lock(); defer { lock.unlock() }
        let now = clock()
        prune(now)
        var best: Int?
        for i in messages.indices where best == nil || abs(now - messages[i].at) < abs(now - messages[best!].at) { best = i }
        if let i = best {
            let m = messages.remove(at: i)
            localAt = nil
            return Match(instanceId: m.instanceId, name: m.name, atMs: now)
        }
        localAt = now
        return nil
    }

    /// A device said it was shaken: a match if we were shaken within the window.
    public func remote(instanceId: String, name: String) -> Match? {
        lock.lock(); defer { lock.unlock() }
        let now = clock()
        prune(now)
        if let at = localAt, now - at <= windowMs {
            localAt = nil
            messages.removeAll { $0.instanceId == instanceId }
            return Match(instanceId: instanceId, name: name, atMs: now)
        }
        if let i = messages.firstIndex(where: { $0.instanceId == instanceId }) {
            messages[i].name = name; messages[i].at = now
        } else {
            messages.append((instanceId, name, now))
        }
        while messages.count > Self.maxRemote { messages.removeFirst() }
        return nil
    }

    /// When this device was last shaken without a match yet (for "now shake the other phone"); nil if not lately.
    public func waitingSince() -> Int64? {
        lock.lock(); defer { lock.unlock() }
        guard let at = localAt, clock() - at <= windowMs else { return nil }
        return at
    }

    private func prune(_ now: Int64) {
        messages.removeAll { now - $0.at > windowMs }
    }
}

/// Turns accelerometer samples into "shaken" moments (the Swift twin of Kotlin `ShakeGesture`, same numbers). A shake is
/// a burst: within the last `spanNs`, at least three quarters of the samples are well above gravity, over at least half
/// that span. One shake per `cooldownNs`. Feed it from one thread.
public final class ShakeGesture {
    /// m/s²; gravity alone is about 9.8, a deliberate shake goes well past this.
    private let threshold: Double
    private let spanNs: Int64
    private let cooldownNs: Int64
    private var times: [Int64] = []
    private var strong: [Bool] = []
    private var strongCount = 0
    private var lastShakeNs: Int64?

    public init(threshold: Double = 13.5, spanNs: Int64 = 500_000_000, cooldownNs: Int64 = 1_500_000_000) {
        self.threshold = threshold; self.spanNs = spanNs; self.cooldownNs = cooldownNs
    }

    /// Feed one sample (any time base in ns, x/y/z in m/s² *including* gravity); true when it completes a shake.
    public func sample(timeNs: Int64, x: Double, y: Double, z: Double) -> Bool {
        let isStrong = x * x + y * y + z * z > threshold * threshold
        times.append(timeNs); strong.append(isStrong); if isStrong { strongCount += 1 }
        while let first = times.first, timeNs - first > spanNs {
            times.removeFirst(); if strong.removeFirst() { strongCount -= 1 }
        }
        guard let first = times.first else { return false }
        let covered = timeNs - first >= spanNs / 2
        let rested = lastShakeNs.map { timeNs - $0 >= cooldownNs } ?? true
        if covered && times.count >= 4 && strongCount * 4 >= times.count * 3 && rested {
            lastShakeNs = timeNs
            times.removeAll(); strong.removeAll(); strongCount = 0
            return true
        }
        return false
    }
}
