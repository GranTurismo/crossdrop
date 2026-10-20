import Foundation
import UIKit
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

/// Single source of truth for "what is happening right now". The UI never touches sockets — it
/// observes `session` / `incomingOffer` and calls intent-style methods.
///
/// Engine threads never touch published state directly: every event hops to the main queue tagged
/// with the id of the session it belongs to, and events from any other (older, unaccepted or probing)
/// connection are dropped — so a stray inbound connection can never overwrite a running send.
@MainActor
final class TransferCoordinator: ObservableObject {
    @Published private(set) var session: SessionState?
    @Published private(set) var incomingOffer: IncomingOffer?

    private let engine: TransferEngine
    private let inbox: InboxStore
    private let history: HistoryStore

    /// Identity + cancel handle of the session `session` describes.
    private var activeID: UUID?
    private var activeToken: CancelToken?
    private var scopedURLs: [URL] = []
    private var tempURLs: [URL] = []
    /// Open inbound connections — capped so a port scanner can't pile up blocked threads.
    private var inboundCount = 0
    private let maxInbound = 4
    nonisolated private static let offerTimeout: TimeInterval = 45

    /// Debug: auto-accept the next inbound offer (loopback self-tests).
    var autoAcceptOnce = false
    /// Debug: auto-accept every inbound offer (`-dev_autoaccept YES`).
    var autoAcceptAll = false

    /// Hook for the app layer (notification when backgrounded).
    var onOfferArrived: ((IncomingOffer) -> Void)?

    init(engine: TransferEngine, inbox: InboxStore, history: HistoryStore) {
        self.engine = engine; self.inbox = inbox; self.history = history
    }

    private static let keepAwakeKey = "transfer"

    // MARK: sending

