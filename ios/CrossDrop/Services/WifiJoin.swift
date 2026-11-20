import Foundation
#if !targetEnvironment(macCatalyst)
import NetworkExtension
#endif
@preconcurrency import CrossDropCore

/// "Scan a code": joins the network in a scanned Wi-Fi code (another phone's private link, or any router's code) from
/// inside CrossDrop, and connects straight to the phone that made it. The Swift twin of Android's `net/WifiJoin`.
///
/// iOS joins through `NEHotspotConfiguration` (needs the Hotspot Configuration entitlement): the system asks the person
/// once, and with `joinOnce` the network lasts only while CrossDrop is in use (iOS leaves it once the app has been in
/// the background for 15 s), or until `leave`. Then we announce ourselves to the host (ANNOUNCE) so it sees us too, and
/// repeat that every 15 s so both radars stay current. iPhones can't host such a link, so this is only the scanning side.
/// Mac Catalyst has no in-app join: `supported` is false and the UI hides it.
@MainActor
final class WifiJoin: ObservableObject {
    enum Failure: Equatable {
        /// Not found, not in range, or the person tapped Cancel on the system's question.
        case notJoined
        /// The link went away (iOS left it while CrossDrop was in the background).
        case lost
        /// WEP and other codes iOS won't join from an app.
        case unsupported
    }

    enum State: Equatable {
        case idle
        /// The system is asking the person / joining the network.
        case joining(ssid: String)
        /// On the network; reaching the device that made the code.
        case connecting(ssid: String)
        /// On the network. `hostName` is set once the code's device answered.
        case joined(ssid: String, hostName: String?)
        case failed(ssid: String, reason: Failure)

        /// The network (and the device, once it answered) while this device is on a scanned link.
        var link: (ssid: String, hostName: String?)? {
            switch self {
            case .connecting(let s): return (s, nil)
            case .joined(let s, let h): return (s, h)
            default: return nil
            }
        }
    }

    /// Whether this device can join a network from inside the app (iPhone and iPad, not the Mac).
    static var supported: Bool {
        #if targetEnvironment(macCatalyst)
        return false
        #else
        return true
        #endif
    }

    @Published private(set) var state: State = .idle {
        didSet {
            #if DEBUG
            if state != oldValue { NSLog("CrossDrop join: %@", String(describing: state)) }
            #endif
        }
    }

    /// This device's transfer listener port (what we announce). Set by `AppContainer`.
    var listenPort: () -> Int = { 0 }

    private let engine: TransferEngine
    /// Where the host shows up on our radar (the same list announcements land in).
    private let known: AnnouncedPeers
    /// The network we asked iOS to join (removed again on `leave`); nil for the debug path.
    private var appliedSSID: String?
    private var hostId: String?
    private var talk: Task<Void, Never>?
    private var inflight: CancelToken?
    /// Bumped by every join/leave: answers that arrive for an older one are dropped.
    private var generation = 0
    private var backgroundedAt: Date?

    static let reannounce: TimeInterval = 15
    static let firstTries = 8
    /// iOS drops a `joinOnce` network once its app has been in the background for this long.
    static let joinOnceGrace: TimeInterval = 15

    init(engine: TransferEngine, known: AnnouncedPeers) {
        self.engine = engine
        self.known = known
    }

