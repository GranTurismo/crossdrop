import Foundation
import UIKit
import Network
import Combine
@preconcurrency import CrossDropCore

/// Hand-rolled service locator, mirrors the Android/desktop `AppContainer`.
@MainActor
final class AppContainer: ObservableObject {
    let instanceId: String = {
        var bytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }()

    @Published private(set) var deviceName: String
    @Published private(set) var peers: [Peer] = []
    @Published private(set) var listenerPort: Int = 0
    @Published var networkNotice: String?
    /// Files opened from another app ("Open in CrossDrop"), waiting for the user to pick a device.
    @Published var pendingShare: [URL] = []
    private var pendingTemporary: [URL] = []
    @Published var discoveryEnabled: Bool
    /// Wireless Drive host overlay open?
    @Published var driveOpen = false
    /// Bumped by the ⌘O "Send Files…" menu command; the radar reacts.
    @Published var sendRequest = 0
    /// Bumped by ⌘R "Rescan Network".
    func requestSend() { sendRequest += 1 }

    let identity = SessionIdentity()
    let inbox = InboxStore()
    let history = HistoryStore()
    let engine: TransferEngine
    let coordinator: TransferCoordinator
    /// "Send to anyone" — zero-install receiver path via QR + embedded HTTP.
    let qrShare: QrShareManager
    /// Client side of the Wireless Drive: a mounted peer vault.
    let vaultExplorer: VaultExplorerManager
    /// "Wireless Drive" — this device as a browsable LAN drive (host side).
    let vaultHost: VaultHostManager

    private let listener = TCPListener()
    private var discovery: BonjourDiscovery!
    private var manual: [String: Peer] = [:]
    private var bonjour: [String: Peer] = [:]
    private var vaultBeacons: [String: Peer] = [:]
    private var vaultDiscovery: VaultDiscovery!
    private let liveness = PeerLiveness()
    private var started = false
    private let pathMonitor = NWPathMonitor()
    private var lastPathSignature = ""

    init() {
        let defaults = UserDefaults.standard
        let name = defaults.string(forKey: "device_name") ?? Self.defaultDeviceName()
        deviceName = name
        discoveryEnabled = defaults.object(forKey: "nsd_enabled") as? Bool ?? true
        let id = instanceId
        // The engine reads the live name through a box so renames apply to later sessions.
        let box = NameBox(name)
        nameBox = box
        engine = TransferEngine(identity: identity, deviceName: { box.value }, instanceId: id)
        coordinator = TransferCoordinator(engine: engine, inbox: inbox, history: history)
        qrShare = QrShareManager(deviceName: { box.value }, history: history)
        vaultExplorer = VaultExplorerManager(identity: identity, deviceName: { box.value }, instanceId: id, inbox: inbox, history: history)
        vaultHost = VaultHostManager(identity: identity, deviceName: { box.value })
        discovery = BonjourDiscovery(instanceId: id, deviceName: { box.value })
        discovery.onPeersChanged = { [weak self] map in
            self?.bonjour = map
            self?.mergePeers()
        }
        discovery.onError = { [weak self] msg in self?.networkNotice = msg }
        vaultDiscovery = VaultDiscovery()
        vaultDiscovery.selfVaultId = { [weak self] in self?.vaultHost.vaultId }
        vaultDiscovery.onPeersChanged = { [weak self] map in
            self?.vaultBeacons = map
            self?.mergePeers()
        }
        // Radar only shows peers whose listener still answers (mDNS alone keeps a vanished device for an hour).
        liveness.onChange = { [weak self] in self?.mergePeers() }
        liveness.start { [weak self] in self?.sightedPeers() ?? [] }
    }

    /// The name other devices see until the user picks one. iOS hides the user-given device name from apps
    /// (it reports just "iPhone"), so a short stable suffix keeps two iPhones apart; on Mac it's the computer name.
    static func defaultDeviceName() -> String {
        #if targetEnvironment(macCatalyst)
        var host = ProcessInfo.processInfo.hostName
        if host.hasSuffix(".local") { host.removeLast(".local".count) }
        // The BSD host name is lower-case and dashed ("gogitas-macbook-air") — make it read like a name.
        host = host.replacingOccurrences(of: "-", with: " ").trimmingCharacters(in: .whitespaces).capitalized
        return host.isEmpty || host.lowercased() == "localhost" ? "Mac" : host
        #else
        let device = UIDevice.current
        let raw = device.name.trimmingCharacters(in: .whitespaces)
        if !raw.isEmpty, raw != device.model { return raw }
        let defaults = UserDefaults.standard
        var suffix = defaults.string(forKey: "name_suffix") ?? ""
        if suffix.isEmpty {
            var b = [UInt8](repeating: 0, count: 2)
            _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
            suffix = b.map { String(format: "%02X", $0) }.joined()
            defaults.set(suffix, forKey: "name_suffix")
        }
        return "\(device.model) \(suffix)"
        #endif
    }