    /// `urls` may be security-scoped (document picker) — access is held until the session ends.
    /// `temporary` URLs (photo picker exports) are deleted afterwards.
    func sendTo(_ peer: Peer, urls: [URL], temporary: [URL] = []) {
        guard let host = peer.host, peer.port > 0 else {
            temporary.forEach { try? FileManager.default.removeItem(at: $0) }
            return
        }
        // A new send replaces whatever was showing; stop its engine thread first.
        if session?.status.isTerminal == false { terminate(.cancelled) } else { endSessionResources() }
        let id = UUID()
        activeID = id
        activeToken = nil
        session = SessionState(direction: .sending, peerName: peer.name, status: .connecting)
        tempURLs = temporary

        var files: [OutgoingFile] = []
        var skippedFolders = 0
        for url in urls {
            if url.startAccessingSecurityScopedResource() { scopedURLs.append(url) }
            let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isDirectoryKey])
            if v?.isDirectory == true { skippedFolders += 1; continue }
            guard v?.isRegularFile != false else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            files.append(OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(v?.fileSize ?? 0), mime: mime), url: url))
        }
        guard !files.isEmpty else {
            terminate(.failed, error: skippedFolders > 0 ? "Folders can't be sent — pick files instead." : "nothing readable selected")
            return
        }
        KeepAwake.shared.acquire(Self.keepAwakeKey) { [weak self] in self?.interruptedByBackground(id) }
        let token = CancelToken()
        activeToken = token
        let name = peer.name
        let engine = self.engine
        let port = peer.port
        Thread.detachNewThread { [weak self] in
            engine.send(host: host, port: port, files: files, cancel: token) { event in
                DispatchQueue.main.async { self?.reduce(event, direction: .sending, id: id) }
            }
        }
        _ = name
    }

    // MARK: inbound

    /// Per-connection state, touched on the main thread only.
    /// Touched on the main thread only (events hop there before reading or writing it).
    private final class InboundConn: @unchecked Sendable {
        var sas: String?
        var sessionID: UUID?
        var pending: IncomingOffer?
    }

    func handleInbound(_ socket: TCPSocket) {
        guard inboundCount < maxInbound else { socket.close(); return }
        inboundCount += 1
        let conn = InboundConn()
        let token = CancelToken()
        let engine = self.engine, inbox = self.inbox
        Thread.detachNewThread { [weak self] in
            engine.receive(socket: socket, cancel: token, decide: { peerName, offer in
                let pending = IncomingOffer(peerName: peerName, offer: offer)
                // No main.sync: the engine thread just waits for the human (or a policy) to answer.
                DispatchQueue.main.async { self?.present(pending, conn: conn, token: token) }
                let answer = pending.await(timeout: Self.offerTimeout)
                DispatchQueue.main.async {
                    guard let self else { return }
                    if self.incomingOffer?.id == pending.id { self.incomingOffer = nil }
                }
                if answer.accepted { return .accept(sinkFor: { try inbox.sinkFor($0) }, delta: inbox.delta) }
                return .reject(answer.reason ?? "declined")
            }, emit: { event in
                DispatchQueue.main.async { self?.inboundEvent(event, conn: conn) }
            })
            DispatchQueue.main.async { self?.inboundCount -= 1 }
        }
    }

    /// Decide what happens to a fresh offer: busy → reject, debug auto-accept, otherwise raise the sheet.
    private func present(_ pending: IncomingOffer, conn: InboundConn, token: CancelToken) {
        conn.pending = pending
        pending.onAccept = { [weak self] in self?.beginReceive(pending, conn: conn, token: token) }
        if session?.status.isTerminal == false {          // one transfer at a time
            pending.resolve(accept: false, reason: "busy")
            return
        }
        if autoAcceptOnce || autoAcceptAll {
            autoAcceptOnce = false
            pending.onAccept?()
            pending.resolve(accept: true)
            return
        }
        incomingOffer?.resolve(accept: false, reason: "superseded")
        incomingOffer = pending
        onOfferArrived?(pending)
    }

    private func beginReceive(_ pending: IncomingOffer, conn: InboundConn, token: CancelToken) {
        if session?.status.isTerminal == false { terminate(.cancelled) } else { endSessionResources() }
        let id = UUID()
        conn.sessionID = id
        activeID = id
        activeToken = token
        session = SessionState(direction: .receiving, peerName: pending.peerName, sas: conn.sas,
                               offer: pending.offer, status: .transferring)
        KeepAwake.shared.acquire(Self.keepAwakeKey) { [weak self] in self?.interruptedByBackground(id) }
    }

    private func inboundEvent(_ event: TransferEvent, conn: InboundConn) {
        if case .secured(_, let sas) = event { conn.sas = sas; return }
        // Until the human accepts, nothing about this connection is shown or recorded.
        guard let id = conn.sessionID else { return }
        reduce(event, direction: .receiving, id: id)
    }

    func respondToOffer(accept: Bool) {
        guard let pending = incomingOffer else { return }
        incomingOffer = nil
        if accept { pending.onAccept?() }
        pending.resolve(accept: accept, reason: accept ? nil : "declined")
    }

    // MARK: session

    func cancelSession() {
        // An offer still waiting for a tap would otherwise keep its engine thread parked for 45 s.
        if let pending = incomingOffer { incomingOffer = nil; pending.resolve(accept: false, reason: "cancelled") }
        terminate(.cancelled)
    }

    func dismissSession() {
        if session?.status.isTerminal == true { session = nil }
    }

    /// iOS is about to suspend us mid-transfer; the socket won't survive, so end it honestly.
    private func interruptedByBackground(_ id: UUID) {
        guard id == activeID else { return }
        terminate(.failed, error: "Interrupted — CrossDrop has to stay open while transferring.")
    }

    /// Move the live session to a terminal state exactly once: record it, free its resources, and stop
    /// the engine thread (off the main thread — cancelling writes to the socket).
    private func terminate(_ status: SessionStatus, error: String? = nil) {
        guard var s = session, !s.status.isTerminal else { endSessionResources(); return }
        s.status = status
        if let error { s.error = error }
        session = s
        let token = activeToken
        activeToken = nil
        if let token { DispatchQueue.global(qos: .userInitiated).async { token.cancel() } }
        finish(s)
    }

    private func reduce(_ event: TransferEvent, direction: Direction, id: UUID) {
        guard id == activeID, var next = session, !next.status.isTerminal else { return }
        switch event {
        case .connecting: next.status = .connecting
        case .secured(let name, let sas): next.peerName = name; next.sas = sas
        case .awaitingAccept(let offer): next.offer = offer; next.status = .awaitingAccept
        case .progress(let done, let total, let bps, let file, let skipped):
            next.status = .transferring
            next.bytesDone = done; next.bytesTotal = total; next.bytesPerSec = bps
            next.bytesSkipped = skipped; next.currentFileName = file.name
        case .completed(let offer, _): next.status = .completed; next.offer = offer
        case .declinedByPeer: next.status = .declined
        case .cancelled: next.status = .cancelled
        case .failed(let msg): next.status = .failed; next.error = msg
        }
        session = next
        if next.status.isTerminal { activeToken = nil; finish(next) }
    }

    private func finish(_ s: SessionState) {
        record(s.direction, s)
        endSessionResources()
        if s.direction == .receiving { inbox.refresh() }
    }

    private func record(_ direction: Direction, _ s: SessionState) {
        let files = s.offer?.files ?? []
        let summary: String
        if files.count == 1 { summary = files[0].name }
        else if files.count > 1 { summary = "\(files.count) files" }
        else { summary = s.currentFileName ?? "—" }
        history.add(HistoryEntry(
            ts: Date().timeIntervalSince1970 * 1000, direction: direction.rawValue, peerName: s.peerName,
            summary: summary, files: files.count,
            bytes: files.isEmpty ? s.bytesDone : files.reduce(0) { $0 + $1.size },
            status: s.status.rawValue.uppercased()))
    }

    // MARK: resources

    private func endSessionResources() {
        KeepAwake.shared.release(Self.keepAwakeKey)
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        scopedURLs = []
        tempURLs.forEach { try? FileManager.default.removeItem(at: $0) }
        tempURLs = []
    }
}
