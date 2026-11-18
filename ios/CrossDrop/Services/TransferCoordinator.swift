import Foundation
import UIKit
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

/// Receiving end of "Switch phones": batches are staged in `batchStore` and imported once whole.
/// Called from engine threads (store, sinks) and the main thread (begin, import) — implementations
/// keep their own state thread-safe.
protocol SwitchReceiving: AnyObject, Sendable {
    /// Staging for one batch — its own folder, so a batch can arrive while the previous one imports.
    func store(for offer: Offer) -> FileDeltaStore
    func batchFile(_ offer: Offer, _ meta: FileMeta) throws -> (url: URL, sink: OutputSink)
    @MainActor func begin(peerName: String, batch: BatchInfo?)
    @MainActor func importBatch(peerName: String, offer: Offer, files: [URL])
    @MainActor func batchEnded(failed: Bool, error: String?)
}

/// Every transfer in flight, both directions — the Swift twin of the Kotlin `TransferHub`. The UI
/// observes `sessions` (grouped into `groups`), `incomingOffer` and `live`, and calls intent-style
/// methods; it never touches sockets.
///
/// - A send to several devices runs one session per device, side by side, as one group.
/// - Text, live-camera and switch-phones offers go to their own screens. Camera and switch receivers
///   hand out a trust grant on the first accept so later shots/batches skip the prompt.
/// - Engine threads never touch published state: every event hops to the main queue tagged with its
///   session id, and events for sessions that were never accepted are dropped.
@MainActor
final class TransferCoordinator: ObservableObject {
    @Published private(set) var sessions: [SessionState] = []
    @Published private(set) var focusedGroupId: UUID?
    @Published private(set) var incomingOffer: IncomingOffer?
    @Published private(set) var live: LiveFeed?

    let trust = TrustStore()
    weak var switchReceiver: SwitchReceiving?
    weak var contactReceiver: ContactReceiving?

    private let engine: TransferEngine
    private let inbox: InboxStore
    private let history: HistoryStore

    private var tokens: [UUID: CancelToken] = [:]
    /// Security-scoped / temporary URLs a group's sends read from — released when the group ends.
    private var groupScoped: [UUID: [URL]] = [:]
    private var groupTemp: [UUID: [URL]] = [:]
    private var specs: [UUID: SendSpec] = [:]
    private var inboundCount = 0
    private let maxInbound = 8
    nonisolated static let offerTimeout: TimeInterval = 45
    nonisolated static let liveTTL: TimeInterval = 30 * 60
    nonisolated static let switchTTL: TimeInterval = 30 * 60
    static let maxLiveShots = 60

    private struct SendSpec {
        var peers: [UUID: Peer]
        var files: [OutgoingFile]
        var options: SendOptions
        var quiet: Bool
    }

    var autoAcceptOnce = false
    var autoAcceptAll = false
    var onOfferArrived: ((IncomingOffer) -> Void)?
    /// Called once per session as it ends (camera shots and switch batches excluded) — app-layer hooks.
    var onSessionEnded: ((SessionState) -> Void)?

    init(engine: TransferEngine, inbox: InboxStore, history: HistoryStore) {
        self.engine = engine; self.inbox = inbox; self.history = history
    }

    private static let keepAwakeKey = "transfer"

    // MARK: derived

    /// Groups the transfer view can show (quiet sessions excluded), oldest first.
    var groups: [TransferGroup] {
        var order: [UUID] = []
        var by: [UUID: [SessionState]] = [:]
        for s in sessions where !s.quiet {
            if by[s.groupId] == nil { order.append(s.groupId) }
            by[s.groupId, default: []].append(s)
        }
        return order.map { TransferGroup(id: $0, sessions: by[$0]!) }
    }

    var focusedGroup: TransferGroup? { groups.first { $0.id == focusedGroupId } }
    var busy: Bool { sessions.contains { !$0.status.isTerminal } }

    func focus(_ id: UUID?) { focusedGroupId = id }

    // MARK: sending

