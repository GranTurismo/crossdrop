import Foundation
import UIKit
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

/// Client-side "mount" of a peer vault: owns the `VaultClient`, the browsing state, a download queue
/// into the inbox and uploads into `rw` vaults. Every VaultClient call is blocking, so all of them run
/// on one serial queue (one connection, one operation at a time — like Android); state is published on main.
@MainActor
final class VaultExplorerManager: ObservableObject {
    struct Job: Identifiable, Equatable {
        let id = UUID()
        var key: String                 // vault-relative path — two files with one name must not collide
        var name: String
        var received: Int64 = 0
        var total: Int64 = 0
        var done = false
        var failed: String?
        var uploading = false
    }

    struct Ui {
        var peerName: String
        var writable: Bool
        var path = ""                           // vault-relative cwd
        var entries: [VaultDirEntry] = []
        var loading = true
        var error: String?
        /// Vault wants a PIN and we haven't unlocked yet.
        var pinRequired = false
        var pinFailed = false
        var selected: Set<String> = []
        var jobs: [Job] = []
        var previewURL: URL?
    }

    @Published private(set) var state: Ui?

    private let identity: SessionIdentity
    private let deviceName: () -> String
    private let instanceId: String
    private let inbox: InboxStore
    private let history: HistoryStore

    private let queue = DispatchQueue(label: "crossdrop.vault.client", qos: .userInitiated)
    /// Transfers get their own connection so browsing stays responsive while files move.
    private let transferQueue = DispatchQueue(label: "crossdrop.vault.transfer", qos: .utility)
    private var client: VaultClient?
    private var peer: Peer?
    private var pin: String?
    private var generation = 0         // bumps on mount/close — stale callbacks bail out

    init(identity: SessionIdentity, deviceName: @escaping () -> String, instanceId: String,
         inbox: InboxStore, history: HistoryStore) {
        self.identity = identity; self.deviceName = deviceName; self.instanceId = instanceId
        self.inbox = inbox; self.history = history
    }

    var isMounted: Bool { state != nil }

    // MARK: mount

    func mount(_ peer: Peer, pin: String? = nil) {
        guard peer.isReachable else { return }
        close()
        generation += 1
        let gen = generation
        self.peer = peer
        self.pin = pin
        state = Ui(peerName: peer.vault?.vaultName ?? peer.name, writable: peer.vault?.writable == true)
        dialAndList(gen: gen, path: "", isUnlock: false)
    }

    /// Retry a mount that asked for a PIN.
    func unlock(_ pin: String) {
        guard peer != nil else { return }
        self.pin = pin
        state?.loading = true
        state?.pinRequired = false
        dialAndList(gen: generation, path: "", isUnlock: true)
    }

    private func makeClient() -> VaultClient? {
        guard let p = peer, let host = p.host else { return nil }
        return VaultClient(host: host, port: p.port, identity: identity, deviceName: deviceName(), instanceId: instanceId)
    }

    private func dialAndList(gen: Int, path: String, isUnlock: Bool) {
        guard let c = makeClient() else { return }
        let pin = self.pin
        let old = client
        queue.async { [weak self] in
            old?.close()
            do {
                try c.connect(pin: pin)
                let entries = try c.ls(path.isEmpty ? "/" : path)
                DispatchQueue.main.async {
                    guard let self, self.generation == gen else { c.close(); return }
                    self.client = c
                    self.state?.loading = false
                    self.state?.entries = Self.sorted(entries)
                    self.state?.path = path
                    self.state?.selected = []
                }
            } catch let e as VaultError where e.code == 401 {
                c.close()
                DispatchQueue.main.async {
                    guard let self, self.generation == gen else { return }
                    self.state?.loading = false
                    self.state?.pinRequired = true
                    self.state?.pinFailed = pin != nil
                }
            } catch {
                c.close()
                DispatchQueue.main.async {
                    guard let self, self.generation == gen else { return }
                    self.state?.loading = false
                    self.state?.error = (error as? VaultError)?.message ?? "unreachable: \(Self.describe(error))"
                }
            }
        }
    }

    // MARK: browse

