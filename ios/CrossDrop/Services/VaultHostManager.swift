import Foundation
import UIKit
@preconcurrency import CrossDropCore

/// Owns the hosted LAN drive: a `VaultServer` over a `FileVaultFs` — the app's own `Documents/Drive`
/// folder (visible in the Files app) or a user-picked folder — plus the Bonjour advert and the
/// dashboard state. The vault id is a fresh 16-hex token per run; restarting the drive makes the old
/// beacon unresolvable.
///
/// iOS suspends sockets when the app leaves the foreground (a background task buys ~30 s), so the
/// drive only answers while CrossDrop is open — the UI says so.
@MainActor
final class VaultHostManager: ObservableObject {
    struct State: Equatable {
        var port: Int
        var url: String
        var vaultName: String
        var rootLabel: String       // "CrossDrop vault" or the picked folder's name
        var writable: Bool
        var pin: String?            // nil → open vault
        var clients: [String] = []
        var bytesOut: Int64 = 0
        var bytesIn: Int64 = 0
    }

    @Published private(set) var state: State?
    /// Current vault token — discovery filters our own beacon with this.
    @Published private(set) var vaultId: String?
    /// Last start failure, for the start card.
    @Published var failure: String?

    var isHosting: Bool { state != nil }

    private let identity: SessionIdentity
    private let deviceName: () -> String
    private var server: VaultServer?
    private let advertiser = VaultAdvertiser()
    private var poll: Timer?
    private var scopedFolder: URL?
    private let fm = FileManager.default

    init(identity: SessionIdentity, deviceName: @escaping () -> String) {
        self.identity = identity
        self.deviceName = deviceName
    }

    /// What users put in `Documents/Drive` is what the LAN sees — nothing else.
    var vaultRoot: URL {
        fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Drive", isDirectory: true)
    }

    private var stagingRoot: URL {
        fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("drive-staging", isDirectory: true)
    }

    /// Refuses roots that would expose CrossDrop's own state: `/`, the app container or an ancestor of
    /// it, and anything under `Library/`. Sub-folders of Documents or a picked iCloud/On-My-iPhone
    /// folder are fine.
    static func isShareable(_ url: URL) -> Bool {
        let f = url.resolvingSymlinksInPath().standardizedFileURL
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: f.path, isDirectory: &isDir), isDir.boolValue,
              FileManager.default.isReadableFile(atPath: f.path) else { return false }
        let path = f.path
        let home = URL(fileURLWithPath: NSHomeDirectory()).resolvingSymlinksInPath().path
        if path == "/" || home == path || home.hasPrefix(path + "/") { return false }
        if path.hasPrefix(home + "/Library") { return false }
        return true
    }

    /// Host the drive. `folder` = a security-scoped directory the user picked; nil → the built-in
    /// vault folder. Returns false when the folder is unusable or the port could not be bound.
    @discardableResult
    func start(writable: Bool, usePin: Bool, label: String? = nil, folder: URL? = nil) -> Bool {
        stop()
        failure = nil
        var rootURL = vaultRoot
        var rootLabel = "CrossDrop vault"
        if let folder {
            let scoped = folder.startAccessingSecurityScopedResource()
            guard Self.isShareable(folder) else {
                if scoped { folder.stopAccessingSecurityScopedResource() }
                failure = "That folder can't be shared — pick a folder inside Files, not the app's own storage."
                return false
            }
            if scoped { scopedFolder = folder }
            rootURL = folder
            rootLabel = folder.lastPathComponent
        }
        guard let ip = LocalIp.wifiIPv4() else {
            releaseScope()
            failure = "Not on Wi-Fi — connect to a network to turn the drive on."
            return false
        }

        var idBytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, idBytes.count, &idBytes)
        let id = idBytes.map { String(format: "%02x", $0) }.joined()
        let pin = usePin ? String(format: "%04d", Int.random(in: 0..<10_000)) : nil
        let name = (label?.isEmpty == false ? label! : "\(deviceName()) Drive")

        let fs = FileVaultFs(rootDir: rootURL, stagingRoot: folder != nil ? stagingRoot : nil)
        let srv = VaultServer(fs: fs, identity: identity, deviceName: deviceName, writable: writable,
                              pin: pin, instanceId: id) { [weak self] _ in
            DispatchQueue.main.async { self?.refresh() }
        }
        let port: Int
        do {
            // 8080 is the friendly "drive address" for Finder/Explorer; ephemeral fallback if taken.
            port = try srv.start(preferredPort: 8080)
        } catch {
            releaseScope()
            failure = "Couldn't turn on the drive (\(error))."
            return false
        }
        server = srv
        vaultId = id
        let url = "http://\(ip):\(port)/"
        state = State(port: port, url: url, vaultName: name, rootLabel: rootLabel, writable: writable, pin: pin)
        advertiser.start(vaultId: id, name: name, port: port, writable: writable, pinProtected: pin != nil)

        KeepAwake.shared.acquire("drive")
        poll = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        #if DEBUG
        NSLog("vault up at %@ (mode=%@, auth=%@, root=%@)", url, writable ? "rw" : "ro", pin != nil ? "pin" : "open", rootLabel)
        if let pin { NSLog("vault pin=%@", pin) }   // debug-only: lets simulator flows unlock
        #endif
        return true
    }

    private func refresh() {
        guard let srv = server, var s = state else { return }
        s.clients = srv.connectedPeers
        s.bytesOut = srv.bytesOut
        s.bytesIn = srv.bytesIn
        if s != state { state = s }
    }

    /// Kill the drive: drop the listener, every client, the Bonjour record and the keep-awake locks.
    func stop() {
        poll?.invalidate(); poll = nil
        advertiser.stop()
        server?.close(); server = nil
        vaultId = nil
        releaseScope()
        KeepAwake.shared.release("drive")
        if state != nil { NSLog("vault stopped") }
        state = nil
    }

    /// Foregrounded again: restart the background grace period and make sure the record is live.
    func resume() {
        guard let s = state, let id = vaultId else { return }
        // iOS may have revoked the listener while suspended — a drive nobody can reach must not look "on".
        if !NetProbe.isListening(port: s.port) {
            stop()
            failure = "The drive stopped while CrossDrop was in the background. Turn it on again to share."
            return
        }
        advertiser.start(vaultId: id, name: s.vaultName, port: s.port, writable: s.writable, pinProtected: s.pin != nil)
    }

    private func releaseScope() {
        scopedFolder?.stopAccessingSecurityScopedResource()
        scopedFolder = nil
    }

    #if DEBUG
    /// Seeds `docs/`, `media/` and `readme.md` into the built-in vault (when empty) and hosts it.
    func startDebug(mode: String) {
        let root = vaultRoot
        try? fm.createDirectory(at: root.appendingPathComponent("docs"), withIntermediateDirectories: true)
        try? fm.createDirectory(at: root.appendingPathComponent("media"), withIntermediateDirectories: true)
        try? "# CrossDrop vault\n\nHello from the iPhone drive.\n".write(to: root.appendingPathComponent("readme.md"), atomically: true, encoding: .utf8)
        try? "meeting notes\n".write(to: root.appendingPathComponent("docs/notes.txt"), atomically: true, encoding: .utf8)
        try? Data((0..<(512 * 1024)).map { UInt8($0 % 251) }).write(to: root.appendingPathComponent("media/clip.bin"))
        start(writable: mode == "rw" || mode == "pin", usePin: mode == "pin")
    }
    #endif
}
