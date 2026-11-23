import Foundation
import CryptoKit
@preconcurrency import CrossDropCore

/// What last happened between this device's clipboard and one of "My devices" (Kotlin `ClipShare`).
enum ClipShare {
    /// A copy made here went there.
    case sent
    /// A copy made there was put on this clipboard.
    case received
    /// Clipboard sharing with this device is off on the other device.
    case offThere
    /// The other device shares its clipboard with us, but sharing is off here (its copy was refused).
    case wanted
    /// The other device runs a CrossDrop that predates clipboard sharing.
    case oldVersion
    /// It couldn't be reached, or whoever answered couldn't prove the pairing.
    case failed
}

/// What `ClipboardSync.copied` did with a copy.
enum CopyOutcome { case sharing, notSharing, tooLong, empty, unchanged }

/// What an explicit "share my clipboard now" achieved: pairing id → result (nil: the device wasn't found in time).
struct ShareNowResult {
    let outcome: CopyOutcome
    let results: [String: ClipShare?]
}

/// Clipboard sharing with "My devices" — the Swift twin of the Kotlin `ClipboardSync` (keep the rules in step): text copied
/// here goes to each paired device that has sharing on and is here, and their copies land on this device's clipboard. Text
/// only, at most `maxClipboardBytes`.
///
/// - Only paired devices, only with the per-device switch on (`PairedDevice.clipboard`), and only after the other device
///   proved the pairing on that same connection (PAIR_CHECK). The other side must have its switch on too, or it refuses.
/// - A copy that just arrived is not sent back (its text is remembered) and is not passed on to a third device.
/// - A copy waits for a device that isn't on the radar yet for `freshFor`, no longer: an old copy turning up later would
///   overwrite whatever was copied there since.
///
/// Thread-safe: `received` runs on engine threads, sends on one serial queue per device; `status` is published on main.
final class ClipboardSync: ObservableObject, @unchecked Sendable {
    /// Pairing id → what last happened with that device's clipboard (main thread).
    @Published private(set) var status: [String: ClipShare] = [:]

    private let engine: TransferEngine
    private let pairings: PairingStore
    /// Put text on this device's clipboard (called on an engine thread).
    private let apply: (String) -> Void
    private let freshFor: TimeInterval

    private struct Clip { let text: String; let hash: String; let at: Date; let seq: Int }
    private let lock = NSLock()
    private var seq = 0
    /// The last copy made here, until it has gone everywhere it can go (or got too old).
    private var latest: Clip?
    /// Hash of the text this device's clipboard is known to hold: copied here, or taken from a device.
    private var known: String?
    /// Pairing id → seq of the copy last sent (or tried) there.
    private var tried: [String: Int] = [:]
    /// Pairing id → (seq, result) of the last try, for `shareNow`.
    private var outcomes: [String: (seq: Int, result: ClipShare)] = [:]
    /// Ids of copies taken: the same id again is a recorded connection played back.
    private var seenIds = Set<String>()
    private var seenOrder: [String] = []
    /// Pairing id → the radar entry that proved it (`PairedPresence.present`), as last told.
    private var here: [String: Peer] = [:]
    private var queues: [String: DispatchQueue] = [:]

    static let freshFor: TimeInterval = 60
    static let maxSeen = 512

    init(engine: TransferEngine, pairings: PairingStore, freshFor: TimeInterval = ClipboardSync.freshFor,
         apply: @escaping (String) -> Void) {
        self.engine = engine; self.pairings = pairings; self.freshFor = freshFor; self.apply = apply
    }

    /// True while any paired device has clipboard sharing on: the clipboard is only looked at then.
    var active: Bool { pairings.devices.contains { $0.clipboard } }

    /// Which of "My devices" are here (a device showing up may be owed the last copy).
    func presentChanged(_ map: [String: Peer]) {
        lock.lock(); here = map; lock.unlock()
        deliver()
    }

    /// The pairings changed (a switch went on): a device may now be owed the last copy.
    func pairingsChanged() { deliver() }

