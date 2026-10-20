@preconcurrency import CrossDropCore
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Owns the "send to anyone" session: an `HttpShareServer` plus the UI state driving the QR overlay.
/// The receiver needs nothing but a browser — the QR encodes a capability URL
/// (`http://<wifi-ip>:<port>/<token>/`) that is both the address and the secret.
@MainActor
final class QrShareManager: ObservableObject {
    struct State: Equatable {
        var url: String
        var deviceName: String
        var files: [FileMeta]
        var connected = false
        var downloadsStarted = 0
        var downloadsDone = 0
        var bytesServed: Int64 = 0
        /// Every file delivered at least once.
        var delivered: Bool { !files.isEmpty && downloadsDone >= files.count }
    }

    @Published private(set) var state: State?
    /// Set when a share couldn't start (no Wi-Fi address, bind failure) — shown by the radar.
    @Published var failure: String?

    private let deviceName: () -> String
    private let history: HistoryStore
    private var server: HttpShareServer?
    private var poll: Timer?
    private var clientSeen = false
    /// Our own liveness probe connects to the server too — don't count that as a receiver.
    private var probeUntil = Date.distantPast
    private var scopedURLs: [URL] = []
    private var tempURLs: [URL] = []

    var isSharing: Bool { state != nil }

    init(deviceName: @escaping () -> String, history: HistoryStore) {
        self.deviceName = deviceName
        self.history = history
    }

    /// `urls` may be security-scoped (document picker) — access is held until `stop()`.
    /// `temporary` paths (photo-picker exports) are deleted afterwards.
    func start(urls: [URL], temporary: [URL] = []) {
        stop()
        var files: [OutgoingFile] = []
        var scoped: [URL] = []
        for url in urls {
            if url.startAccessingSecurityScopedResource() { scoped.append(url) }
            let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard v?.isRegularFile != false else { continue }
            // UTType maps `.bin` to application/macbinary; Android reports the generic type.
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                .flatMap { $0 == "application/macbinary" ? "application/octet-stream" : $0 }
            files.append(OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(v?.fileSize ?? 0), mime: mime), url: url))
        }
        scopedURLs = scoped
        tempURLs = temporary
        guard !files.isEmpty else { fail("nothing readable selected"); return }
        launch(files)
    }

    private func launch(_ files: [OutgoingFile]) {
        guard let ip = LocalIp.wifiIPv4() else { fail("Not on Wi-Fi — connect to a network to share"); return }
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let token = bytes.map { String(format: "%02x", $0) }.joined()

        let srv = HttpShareServer(files: files, deviceName: deviceName(), token: token) { [weak self] event in
            DispatchQueue.main.async {
                if event == .clientConnected, Date() > (self?.probeUntil ?? .distantPast) { self?.clientSeen = true }
                self?.refresh()
            }
        }
        do { try srv.start() } catch { fail("Could not start sharing: \(error)"); return }
        server = srv
        let url = "http://\(ip):\(srv.port)/\(token)/"
        #if DEBUG
        NSLog("CrossDrop share up at %@", url)      // the URL is the secret — never log it in release builds
        #endif
        state = State(url: url, deviceName: deviceName(), files: files.map { $0.meta })

        KeepAwake.shared.acquire("share")      // also keeps the QR on screen
        poll = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    private func refresh() {
        guard let srv = server, state != nil else { return }
        let s = srv.stats()
        state?.connected = clientSeen || s.downloadsStarted > 0
        state?.downloadsStarted = s.downloadsStarted
        state?.downloadsDone = s.downloadsDone
        state?.bytesServed = s.bytesServed
    }

    func stop() {
        poll?.invalidate(); poll = nil
        server?.close(); server = nil
        clientSeen = false
        let s = state
        state = nil
        if let s {
            history.add(HistoryEntry(
                ts: Date().timeIntervalSince1970 * 1000, direction: "SENDING", peerName: "browser share",
                summary: s.files.count == 1 ? s.files[0].name : "\(s.files.count) files",
                files: s.files.count,
                bytes: s.bytesServed > 0 ? s.bytesServed : s.files.reduce(0) { $0 + $1.size },
                status: s.downloadsDone > 0 ? "COMPLETED" : "CANCELLED"))
        }
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }; scopedURLs = []
        tempURLs.forEach { try? FileManager.default.removeItem(at: $0) }; tempURLs = []
        KeepAwake.shared.release("share")
    }

    /// Back in the foreground: iOS may have revoked the listener while we were suspended. If nobody is
    /// listening any more, say so and stop instead of showing a QR that leads nowhere.
    func resume() {
        guard let srv = server, state != nil else { return }
        probeUntil = Date().addingTimeInterval(1.5)
        if !NetProbe.isListening(port: srv.port) {
            stop()
            failure = "Sharing stopped while CrossDrop was in the background. Start it again to get a fresh code."
        }
    }

    private func fail(_ message: String) {
        failure = message
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }; scopedURLs = []
        tempURLs.forEach { try? FileManager.default.removeItem(at: $0) }; tempURLs = []
    }

    #if DEBUG
    /// Debug hook: share a generated 4 MiB payload (`-dev_share 1`).
    func startDebug() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("qr-share-test.bin")
        try? Data((0..<(4 * 1024 * 1024)).map { UInt8($0 % 251) }).write(to: url)
        start(urls: [url])
    }
    #endif
}