    func browse(_ path: String) {
        guard let c = client else { return }
        let gen = generation
        state?.loading = true
        state?.error = nil
        state?.path = path
        queue.async { [weak self] in
            var result: Result<[VaultDirEntry], Error>
            do { result = .success(try c.ls(path.isEmpty ? "/" : path)) }
            catch let e as VaultError { result = .failure(e) }
            catch {
                // Transport died (idle socket / network blip) — re-dial once.
                if let self, let fresh = DispatchQueue.main.sync(execute: { self.makeClient() }) {
                    do { try fresh.connect(pin: DispatchQueue.main.sync { self.pin })
                         DispatchQueue.main.async { if self.generation == gen { self.client = fresh } }
                         result = .success(try fresh.ls(path.isEmpty ? "/" : path)) }
                    catch { result = .failure(error) }
                } else { result = .failure(error) }
            }
            DispatchQueue.main.async {
                guard let self, self.generation == gen else { return }
                self.state?.loading = false
                switch result {
                case .success(let entries):
                    self.state?.entries = Self.sorted(entries)
                    self.state?.path = path
                    self.state?.selected = []
                case .failure(let e):
                    self.state?.error = (e as? VaultError)?.message ?? "lost connection"
                }
            }
        }
    }

    func up() {
        guard let cur = state?.path else { return }
        if let i = cur.lastIndex(of: "/") { browse(String(cur[..<i])) } else { browse("") }
    }

    func toggleSelect(_ e: VaultDirEntry) {
        let key = joinPath(state?.path ?? "", e.name)
        guard var s = state?.selected else { return }
        if s.contains(key) { s.remove(key) } else { s.insert(key) }
        state?.selected = s
    }

    func clearSelection() { state?.selected = [] }

    // MARK: download

    /// Queue downloads of `paths` (vault-relative) into the inbox.
    func download(_ paths: [String]) {
        let gen = generation
        for path in paths {
            let name = (path as NSString).lastPathComponent
            addJob(Job(key: path, name: name))
            runTransfer(gen: gen, job: path) { [weak self] c in
                guard let self else { return }
                var dest: URL?
                do {
                    let attr = try c.stat(path)
                    let target = DispatchQueue.main.sync { self.uniqueInboxURL(name) }
                    dest = target
                    try c.pull(path, to: target) { recv in self.updateJob(path) { $0.received = recv; $0.total = attr.size } }
                    self.updateJob(path) { $0.done = true; $0.received = attr.size; $0.total = attr.size }
                    DispatchQueue.main.async { self.inbox.refresh() }
                    self.record(name, bytes: attr.size, direction: "RECEIVING")
                } catch {
                    if let dest { try? FileManager.default.removeItem(at: dest) }   // no half files in the inbox
                    DispatchQueue.main.async { self.inbox.refresh() }
                    self.updateJob(path) { $0.failed = Self.describe(error) }
                }
            }
        }
    }

    /// Quick-look preview: pull into a temp file and hand the URL to the UI.
    func preview(_ path: String) {
        let gen = generation
        let name = (path as NSString).lastPathComponent
        addJob(Job(key: path, name: name))
        runTransfer(gen: gen, job: path) { [weak self] c in
            guard let self else { return }
            do {
                let attr = try c.stat(path)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vault-preview-\(UUID().uuidString)", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let dest = dir.appendingPathComponent(InboxStore.sanitize(name))      // the name comes from a remote host
                try c.pull(path, to: dest) { recv in self.updateJob(path) { $0.received = recv; $0.total = attr.size } }
                self.updateJob(path) { $0.done = true }
                DispatchQueue.main.async { if self.generation == gen { self.state?.previewURL = dest } }
            } catch {
                self.updateJob(path) { $0.failed = Self.describe(error) }
            }
        }
    }

    // MARK: upload