    /// Send the same items to every peer at once. `urls` may be security-scoped (document picker);
    /// `temporary` paths (picker exports) are deleted once every session of the group has ended.
    @discardableResult
    func sendTo(_ peers: [Peer], urls: [URL], temporary: [URL] = []) -> UUID? {
        var scoped: [URL] = []
        var files: [OutgoingFile] = []
        var skippedFolders = 0
        for url in urls {
            if url.startAccessingSecurityScopedResource() { scoped.append(url) }
            let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isDirectoryKey, .contentModificationDateKey])
            if v?.isDirectory == true { skippedFolders += 1; continue }
            guard v?.isRegularFile != false else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            let mtime = v?.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
            files.append(OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(v?.fileSize ?? 0), mime: mime, mtime: mtime), url: url))
        }
        // Contact cards (a .vcf from Contacts' Share or the Files app) go as a contact offer, so the
        // receiver can add them with one tap.
        if !files.isEmpty, files.allSatisfy({ ContactCards.isVCard(name: $0.meta.name, mime: $0.meta.mime) && $0.meta.size <= ContactCards.maxCardFile }) {
            let text = files.compactMap { try? String(contentsOf: $0.url, encoding: .utf8) }.joined()
            let cards = files.map { f in
                var m = f.meta; m.mime = "text/vcard"; m.cat = ItemCategory.contacts
                return OutgoingFile(meta: m, url: f.url)
            }
            return send(peers, files: cards, options: SendOptions(kind: OfferKind.contact, text: ContactCards.preview(text)),
                        quiet: false, scoped: scoped, temporary: temporary)
        }
        if files.isEmpty {
            scoped.forEach { $0.stopAccessingSecurityScopedResource() }
            temporary.forEach { try? FileManager.default.removeItem(at: $0) }
            if let p = peers.first {
                let gid = UUID()
                var s = SessionState(direction: .sending, peerName: p.name, status: .failed)
                s.error = skippedFolders > 0 ? tr("Folders can't be sent — pick files instead.") : tr("nothing readable selected")
                s.groupId = gid
                sessions.append(s)
                focusedGroupId = gid
                return gid
            }
            return nil
        }
        return send(peers, files: files, options: SendOptions(), quiet: false, scoped: scoped, temporary: temporary)
    }

    func sendTo(_ peer: Peer, urls: [URL], temporary: [URL] = []) { sendTo([peer], urls: urls, temporary: temporary) }

    /// Text or a link: shown on the receiver with Copy / Open; older versions receive a .txt file.
    @discardableResult
    func sendText(_ peers: [Peer], text: String) -> UUID? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(isLinkText(text) ? "Link.txt" : "Text.txt")
        guard (try? Data(text.utf8).write(to: url)) != nil else { return nil }
        let file = OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(text.utf8.count), mime: "text/plain"), url: url)
        return send(peers, files: [file], options: SendOptions(kind: OfferKind.text, text: text), quiet: false, scoped: [], temporary: [dir])
    }

    /// Contact cards picked in CrossDrop (`vcf` = one vCard document): the receiver sees who they are and
    /// adds them with one tap; older versions receive the `.vcf` file.
    @discardableResult
    func sendContacts(_ peers: [Peer], vcf: String, names: [String]) -> UUID? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(ContactCards.fileName(names: names))
        let data = Data(vcf.utf8)
        guard (try? data.write(to: url)) != nil else { return nil }
        let file = OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(data.count), mime: "text/vcard",
                                               cat: ItemCategory.contacts), url: url)
        return send(peers, files: [file], options: SendOptions(kind: OfferKind.contact, text: ContactCards.preview(vcf)),
                    quiet: false, scoped: [], temporary: [dir])
    }

    /// One camera shot, live to `peers` (they accept the first, then the rest arrive without asking).
    @discardableResult
    func sendShot(_ peers: [Peer], url: URL, mime: String) -> UUID? {
        let size = ((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize).map(Int64.init) ?? 0
        let file = OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: size, mime: mime,
                                               mtime: Int64(Date().timeIntervalSince1970 * 1000)), url: url)
        return send(peers, files: [file], options: SendOptions(kind: OfferKind.camera), quiet: true, scoped: [], temporary: [])
    }

    @discardableResult
    func send(_ peers: [Peer], files: [OutgoingFile], options: SendOptions, quiet: Bool,
              scoped: [URL], temporary: [URL]) -> UUID? {
        let targets = peers.filter { $0.isReachable }
        guard !targets.isEmpty else {
            scoped.forEach { $0.stopAccessingSecurityScopedResource() }
            temporary.forEach { try? FileManager.default.removeItem(at: $0) }
            return nil
        }
        let gid = UUID()
        var byId: [UUID: Peer] = [:]
        for p in targets {
            var s = SessionState(direction: .sending, peerName: p.name, status: .connecting)
            s.groupId = gid; s.kind = options.kind; s.quiet = quiet
            sessions.append(s)
            byId[s.id] = p
        }
        specs[gid] = SendSpec(peers: byId, files: files, options: options, quiet: quiet)
        groupScoped[gid] = scoped
        groupTemp[gid] = temporary
        if !quiet { focusedGroupId = gid }
        for (sid, p) in byId { launchSend(sid, peer: p, files: files, options: options) }
        updateKeepAwake()
        return gid
    }

    private func launchSend(_ sid: UUID, peer: Peer, files: [OutgoingFile], options: SendOptions) {
        guard let host = peer.host else { reduce(.failed(tr("device has no address")), id: sid); return }
        var opts = options
        let kind = options.kind
        if let kind, kind == OfferKind.camera || kind == OfferKind.switchPhones, opts.trust == nil {
            opts.trust = trust.credential(peerKey: peer.instanceId, kind: kind)
        }
        let token = CancelToken()
        tokens[sid] = token
        let engine = self.engine, trust = self.trust, port = peer.port, peerKey = peer.instanceId
        let sendOpts = opts
        Thread.detachNewThread { [weak self] in
            engine.send(host: host, port: port, files: files, options: sendOpts, cancel: token) { event in
                switch event {
                case .trustGranted(let g): trust.remember(peerKey: peerKey, grant: g)
                case .declinedByPeer: if let kind { trust.forget(peerKey: peerKey, kind: kind) }
                default: break
                }
                DispatchQueue.main.async { self?.reduce(event, id: sid) }
            }
        }
    }

    /// Resend to the devices of `groupId` that failed, declined or were cancelled.
    func retryFailed(_ groupId: UUID) {
        guard let spec = specs[groupId] else { return }
        let retry = sessions.filter { $0.groupId == groupId && $0.status.isProblem }
        for s in retry {
            guard let i = sessions.firstIndex(where: { $0.id == s.id }), let p = spec.peers[s.id] else { continue }
            var fresh = SessionState(direction: .sending, peerName: s.peerName, status: .connecting)
            fresh.id = s.id; fresh.groupId = groupId; fresh.kind = s.kind; fresh.quiet = s.quiet
            sessions[i] = fresh
            launchSend(s.id, peer: p, files: spec.files, options: spec.options)
        }
        if !spec.quiet { focusedGroupId = groupId }
        updateKeepAwake()
    }

    func cancelGroup(_ groupId: UUID) {
        for s in sessions where s.groupId == groupId { cancelSession(s.id) }
    }

    func cancelSession(_ id: UUID) {
        if let t = tokens.removeValue(forKey: id) { DispatchQueue.global(qos: .userInitiated).async { t.cancel() } }
        if let i = sessions.firstIndex(where: { $0.id == id }), !sessions[i].status.isTerminal {
            sessions[i].status = .cancelled
            ended(sessions[i])
        }
    }

    /// Forget the group's finished sessions (history already has them).
    func dismissGroup(_ groupId: UUID) {
        sessions.removeAll { $0.groupId == groupId && $0.status.isTerminal }
        if !sessions.contains(where: { $0.groupId == groupId }) {
            specs[groupId] = nil
            releaseGroup(groupId)
            if focusedGroupId == groupId { focusedGroupId = nil }
        }
    }

    func pruneQuiet(kind: String) {
        let gone = sessions.filter { $0.quiet && $0.kind == kind && $0.status.isTerminal }.map(\.groupId)
        sessions.removeAll { $0.quiet && $0.kind == kind && $0.status.isTerminal }
        for g in Set(gone) where !sessions.contains(where: { $0.groupId == g }) { specs[g] = nil; releaseGroup(g) }
    }

    // MARK: inbound

    /// Per-connection state, touched on the main thread only.
    private final class InboundConn: @unchecked Sendable {
        var sessionID: UUID?
        var kind: String?
        var peerName = ""
        var v1Files: [URL] = []
        let lock = NSLock()
        var store: FileDeltaStore?
        /// Staged for the contact receiver (not the inbox).
        var contactStaged = false
    }

    private final class DiscardSink: OutputSink {
        func write(_ bytes: [UInt8]) throws {}
        func close() {}
    }

    /// What `decide` resolves to, built on the engine thread from thread-safe parts.
    private enum Route { case files, text, contact, camera(String, TrustGrant?), switchBatch(TrustGrant?) }

    func handleInbound(_ socket: TCPSocket) {
        guard inboundCount < maxInbound else { socket.close(); return }
        inboundCount += 1
        let conn = InboundConn()
        let token = CancelToken()
        let engine = self.engine, inbox = self.inbox, trust = self.trust
        let switchRx = self.switchReceiver
        let contactRx = self.contactReceiver
        Thread.detachNewThread { [weak self] in
            engine.receive(socket: socket, cancel: token, decideRequest: { req in
                let offer = req.offer
                var route: Route?
                if let g = trust.match(req) {
                    switch offer.kind {
                    case OfferKind.camera: route = .camera(g.id, nil)
                    case OfferKind.switchPhones where switchRx != nil: route = .switchBatch(nil)
                    default: route = .files
                    }
                } else {
                    let pending = IncomingOffer(peerName: req.peerName, offer: offer)
                    DispatchQueue.main.async { self?.present(pending) }
                    let answer = pending.await(timeout: Self.offerTimeout)
                    DispatchQueue.main.async {
                        guard let self else { return }
                        if self.incomingOffer?.id == pending.id { self.incomingOffer = nil }
                    }
                    guard answer.accepted else { return .reject(answer.reason ?? "declined") }
                    if offer.kind == OfferKind.text, offer.text != nil { route = .text }
                    else if offer.kind == OfferKind.contact, contactRx != nil { route = .contact }
                    else if offer.kind == OfferKind.camera {
                        let g = trust.grant(kind: OfferKind.camera, peerName: req.peerName, ttl: Self.liveTTL)
                        route = .camera(g.id, g)
                    } else if offer.kind == OfferKind.switchPhones, switchRx != nil {
                        trust.revoke(kind: OfferKind.switchPhones)
                        route = .switchBatch(trust.grant(kind: OfferKind.switchPhones, peerName: req.peerName, ttl: Self.switchTTL))
                    } else { route = .files }
                }
                let sid = UUID()
                conn.peerName = req.peerName
                let sas = req.sas
                switch route! {
                case .text:
                    conn.kind = OfferKind.text
                    DispatchQueue.main.async { self?.register(sid, conn: conn, peer: req.peerName, sas: sas, offer: offer, kind: OfferKind.text, quiet: true) }
                    return .accept(sinkFor: { _ in DiscardSink() }, delta: nil)
                case .contact:
                    guard let rx = contactRx else { return .reject("unsupported") }
                    conn.kind = OfferKind.contact
                    conn.contactStaged = true
                    DispatchQueue.main.async { self?.register(sid, conn: conn, peer: req.peerName, sas: sas, offer: offer, kind: OfferKind.contact, quiet: true) }
                    // A few kilobytes: the plain stream path, staged outside the inbox until it's been added.
                    return .accept(sinkFor: { meta in
                        let (url, sink) = try rx.stagingFile(meta)
                        conn.lock.lock(); conn.v1Files.append(url); conn.lock.unlock()
                        return sink
                    }, delta: nil)
                case .files:
                    conn.kind = offer.kind
                    conn.store = inbox.delta
                    DispatchQueue.main.async { self?.register(sid, conn: conn, peer: req.peerName, sas: sas, offer: offer, kind: offer.kind, quiet: false) }
                    return .accept(sinkFor: { meta in
                        let (url, sink) = try inbox.newFile(meta)
                        conn.lock.lock(); conn.v1Files.append(url); conn.lock.unlock()
                        return sink
                    }, delta: inbox.delta)
                case .camera(let grantId, let grant):
                    conn.kind = OfferKind.camera
                    conn.store = inbox.delta
                    DispatchQueue.main.async {
                        self?.register(sid, conn: conn, peer: req.peerName, sas: sas, offer: offer, kind: OfferKind.camera, quiet: true)
                        self?.liveIncoming(grantId: grantId, peer: req.peerName, sessionId: sid)
                    }
                    return .accept(sinkFor: { meta in
                        let (url, sink) = try inbox.newFile(meta)
                        conn.lock.lock(); conn.v1Files.append(url); conn.lock.unlock()
                        return sink
                    }, delta: inbox.delta, grant: grant)
                case .switchBatch(let grant):
                    guard let rx = switchRx else { return .reject("unsupported") }
                    let store = rx.store(for: offer)
                    conn.kind = OfferKind.switchPhones
                    conn.store = store
                    DispatchQueue.main.async {
                        if grant != nil { rx.begin(peerName: req.peerName, batch: offer.batch) }
                        self?.register(sid, conn: conn, peer: req.peerName, sas: sas, offer: offer, kind: OfferKind.switchPhones, quiet: true)
                    }
                    return .accept(sinkFor: { meta in
                        let (url, sink) = try rx.batchFile(offer, meta)
                        conn.lock.lock(); conn.v1Files.append(url); conn.lock.unlock()
                        return sink
                    }, delta: store, grant: grant)
                }
            }, emit: { event in
                DispatchQueue.main.async { self?.inboundEvent(event, conn: conn) }
            })
            DispatchQueue.main.async { self?.inboundCount -= 1 }
        }
    }

    private func present(_ pending: IncomingOffer) {
        if autoAcceptOnce || autoAcceptAll {
            autoAcceptOnce = false
            pending.resolve(accept: true)
            return
        }
        incomingOffer?.resolve(accept: false, reason: "superseded")
        incomingOffer = pending
        onOfferArrived?(pending)
    }

    private func register(_ sid: UUID, conn: InboundConn, peer: String, sas: String, offer: Offer, kind: String?, quiet: Bool) {
        conn.sessionID = sid
        var s = SessionState(direction: .receiving, peerName: peer, sas: sas, offer: offer,
                             bytesTotal: max(offer.files.reduce(0) { $0 + $1.size }, 1), status: .transferring)
        s.id = sid; s.groupId = sid; s.kind = kind; s.quiet = quiet
        sessions.append(s)
        if !quiet, focusedGroup?.isTerminal != false { focusedGroupId = sid }
        updateKeepAwake()
    }

    private func inboundEvent(_ event: TransferEvent, conn: InboundConn) {
        guard let id = conn.sessionID else { return }      // never accepted: nothing to show
        reduce(event, id: id)
        switch event {
        case .completed(let offer, _): received(offer, conn: conn)
        case .failed(let msg):
            if conn.kind == OfferKind.switchPhones { switchReceiver?.batchEnded(failed: true, error: msg) }
            if conn.kind == OfferKind.camera { live?.incoming = nil }
        case .cancelled:
            if conn.kind == OfferKind.switchPhones { switchReceiver?.batchEnded(failed: false, error: nil) }
            if conn.kind == OfferKind.camera { live?.incoming = nil }
        default: break
        }
    }

    /// Files of a completed receive, parallel to `offer.files`.
    private func files(of offer: Offer, conn: InboundConn) -> [URL] {
        conn.lock.lock(); let v1 = conn.v1Files; conn.lock.unlock()
        if v1.count == offer.files.count { return v1 }
        guard let store = conn.store else { return [] }
        return offer.files.map { store.targetFor($0) }
    }

    private func received(_ offer: Offer, conn: InboundConn) {
        switch conn.kind {
        case OfferKind.camera:
            let urls = files(of: offer, conn: conn)
            let shots = zip(urls, offer.files).map { LiveShot(url: $0, name: $1.name, mime: $1.mime) }
            if var l = live {
                l.shots = Array((l.shots + shots).suffix(Self.maxLiveShots))
                l.incoming = nil
                live = l
            }
            inbox.refresh()
        case OfferKind.switchPhones:
            switchReceiver?.importBatch(peerName: conn.peerName, offer: offer, files: files(of: offer, conn: conn))
        case OfferKind.text:
            break
        case OfferKind.contact where conn.contactStaged:
            contactReceiver?.received(peerName: conn.peerName, offer: offer, files: files(of: offer, conn: conn))
            pruneQuiet(kind: OfferKind.contact)
        default:
            inbox.refresh()
        }
    }

    private func liveIncoming(grantId: String, peer: String, sessionId: UUID) {
        let s = sessions.first { $0.id == sessionId }
        if var l = live, l.grantId == grantId { l.incoming = s; live = l }
        else { live = LiveFeed(grantId: grantId, peerName: peer, incoming: s) }
    }

    /// End the live camera session: the sender's next shot asks again.
    func stopLive() {
        if let l = live { trust.revoke(l.grantId) }
        live = nil
        pruneQuiet(kind: OfferKind.camera)
    }

    func respondToOffer(accept: Bool) {
        guard let pending = incomingOffer else { return }
        respondToOffer(pending, accept: accept)
    }

    /// Answer exactly the offer that was on screen; if a newer one replaced it meanwhile, that one stays up.
    func respondToOffer(_ offer: IncomingOffer, accept: Bool) {
        if incomingOffer?.id == offer.id { incomingOffer = nil }
        offer.resolve(accept: accept, reason: accept ? nil : "declined")
    }

    // MARK: state

    private func reduce(_ event: TransferEvent, id: UUID) {
        guard let i = sessions.firstIndex(where: { $0.id == id }) else { return }
        var next = sessions[i]
        guard !next.status.isTerminal else { return }
        switch event {
        case .connecting: next.status = .connecting
        case .secured(let name, let sas):
            if next.direction == .sending { next.peerName = name }
            next.sas = sas
        case .awaitingAccept(let offer):
            next.offer = offer; next.status = .awaitingAccept
            next.bytesTotal = max(offer.files.reduce(0) { $0 + $1.size }, 1)
        case .progress(let done, let total, let bps, let file, let skipped):
            next.status = .transferring
            next.bytesDone = done; next.bytesTotal = total; next.bytesPerSec = bps
            next.bytesSkipped = skipped; next.currentFileName = file.name
        case .completed(let offer, _):
            next.status = .completed; next.offer = offer; next.bytesPerSec = 0
            next.bytesDone = max(next.bytesTotal, next.bytesDone)
        case .trustGranted: break
        case .declinedByPeer: next.status = .declined; next.bytesPerSec = 0
        case .cancelled: next.status = .cancelled; next.bytesPerSec = 0
        case .failed(let msg): next.status = .failed; next.error = msg; next.bytesPerSec = 0
        }
        sessions[i] = next
        if live?.incoming?.id == id { live?.incoming = next }
        if next.status.isTerminal { ended(next) }
    }

    /// A session reached a terminal state: record it, release its resources once its group is done.
    private func ended(_ s: SessionState) {
        tokens[s.id] = nil
        if s.kind != OfferKind.camera && s.kind != OfferKind.switchPhones { record(s) }
        onSessionEnded?(s)
        if s.direction == .receiving { inbox.refresh() }
        // The group's files stay readable until it is dismissed: Retry resends from them.
        updateKeepAwake()
    }

    private func releaseGroup(_ gid: UUID) {
        groupScoped.removeValue(forKey: gid)?.forEach { $0.stopAccessingSecurityScopedResource() }
        groupTemp.removeValue(forKey: gid)?.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func record(_ s: SessionState) {
        let files = s.offer?.files ?? []
        let summary: String
        if s.kind == OfferKind.text { summary = isLinkText(s.offer?.text ?? "") ? tr("Link") : tr("Text") }
        else if s.kind == OfferKind.contact {
            let n = files.first?.name ?? tr("Contact")
            summary = n.lowercased().hasSuffix(".vcf") ? String(n.dropLast(4)) : n
        }
        else if files.count == 1 { summary = files[0].name }
        else if files.count > 1 { summary = trPlural(files.count, one: "1 file", other: "{n} files") }
        else { summary = s.currentFileName ?? "—" }
        history.add(HistoryEntry(
            ts: Date().timeIntervalSince1970 * 1000, direction: s.direction.rawValue, peerName: s.peerName,
            summary: summary, files: s.kind == OfferKind.text ? 1 : files.count,
            bytes: files.isEmpty ? s.bytesDone : files.reduce(0) { $0 + $1.size },
            status: Self.historyCode(s.status)))
    }

    /// The persisted outcome code (the history row maps it to a translated label).
    private static func historyCode(_ status: SessionStatus) -> String {
        switch status {
        case .connecting: return "CONNECTING"
        case .awaitingAccept: return "AWAITINGACCEPT"
        case .transferring: return "TRANSFERRING"
        case .completed: return "COMPLETED"
        case .failed: return "FAILED"
        case .declined: return "DECLINED"
        case .cancelled: return "CANCELLED"
        }
    }

    // MARK: keep awake

    private func updateKeepAwake() {
        if busy {
            KeepAwake.shared.acquire(Self.keepAwakeKey) { [weak self] in self?.interruptedByBackground() }
        } else {
            KeepAwake.shared.release(Self.keepAwakeKey)
        }
    }

    /// iOS is about to suspend us mid-transfer; the sockets won't survive, so end them honestly.
    private func interruptedByBackground() {
        for s in sessions where !s.status.isTerminal {
            if let t = tokens.removeValue(forKey: s.id) { DispatchQueue.global(qos: .userInitiated).async { t.cancel() } }
            if let i = sessions.firstIndex(where: { $0.id == s.id }) {
                sessions[i].status = .failed
                sessions[i].error = tr("Interrupted — CrossDrop has to stay open while transferring.")
                ended(sessions[i])
            }
        }
    }
}

