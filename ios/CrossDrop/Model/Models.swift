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
    /// The peer's listener ignores a bare connect-and-close, so `PeerLiveness` may use one to check it is
    /// still there (older builds report a failed session for such a connection — opt-in via the peer's TXT).
    var probeable = false
    var id: String { instanceId }
    var isReachable: Bool { host != nil && port > 0 }

    /// Shown when a sighting carries no name of its own (partial resolve).
    static var unnamed: String { tr("Nearby device") }
}

enum Direction: String { case sending = "SENDING", receiving = "RECEIVING" }

enum SessionStatus: String {
    case connecting, awaitingAccept, transferring, completed, failed, declined, cancelled
    var isTerminal: Bool { self == .completed || self == .failed || self == .declined || self == .cancelled }
}

struct SessionState: Identifiable {
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
    var id = UUID()
    /// Sessions started together (one send to several devices) share it; a receive is its own group.
    var groupId = UUID()
    var kind: String?
    /// Shown by its own screen (live camera, switch phones, text) — never opens the transfer view.
    var quiet = false

    var fraction: Double {
        status == .completed ? 1 : min(1, max(0, Double(bytesDone) / Double(max(bytesTotal, 1))))
    }
}

extension SessionStatus {
    var isProblem: Bool { self == .failed || self == .declined || self == .cancelled }
}

/// One send (a session per device) or one receive, as the transfer view shows it.
struct TransferGroup: Identifiable {
    let id: UUID
    let sessions: [SessionState]
    var primary: SessionState { sessions[0] }
    var direction: Direction { primary.direction }
    var multi: Bool { sessions.count > 1 }
    var isTerminal: Bool { sessions.allSatisfy { $0.status.isTerminal } }
    var completed: Int { sessions.filter { $0.status == .completed }.count }
    var problems: Int { sessions.filter { $0.status.isProblem }.count }
    var bytesTotal: Int64 { sessions.reduce(0) { $0 + max($1.bytesTotal, 1) } }
    var bytesDone: Int64 { sessions.reduce(0) { $0 + ($1.status == .completed ? max($1.bytesTotal, $1.bytesDone) : $1.bytesDone) } }
    var bytesPerSec: Int64 { sessions.filter { !$0.status.isTerminal }.reduce(0) { $0 + $1.bytesPerSec } }
    var fraction: Double { min(1, max(0, Double(bytesDone) / Double(max(bytesTotal, 1)))) }
}

/// A photo or video that arrived during a live camera session.
struct LiveShot: Identifiable, Equatable {
    let url: URL
    let name: String
    let mime: String?
    var id: URL { url }
    var isVideo: Bool {
        mime?.hasPrefix("video/") == true || ["mp4", "mov", "m4v", "3gp", "webm"].contains(url.pathExtension.lowercased())
    }
}

/// Someone else's camera feeding this device: each shot lands here without a prompt.
struct LiveFeed {
    let grantId: String
    let peerName: String
    var shots: [LiveShot] = []
    var incoming: SessionState?
}

/// "http(s)://…" with nothing else around it.
func isLinkText(_ text: String) -> Bool {
    let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return !t.contains(" ") && !t.contains("\n") && (t.lowercased().hasPrefix("http://") || t.lowercased().hasPrefix("https://"))
}

/// An offer waiting for the human's accept/decline tap.
final class IncomingOffer: Identifiable, @unchecked Sendable {   // all mutable state is behind `lock`
    let id = UUID()
    let peerName: String
    let offer: Offer
    /// When the offer reached us — the auto-decline countdown runs from here.
    let receivedAt = Date()
    private let sem = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var answer: Bool?
    private var reason: String?
    /// Runs on the main thread when (and only when) the human accepts — opens the transfer session.
    var onAccept: (() -> Void)?

    /// A contact offer's cards, from its photo-less inline copy (empty when it had none).
    let contacts: [ContactPreview]

    init(peerName: String, offer: Offer) {
        self.peerName = peerName; self.offer = offer
        contacts = offer.kind == OfferKind.contact ? ContactPreview.parse(offer.text) : []
    }

    var kind: String? { offer.kind }
    /// Inline text of a text/link offer (nil for files, or text too long to inline).
    var text: String? { offer.kind == OfferKind.text ? offer.text : nil }

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
    /// Same shape as Android's `formatBytes` ("1.2 MB", "340 KB", "12 B"): units stay Latin in every language, digits follow the app locale.
    static func bytes(_ n: Int64) -> String {
        let d = Double(n), loc = I18n.locale
        if n >= 1 << 30 { return String(format: "%.2f GB", locale: loc, d / Double(1 << 30)) }
        if n >= 1 << 20 { return String(format: "%.1f MB", locale: loc, d / Double(1 << 20)) }
        if n >= 1 << 10 { return String(format: "%.0f KB", locale: loc, d / Double(1 << 10)) }
        return "\(n) B"
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