    private var notificationWatch: AnyCancellable?
    private let nameBox: NameBox
    /// Read from engine/discovery threads, written from the UI — hence the lock.
    private final class NameBox: @unchecked Sendable {
        private let lock = NSLock()
        private var name: String
        init(_ v: String) { name = v }
        var value: String {
            get { lock.lock(); defer { lock.unlock() }; return name }
            set { lock.lock(); name = newValue; lock.unlock() }
        }
    }

    func setDeviceName(_ new: String) {
        // 40 characters is plenty for a label, and the Bonjour TXT entry is limited to 255 bytes.
        let clean = String(new.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !clean.isEmpty else { return }
        UserDefaults.standard.set(clean, forKey: "device_name")
        deviceName = clean
        nameBox.value = clean
        discovery.updateAdvertisedName()
    }

    /// Called for files other apps hand us (Open in…, drag & drop). `temporary` paths are our own copies
    /// and are deleted once the files are sent, shared or discarded.
    func queueShare(_ urls: [URL], temporary: [URL] = []) {
        let files = urls.filter { $0.isFileURL }
        guard !files.isEmpty else { temporary.forEach { try? FileManager.default.removeItem(at: $0) }; return }
        clearPendingShare()
        pendingShare = files
        pendingTemporary = temporary
    }

    /// Hands the waiting files to a send/share; the receiver takes over cleanup of the temp copies.
    func takePendingShare() -> (urls: [URL], temporary: [URL]) {
        let out = (pendingShare, pendingTemporary)
        pendingShare = []; pendingTemporary = []
        return out
    }

    func clearPendingShare() {
        pendingTemporary.forEach { try? FileManager.default.removeItem(at: $0) }
        pendingShare = []; pendingTemporary = []
    }

    func setDiscoveryEnabled(_ on: Bool) {
        discoveryEnabled = on
        UserDefaults.standard.set(on, forKey: "nsd_enabled")
        if on { discovery.start(port: listener.port); vaultDiscovery.start() } else { discovery.stop(); vaultDiscovery.stop() }
    }

    func start() {
        guard !started else { return }
        started = true
        Self.sweepTemporaryFiles()
        let delta = inbox.delta
        DispatchQueue.global(qos: .utility).async { delta.prunePool() }     // drop stale resume chunks from failed transfers
        do {
            let coordinator = self.coordinator
            try listener.start { socket, _ in
                DispatchQueue.main.async { coordinator.handleInbound(socket) }
            }
            listenerPort = listener.port
        } catch {
            networkNotice = "could not start listener: \(error)"
        }
        if discoveryEnabled { discovery.start(port: listener.port); vaultDiscovery.start() }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Wi-Fi changed (new network / IP): rebind mDNS so we advertise and browse on the right interface.
            let sig = path.availableInterfaces.map(\.name).joined(separator: ",") + "|\(path.status)"
            DispatchQueue.main.async {
                guard let self, sig != self.lastPathSignature else { return }
                let first = self.lastPathSignature.isEmpty
                self.lastPathSignature = sig
                if !first { self.restartDiscovery() }
            }
        }
        pathMonitor.start(queue: .global(qos: .utility))
        coordinator.onOfferArrived = { [weak self] offer in self?.notifyOffer(offer) }
        // Ask for notifications in context — after the first finished transfer, not at launch.
        if !UserDefaults.standard.bool(forKey: "asked_notifications") {
            notificationWatch = coordinator.$session.sink { [weak self] s in
                guard let s, s.status.isTerminal else { return }
                UserDefaults.standard.set(true, forKey: "asked_notifications")
                self?.requestNotificationPermission()
                self?.notificationWatch = nil
            }
        }
        #if DEBUG
        NSLog("CrossDrop listening on :%d id=%@", listener.port, instanceId)
        applyDebugArguments()
        #endif
    }

    /// Photo-picker exports and vault previews are normally deleted when their session ends; this catches
    /// the ones a crash or kill left behind.
    private static func sweepTemporaryFiles() {
        let tmp = FileManager.default.temporaryDirectory
        for name in (try? FileManager.default.contentsOfDirectory(atPath: tmp.path)) ?? []
        where name.hasPrefix("picked-") || name.hasPrefix("vault-preview-") {
            try? FileManager.default.removeItem(at: tmp.appendingPathComponent(name))
        }
    }