    func join(_ code: JoinCode) {
        leave()
        let ssid = code.ssid
        #if targetEnvironment(macCatalyst)
        state = .failed(ssid: ssid, reason: .unsupported)
        #else
        let config: NEHotspotConfiguration
        switch code.security {
        case "nopass": config = NEHotspotConfiguration(ssid: ssid)
        case "WEP": state = .failed(ssid: ssid, reason: .unsupported); return
        default: config = NEHotspotConfiguration(ssid: ssid, passphrase: code.password ?? "", isWEP: false)   // WPA2/WPA3 personal
        }
        config.joinOnce = true
        if code.hidden { config.hidden = true }
        generation += 1
        let gen = generation
        appliedSSID = ssid
        state = .joining(ssid: ssid)
        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            DispatchQueue.main.async { self?.applied(gen, code, error) }
        }
        #endif
    }

    #if !targetEnvironment(macCatalyst)
    private func applied(_ gen: Int, _ code: JoinCode, _ error: Error?) {
        guard gen == generation else { return }
        if let error = error as NSError? {
            let reason: Failure? = {
                guard error.domain == NEHotspotConfigurationErrorDomain else { return .notJoined }
                switch NEHotspotConfigurationError(rawValue: error.code) {
                case .alreadyAssociated?: return nil                  // already on it: carry on
                case .invalid?, .invalidSSID?, .invalidWPAPassphrase?, .invalidWEPPassphrase?, .invalidEAPSettings?,
                     .invalidHS20Settings?, .invalidHS20DomainName?, .invalidSSIDPrefix?:
                    return .unsupported
                default: return .notJoined                          // userDenied, internal, pending, systemConfiguration…
                }
            }()
            if let reason {
                #if DEBUG
                NSLog("CrossDrop join: apply failed: %@", error.description)
                #endif
                appliedSSID = nil
                state = .failed(ssid: code.ssid, reason: reason)
                return
            }
        }
        state = .connecting(ssid: code.ssid)
        reach(code, gen)
    }
    #endif

    /// Leave the network (iOS returns to the usual Wi-Fi) and forget the host.
    func leave() {
        stopTalking()
        #if !targetEnvironment(macCatalyst)
        if let ssid = appliedSSID { NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid) }
        #endif
        appliedSSID = nil
        state = .idle
    }

    /// Clears a failure shown to the person.
    func dismiss() { if case .failed = state { state = .idle } }

    /// Debug builds: skip the Wi-Fi step (the simulator shares the Mac's network) and connect + announce.
    func connectForDebug(_ code: JoinCode) {
        leave()
        state = .connecting(ssid: code.ssid)
        reach(code, generation)
    }

    func enteredBackground() { backgroundedAt = Date() }

    /// Back in the foreground: after more than `joinOnceGrace` away, iOS has already left a `joinOnce` network.
    func resumed() {
        defer { backgroundedAt = nil }
        guard let since = backgroundedAt, appliedSSID != nil, Date().timeIntervalSince(since) > Self.joinOnceGrace else { return }
        // (A join still waiting on the system's answer isn't on the network yet: its answer decides.)
        guard let ssid = state.link?.ssid else { return }
        leave()
        state = .failed(ssid: ssid, reason: .lost)
    }

    // MARK: talking to the host

    private func stopTalking() {
        generation += 1
        talk?.cancel(); talk = nil
        inflight?.cancel(); inflight = nil
        if let id = hostId { known.remove(id) }
        hostId = nil
    }

    private func reach(_ code: JoinCode, _ gen: Int) {
        talk?.cancel()
        talk = Task { [weak self] in await self?.reachHost(code, gen) }
    }

    /// Announce to the code's device until it answers, then keep it current. Without an address, discovery has to find it.
    private func reachHost(_ code: JoinCode, _ gen: Int) async {
        guard let host = code.host else { state = .joined(ssid: code.ssid, hostName: nil); return }
        var first = true
        var misses = 0
        while !Task.isCancelled && gen == generation {
            let answer = await announce(host: host, port: code.port)
            guard !Task.isCancelled, gen == generation else { return }
            if let answer {
                hostId = answer.instanceId
                known.add(instanceId: answer.instanceId, name: answer.name, host: host, port: code.port)
                misses = 0
                if first || state.link?.hostName != answer.name { state = .joined(ssid: code.ssid, hostName: answer.name) }
                first = false
                await sleep(Self.reannounce)
            } else {
                misses += 1
                // The phone that made the code may need a moment after we joined; after that, keep being on the network
                // even if it doesn't answer (discovery may still find it). A host that stopped answering is no longer
                // named as linked.
                if (first && misses == Self.firstTries) || (!first && misses == 2) { state = .joined(ssid: code.ssid, hostName: nil) }
                await sleep(first && misses < Self.firstTries ? 1 : Self.reannounce)
            }
        }
    }

    /// One ANNOUNCE on a background thread (the engine blocks); nil when the host didn't answer.
    private func announce(host: String, port: Int) async -> (name: String, instanceId: String)? {
        let listen = listenPort()
        guard listen > 0 else { return nil }
        let engine = self.engine
        let token = CancelToken()
        inflight = token
        let result: (name: String, instanceId: String)? = await withCheckedContinuation { cont in
            Thread.detachNewThread {
                do {
                    cont.resume(returning: try engine.announce(host: host, port: port, listenPort: listen, cancel: token))
                } catch {
                    #if DEBUG
                    NSLog("CrossDrop join: announce to %@:%d failed: %@", host, port, String(describing: error))
                    #endif
                    cont.resume(returning: nil)
                }
            }
        }
        if inflight === token { inflight = nil }
        #if DEBUG
        if let result { NSLog("CrossDrop join: announced :%d to %@:%d -> %@ (%@)", listen, host, port, result.name, result.instanceId) }
        #endif
        return result
    }

    private func sleep(_ seconds: TimeInterval) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}