    /// Upload picked files (security-scoped or temp URLs) into the current folder.
    func upload(_ urls: [URL], temporary: [URL] = []) {
        let gen = generation
        let dir = state?.path ?? ""
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            let name = url.lastPathComponent
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            let key = joinPath(dir, name)
            addJob(Job(key: key, name: name, total: size, uploading: true))
            runTransfer(gen: gen, job: key) { [weak self] c in
                guard let self else { if scoped { url.stopAccessingSecurityScopedResource() }; return }
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    try c.push(self.joinPath(dir, name), fileURL: url, mime: mime) { sent in
                        self.updateJob(key) { $0.received = sent; $0.total = size }
                    }
                    self.updateJob(key) { $0.done = true; $0.received = size }
                    self.record(name, bytes: size, direction: "SENDING")
                    DispatchQueue.main.async { if self.generation == gen, self.state?.path == dir { self.browse(dir) } }
                } catch {
                    self.updateJob(key) { $0.failed = Self.describe(error) }
                }
            }
        }
        // Temp exports go once the serial transfer queue has drained past every upload above.
        if !temporary.isEmpty {
            transferQueue.async { temporary.forEach { try? FileManager.default.removeItem(at: $0) } }
        }
    }

    // MARK: bookkeeping

    /// Each transfer dials its own short-lived connection (serialised on `transferQueue`), so browsing
    /// on the main connection stays responsive and a dead socket can never poison later transfers.
    private func runTransfer(gen: Int, job: String, _ work: @escaping (VaultClient) -> Void) {
        transferQueue.async { [weak self] in
            guard let self else { return }
            let (c, pin, stale): (VaultClient?, String?, Bool) = DispatchQueue.main.sync {
                (self.makeClient(), self.pin, self.generation != gen)
            }
            guard let c, !stale else { return }
            defer { c.close() }
            do { try c.connect(pin: pin) }
            catch { self.updateJob(job) { $0.failed = Self.describe(error) }; return }
            work(c)
        }
    }

    private nonisolated func addJob(_ job: Job) {
        DispatchQueue.main.async { [weak self] in
            guard let self, var s = self.state else { return }
            s.jobs.removeAll { $0.key == job.key }
            s.jobs.append(job)
            self.state = s
        }
    }

    private nonisolated func updateJob(_ name: String, _ f: @escaping (inout Job) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, var s = self.state, let i = s.jobs.firstIndex(where: { $0.key == name }) else { return }
            f(&s.jobs[i])
            self.state = s
        }
    }

    func clearFinishedJobs() { state?.jobs.removeAll { $0.done || $0.failed != nil } }

    func dismissPreview() {
        if let u = state?.previewURL { try? FileManager.default.removeItem(at: u.deletingLastPathComponent()) }
        state?.previewURL = nil
    }

    private nonisolated func record(_ name: String, bytes: Int64, direction: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.history.add(HistoryEntry(ts: Date().timeIntervalSince1970 * 1000, direction: direction,
                                          peerName: self.state?.peerName ?? "vault", summary: name, files: 1,
                                          bytes: bytes, status: "COMPLETED"))
        }
    }

    func close() {
        generation += 1
        let a = client
        client = nil
        queue.async { a?.close() }
        if let u = state?.previewURL { try? FileManager.default.removeItem(at: u.deletingLastPathComponent()) }
        peer = nil; pin = nil
        state = nil
    }

    #if DEBUG
    /// Simulator driver (`-dev_vault_script "browse:docs;download:docs/big.bin;upload;preview:docs/notes.txt"`):
    /// steps run 1.5 s apart after the mount, since the simulator has no tap tool.
    func runDebugScript(_ script: String) {
        for (i, step) in script.split(separator: ";").enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5 + Double(i) * 1.5) { [weak self] in
                guard let self else { return }
                let parts = step.split(separator: ":", maxSplits: 1).map(String.init)
                let arg = parts.count > 1 ? parts[1] : ""
                NSLog("vault-script: %@", String(step))
                switch parts[0] {
                case "browse": self.browse(arg)
                case "up": self.up()
                case "download": self.download([arg])
                case "preview": self.preview(arg)
                case "upload":
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ios-upload.bin")
                    try? Data((0..<1_200_000).map { UInt8($0 % 241) }).write(to: url)
                    self.upload([url])
                case "unlock": self.unlock(arg)
                default: break
                }
            }
        }
    }
    #endif

    // MARK: helpers

    private func joinPath(_ dir: String, _ name: String) -> String { dir.isEmpty ? name : "\(dir)/\(name)" }

    /// Collision-safe inbox destination (same naming as `InboxStore.sinkFor`).
    private func uniqueInboxURL(_ name: String) -> URL {
        let safe = InboxStore.sanitize(name)
        let ext = (safe as NSString).pathExtension, stem = (safe as NSString).deletingPathExtension
        var target = inbox.dir.appendingPathComponent(safe)
        var i = 1
        while FileManager.default.fileExists(atPath: target.path) {
            target = inbox.dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            i += 1
        }
        return target
    }

    nonisolated static func sorted(_ e: [VaultDirEntry]) -> [VaultDirEntry] {
        e.sorted { a, b in
            if a.isDir != b.isDir { return a.isDir }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
    }

    nonisolated static func describe(_ e: Error) -> String {
        if let v = e as? VaultError { return v.message }
        if let p = e as? ProtocolError { return p.message }
        if e is EOFError { return "connection closed" }
        return e.localizedDescription
    }

    static func isPreviewable(_ e: VaultDirEntry) -> Bool {
        guard let m = e.mime else { return false }
        return m.hasPrefix("image/") || m.hasPrefix("video/") || m.hasPrefix("audio/") || m == "application/pdf" || m.hasPrefix("text/")
    }
}