#if DEBUG
/// Screenshot drivers: fake a live session / pending offer without touching the network.
extension TransferCoordinator {
    func debugFakeSession(_ kind: String) {
        let files = [FileMeta(name: "Holiday in Tbilisi.mov", size: 412_000_000, mime: "video/quicktime"),
                     FileMeta(name: "IMG_4021.heic", size: 3_100_000, mime: "image/heic")]
        let total = files.reduce(0) { $0 + $1.size }
        var s = SessionState(direction: kind == "receiving" ? .receiving : .sending,
                             peerName: kind == "receiving" ? "MacBook Air" : "Pixel 9 Pro",
                             sas: "482913", offer: Offer(transferId: "debug", files: files),
                             bytesDone: Int64(Double(total) * 0.62), bytesTotal: total,
                             bytesPerSec: 38_000_000, currentFileName: files[0].name, status: .transferring)
        if kind == "done" { s.bytesDone = total; s.bytesPerSec = 0; s.status = .completed }
        sessions.append(s)
        focusedGroupId = s.groupId
    }

    func debugFakeOffer() {
        let files = [FileMeta(name: "Holiday in Tbilisi.mov", size: 412_000_000, mime: "video/quicktime"),
                     FileMeta(name: "Boarding pass.pdf", size: 312_000, mime: "application/pdf"),
                     FileMeta(name: "IMG_4021.heic", size: 3_100_000, mime: "image/heic")]
        incomingOffer = IncomingOffer(peerName: "Pixel 9 Pro", offer: Offer(transferId: "debug-offer", files: files))
    }
}
#endif
