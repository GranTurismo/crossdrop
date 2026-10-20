import Foundation
import UIKit
@preconcurrency import CrossDropCore

/// How a peer was sighted.
enum DiscoverySource: String { case bonjour, manual }

/// TXT-advertised properties of a `_crossdrop-vault._tcp.` service.
struct VaultDescriptor: Equatable {
    var vaultName: String
    var writable: Bool
    var needsPin: Bool
}

/// A reachable CrossDrop instance on the local network.
struct Peer: Identifiable, Equatable {
    var instanceId: String
    var name: String
    var host: String?
    var port: Int = 0
    var sources: Set<DiscoverySource> = [.bonjour]
    var lastSeen = Date()
    var vault: VaultDescriptor?
    var id: String { instanceId }
    var isReachable: Bool { host != nil && port > 0 }
}

enum Direction: String { case sending = "SENDING", receiving = "RECEIVING" }

enum SessionStatus: String {
    case connecting, awaitingAccept, transferring, completed, failed, declined, cancelled
    var isTerminal: Bool { self == .completed || self == .failed || self == .declined || self == .cancelled }
}

struct SessionState {
    var direction: Direction
    var peerName: String
    var sas: String?
    var offer: Offer?
    var bytesDone: Int64 = 0
    var bytesTotal: Int64 = 1
    var bytesPerSec: Int64 = 0
    /// v2 delta: bytes satisfied from local sources instead of the wire.
    var bytesSkipped: Int64 = 0
    var currentFileName: String?
    var status: SessionStatus = .connecting
    var error: String?

    var fraction: Double { min(1, max(0, Double(bytesDone) / Double(max(bytesTotal, 1)))) }
}

/// An offer waiting for the human's accept/decline tap.
final class IncomingOffer: Identifiable, @unchecked Sendable {   // all mutable state is behind `lock`
    let id = UUID()
    let peerName: String
    let offer: Offer
    private let sem = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var answer: Bool?
    private var reason: String?
    /// Runs on the main thread when (and only when) the human accepts — opens the transfer session.
    var onAccept: (() -> Void)?

    init(peerName: String, offer: Offer) { self.peerName = peerName; self.offer = offer }

    func resolve(accept: Bool, reason: String? = nil) {
        lock.lock()
        if answer == nil { answer = accept; self.reason = reason; sem.signal() }
        lock.unlock()
    }

    /// Blocks the engine thread until the human answers (or the timeout expires).
    func await(timeout: TimeInterval) -> (accepted: Bool, reason: String?) {
        if sem.wait(timeout: .now() + timeout) == .timedOut { resolve(accept: false, reason: "no response") }
        lock.lock(); defer { lock.unlock() }
        return (answer ?? false, reason)
    }
}

struct HistoryEntry: Codable, Identifiable {
    var ts: Double          // epoch millis
    var direction: String   // SENDING | RECEIVING
    var peerName: String
    var summary: String
    var files: Int
    var bytes: Int64
    var status: String      // COMPLETED | FAILED | DECLINED | CANCELLED
    var id: String { "\(ts)-\(peerName)-\(summary)" }
    var isSend: Bool { direction == "SENDING" }
    var ok: Bool { status == "COMPLETED" }
    var date: Date { Date(timeIntervalSince1970: ts / 1000) }
}

enum Fmt {
    static func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }
    static func rate(_ bps: Int64) -> String { bytes(bps) + "/s" }
}

extension String {
    /// At most `bytes` UTF-8 bytes, cut on a character boundary (TXT records and file names have byte limits).
    func clippedUTF8(_ bytes: Int) -> String {
        var out = ""
        var n = 0
        for ch in self {
            let c = String(ch).utf8.count
            if n + c > bytes { break }
            out.append(ch); n += c
        }
        return out
    }
}

/// Opens the OS page where the user can re-enable Local Network access for CrossDrop.
@MainActor
func openSystemSettings() {
    #if targetEnvironment(macCatalyst)
    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
        UIApplication.shared.open(url)
    }
    #else
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    #endif
}

/// Device-appropriate wording: the same app ships on iPhone, iPad and Mac.
enum Platform {
    static var deviceNoun: String {
        #if targetEnvironment(macCatalyst)
        return "Mac"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        #endif
    }

    /// iOS suspends sockets when the app leaves the foreground; a Mac keeps serving.
    static var pausesInBackground: Bool {
        #if targetEnvironment(macCatalyst)
        return false
        #else
        return true
        #endif
    }
}