    #if DEBUG
    /// Launch args (`xcrun simctl launch … -dev_peer host:port`, `-dev_send host:port`) for simulator testing.
    private func applyDebugArguments() {
        let args = UserDefaults.standard
        func hostPort(_ s: String) -> (String, Int)? {
            let p = s.split(separator: ":", maxSplits: 1).map(String.init)
            guard p.count == 2, let port = Int(p[1]) else { return nil }
            return (p[0], port)
        }
        if let v = args.string(forKey: "dev_peer"), let (h, p) = hostPort(v) { addManualPeer(name: "dev \(h)", host: h, port: p) }
        if let v = args.string(forKey: "dev_send"), let (h, p) = hostPort(v) {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("self-test.bin")
            let bytes = Data((0..<(4 * 1024 * 1024)).map { UInt8($0 % 251) })
            try? bytes.write(to: url)
            let peer = Peer(instanceId: "dev-send", name: "dev \(h)", host: h, port: p, sources: [.manual])
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.coordinator.sendTo(peer, urls: [url]) }
        }
        if args.bool(forKey: "dev_share") { DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.qrShare.startDebug() } }
        if args.bool(forKey: "dev_autoaccept") { coordinator.autoAcceptAll = true }
        // Screenshot drivers.
        if args.bool(forKey: "dev_seed_inbox") { inbox.seedDebug() }
        if let kind = args.string(forKey: "dev_fake_session") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.coordinator.debugFakeSession(kind) }
        }
        if args.bool(forKey: "dev_fake_offer") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.coordinator.debugFakeOffer() }
        }
        // Vault client: `-dev_vault_peer host:port` puts a beacon on the radar; `-dev_vault_mount host:port`
        // mounts straight away (`-dev_vault_pin 1234` supplies the PIN).
        if let v = args.string(forKey: "dev_vault_peer"), let (h, p) = hostPort(v) {
            let id = "debug-vault-\(h):\(p)"
            vaultBeacons[id] = Peer(instanceId: id, name: "Vault @\(h):\(p)", host: h, port: p, sources: [.manual],
                                    vault: VaultDescriptor(vaultName: "Vault @\(h):\(p)", writable: true, needsPin: args.string(forKey: "dev_vault_pin") != nil))
            mergePeers()
        }
        if let v = args.string(forKey: "dev_vault_mount"), let (h, p) = hostPort(v) {
            let pin = args.string(forKey: "dev_vault_pin")
            let peer = Peer(instanceId: "debug-vault", name: "vault@\(h):\(p)", host: h, port: p, sources: [.manual],
                            vault: VaultDescriptor(vaultName: "vault@\(h):\(p)", writable: true, needsPin: pin != nil))
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { self.vaultExplorer.mount(peer, pin: pin) }
            if let script = args.string(forKey: "dev_vault_script") { DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { self.vaultExplorer.runDebugScript(script) } }
        }
        if let screen = args.string(forKey: "dev_open"), screen.hasPrefix("drive") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.driveOpen = true }
        }
        if let mode = args.string(forKey: "dev_vault") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.vaultHost.startDebug(mode: mode) }
        }
    }
    #endif

    /// Foregrounded again: sockets/mDNS may have been torn down while suspended.
    func resume() {
        guard started else { return }
        inbox.refresh()
        KeepAwake.shared.refresh()
        qrShare.resume()
        vaultHost.resume()
        var rebound = false
        // iOS may have revoked the listening socket while we were suspended. Only if nobody answers on it
        // any more do we rebind (a new port) — in-flight sessions use their own accepted sockets.
        if listener.port == 0 || (wasBackgrounded && !NetProbe.isListening(port: listener.port)) {
            listener.stop()
            try? listener.start { [weak self] socket, _ in
                DispatchQueue.main.async { self?.coordinator.handleInbound(socket) }
            }
            listenerPort = listener.port
            rebound = true
        }
        // mDNS registrations don't survive suspension either; also retry after a Local Network denial
        // (the user may have just flipped the switch in Settings).
        if rebound || wasBackgrounded || networkNotice != nil { restartDiscovery() }
        wasBackgrounded = false
    }

    private var wasBackgrounded = false
    func didEnterBackground() { wasBackgrounded = true }

    func restartDiscovery() {
        guard discoveryEnabled, listener.port > 0 else { return }
        discovery.start(port: listener.port)
        vaultDiscovery.start()
    }

    // MARK: manual peers (debug / "add by address")

    func addManualPeer(name: String?, host: String, port: Int) {
        let id = "manual-\(host):\(port)"
        manual[id] = Peer(instanceId: id, name: name ?? "\(host):\(port)", host: host, port: port, sources: [.manual])
        mergePeers()
    }

    private func sightedPeers() -> [Peer] {
        var merged = bonjour
        for (k, v) in vaultBeacons { merged[k] = v }
        for (k, v) in manual { merged[k] = v }
        return Array(merged.values)
    }

    private func mergePeers() {
        let sighted = sightedPeers()
        peers = sighted.filter(liveness.admits)
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        if !peers.isEmpty { networkNotice = nil }       // discovery clearly works now
        if sighted.contains(where: { $0.probeable && !liveness.admits($0) }) { liveness.poke() }   // verify newcomers now
    }

    // MARK: notifications

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notifyOffer(_ offer: IncomingOffer) {
        guard UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = "\(offer.peerName) wants to send you files"
        let n = offer.offer.files.count
        c.body = n == 1 ? offer.offer.files[0].name : "\(n) files"
        c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: offer.id.uuidString, content: c, trigger: nil))
    }
}

import UserNotifications