    /// This device's clipboard now holds `text`. It goes to every device with sharing on that is here, or that turns up
    /// within `freshFor`. `force` sends it even when it is what the clipboard already held (someone asked for it).
    @discardableResult
    func copied(_ text: String, force: Bool = false) -> CopyOutcome {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .empty }
        if text.utf8.count > maxClipboardBytes { return .tooLong }
        if !active { return .notSharing }
        let h = Self.hash(text)
        lock.lock()
        if !force && h == known { lock.unlock(); return .unchanged }
        known = h
        seq += 1
        latest = Clip(text: text, hash: h, at: Date(), seq: seq)
        lock.unlock()
        deliver()
        return .sharing
    }

    /// Share `text` now and wait (up to `wait`) until every device with sharing on got it or refused it; `done` runs on main.
    func shareNow(_ text: String, wait: TimeInterval = 12, done: @escaping (ShareNowResult) -> Void) {
        let outcome = copied(text, force: true)
        let targets = pairings.devices.filter(\.clipboard).map(\.id)
        lock.lock(); let mine = latest?.seq; lock.unlock()
        guard outcome == .sharing, let mine else {
            DispatchQueue.main.async { done(ShareNowResult(outcome: outcome, results: [:])) }
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let deadline = Date().addingTimeInterval(wait)
            var results: [String: ClipShare?] = [:]
            while let self {
                self.lock.lock(); let m = self.outcomes; self.lock.unlock()
                results = Dictionary(uniqueKeysWithValues: targets.map { id in (id, m[id].flatMap { $0.seq == mine ? $0.result : nil }) })
                if results.values.allSatisfy({ $0 != nil }) || Date() >= deadline { break }
                Thread.sleep(forTimeInterval: 0.1)
            }
            DispatchQueue.main.async { done(ShareNowResult(outcome: outcome, results: results)) }
        }
    }

    /// Forget what happened with `pairId` (unpaired).
    func forget(_ pairId: String) {
        lock.lock(); tried[pairId] = nil; outcomes[pairId] = nil; lock.unlock()
        DispatchQueue.main.async { self.status[pairId] = nil }
    }

    /// A copy from the device holding `pairId` (proven on the connection it came over). Engine thread.
    func received(pairId: String, clipId: String, text: String) -> Bool {
        guard let device = pairings.get(pairId) else { return false }
        guard device.clipboard else {
            setStatus(pairId, .wanted)
            return false
        }
        let h = Self.hash(text)
        lock.lock()
        guard seenIds.insert(clipId).inserted else { lock.unlock(); return false }
        seenOrder.append(clipId)
        while seenOrder.count > Self.maxSeen { seenIds.remove(seenOrder.removeFirst()) }
        // Before it lands, so the clipboard watcher sees it as ours and doesn't send it straight back. And ours is no longer
        // what's on the clipboard: nothing of ours is owed to anyone now.
        known = h
        latest = nil
        lock.unlock()
        apply(text)
        setStatus(pairId, .received)
        pairings.seen(pairId)
        return true
    }

    private func setStatus(_ pairId: String, _ s: ClipShare) {
        DispatchQueue.main.async { self.status[pairId] = s }
    }

    private func deliver() {
        lock.lock()
        guard let clip = latest, Date().timeIntervalSince(clip.at) <= freshFor else { lock.unlock(); return }
        let present = here
        let owed = pairings.devices.filter { $0.clipboard && present[$0.id]?.isReachable == true && tried[$0.id] != clip.seq }.map(\.id)
        let qs = owed.map { id -> (String, DispatchQueue) in
            if let q = queues[id] { return (id, q) }
            let q = DispatchQueue(label: "crossdrop.clipboard.\(id.prefix(8))")
            queues[id] = q
            return (id, q)
        }
        lock.unlock()
        for (id, q) in qs { q.async { [weak self] in self?.sendTo(id) } }
    }

    /// One device at a time (its own serial queue), newest copy only: two quick copies must not arrive the wrong way round.
    private func sendTo(_ pairId: String) {
        guard let device = pairings.get(pairId), device.clipboard, let secret = device.secret else { return }
        lock.lock()
        guard let peer = here[pairId], let host = peer.host,
              let clip = latest, tried[pairId] != clip.seq, Date().timeIntervalSince(clip.at) <= freshFor else { lock.unlock(); return }
        tried[pairId] = clip.seq
        lock.unlock()
        let result: ClipShare
        do {
            let taken: Bool? = try engine.control(host: host, port: peer.port, connectTimeout: 2, readTimeout: 5) { ch in
                // Proven both ways first: the copy only ever goes to the device that holds this pairing.
                guard try ch.pairCheck([secret]) != nil else { return nil }
                return try ch.clipboard(clip.text)
            }
            result = taken == nil ? .failed : (taken! ? .sent : .offThere)
        } catch is EOFError {
            result = .oldVersion         // an older build fails the unknown frame and drops the connection
        } catch is ProtocolError {
            result = .oldVersion
        } catch {
            result = .failed
        }
        lock.lock(); outcomes[pairId] = (clip.seq, result); lock.unlock()
        #if DEBUG
        NSLog("CrossDrop clipboard: %d chars to %@ -> %@", clip.text.count, device.name, "\(result)")
        #endif
        setStatus(pairId, result)
        if result == .sent { pairings.seen(pairId) }
    }

    static func hash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
