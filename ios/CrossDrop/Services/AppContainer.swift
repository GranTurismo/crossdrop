import Foundation
import Contacts
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
    /// A Wi-Fi/Ethernet network (or this device's own Personal Hotspot) exists to find other devices on; drives the radar's help.
    @Published private(set) var hasNetwork = true
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
    /// "Switch phones": this device's library out, or another device's library in.
    let switchSender: SwitchSender
    let switchImporter: SwitchImporter
    /// Contact cards sent on their own: straight into the address book.
    let contactInbox: ContactInbox
    /// Live camera open, sending to these devices.
    @Published var cameraTargets: [Peer]?
    /// "Switch phones" screen open for this device.
    @Published var switchTarget: Peer?

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
        switchImporter = SwitchImporter(inbox: inbox, history: history)
        switchSender = SwitchSender(coordinator: coordinator, history: history)
        coordinator.switchReceiver = switchImporter
        switchImporter.attach(coordinator)
        contactInbox = ContactInbox(inbox: inbox)
        coordinator.contactReceiver = contactInbox
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

    /// True when an interface that other devices can share is up with a usable address: Wi-Fi or Ethernet (`en*`),
    /// or the bridge Personal Hotspot creates. Public and IPv6-only networks count (see `LocalAddress`); cellular
    /// alone doesn't, because phones on it can't see each other. If the interfaces can't be read we say yes,
    /// so the app never nags on a guess.
    nonisolated static func localNetworkUp() -> Bool {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return true }
        defer { freeifaddrs(list) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cursor {
            let ifa = p.pointee
            cursor = ifa.ifa_next
            let name = String(cString: ifa.ifa_name)
            let up = Int32(ifa.ifa_flags) & IFF_UP != 0 && Int32(ifa.ifa_flags) & IFF_LOOPBACK == 0
            guard up, name.hasPrefix("en") || name.hasPrefix("bridge"), let addr = ifa.ifa_addr else { continue }
            switch Int32(addr.pointee.sa_family) {
            case AF_INET:
                let host = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
                if LocalAddress.isShareableIPv4(host) { return true }
            case AF_INET6:
                let bytes = addr.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { ptr in
                    withUnsafeBytes(of: ptr.pointee.sin6_addr) { Array($0) }
                }
                if LocalAddress.isShareableIPv6(bytes) { return true }
            default: break
            }
        }
        return false
    }

    /// The first-run welcome is shown once; anyone who has already transferred something has seen enough.
    /// Discovery (and with it the Local Network prompt) waits until it is done, so the tour explains first.
    static var welcomeSeen: Bool {
        get {
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "dev_skip_welcome") { return true }
            if UserDefaults.standard.bool(forKey: "dev_welcome") { return false }
            #endif
            return UserDefaults.standard.bool(forKey: "welcome_seen") || HistoryStore.hasEntries
        }
        set { UserDefaults.standard.set(newValue, forKey: "welcome_seen") }
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
            networkNotice = tr("could not start listener: {0}", String(describing: error))
        }
        if discoveryEnabled && Self.welcomeSeen { discovery.start(port: listener.port); vaultDiscovery.start() }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Wi-Fi changed (new network / IP): rebind mDNS so we advertise and browse on the right interface.
            let sig = path.availableInterfaces.map(\.name).joined(separator: ",") + "|\(path.status)"
            DispatchQueue.main.async {
                self?.hasNetwork = Self.localNetworkUp()
                guard let self, sig != self.lastPathSignature else { return }
                let first = self.lastPathSignature.isEmpty
                self.lastPathSignature = sig
                if !first { self.restartDiscovery() }
            }
        }
        hasNetwork = Self.localNetworkUp()
        pathMonitor.start(queue: .global(qos: .utility))
        coordinator.onOfferArrived = { [weak self] offer in self?.notifyOffer(offer) }
        // Ask for notifications in context — after the first finished transfer, not at launch.
        if !UserDefaults.standard.bool(forKey: "asked_notifications") {
            notificationWatch = coordinator.$sessions.sink { [weak self] list in
                guard list.contains(where: { $0.status.isTerminal }) else { return }
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
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.debugSend(host: h, port: p) }
        }
        // `-dev_send_multi h:p,h:p` sends the self-test file to several devices at once;
        // `-dev_text h:p` sends a link; `-dev_switch h:p` starts Switch phones (photos + contacts).
        func peers(_ list: String) -> [Peer] {
            list.split(separator: ",").compactMap { hostPort(String($0)) }.map {
                Peer(instanceId: "dev-\($0.0):\($0.1)", name: "dev \($0.0):\($0.1)", host: $0.0, port: $0.1, sources: [.manual])
            }
        }
        if let v = args.string(forKey: "dev_send_multi") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("self-test.bin")
                if !FileManager.default.fileExists(atPath: url.path) {
                    try? Data((0..<(4 * 1024 * 1024)).map { UInt8($0 % 251) }).write(to: url)
                }
                self.coordinator.sendTo(peers(v), urls: [url])
            }
        }
        if let v = args.string(forKey: "dev_text") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.coordinator.sendText(peers(v), text: "https://example.com/from-iphone") }
        }
        // `-dev_send_contact h:p` sends a made-up contact card (never the address book).
        if let v = args.string(forKey: "dev_send_contact") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                let c = CNMutableContact()
                c.givenName = "Test"; c.familyName = "Card"; c.organizationName = "CrossDrop QA"
                c.phoneNumbers = [CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: "+1 555 0199"))]
                c.emailAddresses = [CNLabeledValue(label: nil, value: "test.card@example.com")]
                self.coordinator.sendContacts(peers(v), vcf: ContactsBridge.vcard(c), names: ["Test Card"])
            }
        }
        if let v = args.string(forKey: "dev_switch"), let p = peers(v).first {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                self.switchTarget = p
                self.switchSender.start(p, photos: true, videos: true, contacts: true)
            }
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

    /// Sends a generated 4 MiB file to `host:port` (`-dev_send`, Settings → Developer).
    func debugSend(host: String, port: Int) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("self-test.bin")
        if !FileManager.default.fileExists(atPath: url.path) {
            try? Data((0..<(4 * 1024 * 1024)).map { UInt8($0 % 251) }).write(to: url)
        }
        let peer = Peer(instanceId: "dev-send", name: "dev \(host)", host: host, port: port, sources: [.manual])
        coordinator.sendTo(peer, urls: [url])
    }
    #endif

    /// Foregrounded again: sockets/mDNS may have been torn down while suspended.
    func resume() {
        vaultExplorer.livePaused = false
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
    func didEnterBackground() { wasBackgrounded = true; vaultExplorer.livePaused = true }

    func restartDiscovery() {
        guard discoveryEnabled, Self.welcomeSeen, listener.port > 0 else { return }
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
        let n = offer.offer.files.count
        switch offer.kind {
        case .some(OfferKind.text) where offer.text != nil:
            c.title = isLinkText(offer.text!) ? tr("{0} sent you a link", offer.peerName) : tr("{0} sent you some text", offer.peerName)
            c.body = String(offer.text!.prefix(120))
        case .some(OfferKind.contact):
            let cards = max(1, offer.contacts.count)
            c.title = trPlural(cards, one: "{0} sent you a contact", other: "{0} sent you {n} contacts", offer.peerName)
            c.body = offer.contacts.isEmpty ? tr("Open CrossDrop to add it") : ContactCards.title(names: offer.contacts.map(\.name))
        case .some(OfferKind.camera):
            c.title = tr("{0} wants to share their camera", offer.peerName)
            c.body = tr("Open CrossDrop to watch their photos arrive live")
        case .some(OfferKind.switchPhones):
            c.title = tr("{0} wants to move to this {1}", offer.peerName, Platform.deviceNoun)
            c.body = tr("Open CrossDrop to start")
        default:
            c.title = tr("{0} wants to send you files", offer.peerName)
            c.body = n == 1 ? offer.offer.files[0].name : trPlural(n, one: "1 file", other: "{n} files")
        }
        c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: offer.id.uuidString, content: c, trigger: nil))
    }
}

import UserNotifications
