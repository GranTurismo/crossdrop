import UIKit
import SwiftUI
import UniformTypeIdentifiers
import Darwin
@preconcurrency import CrossDropCore

/// "Share → CrossDrop" from any app on iPhone, iPad or Mac. The sheet is already scanning when it
/// opens: tap a device and the send starts right here (an extension can't hand off to its app), tick
/// several to send to all of them. Links and text go as text, so the receiver can copy or open them.
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        model.onFinish = { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        let host = UIHostingController(rootView: ShareRoot(model: model))
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        model.load(extensionContext?.inputItems as? [NSExtensionItem] ?? [])
        model.browse()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        model.stopBrowsing()
    }
}

// MARK: - Model

struct ShareDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let port: Int
}

@MainActor
final class ShareModel: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    enum Payload { case files([URL]), text(String) }
    struct Row: Identifiable { let id: String; let name: String; var status: String; var fraction: Double; var done: Bool; var failed: Bool }

    @Published var payload: Payload?
    @Published var preparing = true
    @Published var devices: [ShareDevice] = []
    @Published var selected: Set<String> = []
    @Published var rows: [Row] = []
    @Published var error: String?
    var onFinish: (() -> Void)?

    private var browser: NetServiceBrowser?
    private var resolving: [String: NetService] = [:]
    private let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)", isDirectory: true)
    private let engine: TransferEngine = {
        var b = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        let name = ShareModel.deviceName()
        return TransferEngine(identity: SessionIdentity(), deviceName: { name }, instanceId: b.map { String(format: "%02x", $0) }.joined())
    }()

    var summary: String {
        switch payload {
        case .files(let urls)?: return urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) items"
        case .text(let t)?: return t.replacingOccurrences(of: "\n", with: " ")
        case nil: return preparing ? "Preparing…" : "Nothing to send"
        }
    }

    var isLink: Bool {
        if case .text(let t)? = payload {
            let s = t.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            return !s.contains(" ") && (s.hasPrefix("http://") || s.hasPrefix("https://"))
        }
        return false
    }

    nonisolated static func deviceName() -> String {
        #if targetEnvironment(macCatalyst)
        var host = ProcessInfo.processInfo.hostName
        if host.hasSuffix(".local") { host.removeLast(6) }
        return host.replacingOccurrences(of: "-", with: " ").capitalized
        #else
        return UIDevice.current.name
        #endif
    }

    // MARK: input

    func load(_ items: [NSExtensionItem]) {
        let providers = items.flatMap { $0.attachments ?? [] }
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        Task {
            var files: [URL] = []
            var text: String?
            for p in providers {
                if let url = await Self.loadFile(p, into: tmp) { files.append(url); continue }
                if text == nil, let t = await Self.loadText(p) { text = t }
            }
            if !files.isEmpty { payload = .files(files) } else if let text { payload = .text(text) }
            preparing = false
        }
    }

    private static func loadFile(_ p: NSItemProvider, into dir: URL) async -> URL? {
        let fileTypes = [UTType.fileURL, .movie, .image, .pdf, .data, .item].map(\.identifier)
        let plainOnly = !p.hasItemConformingToTypeIdentifier(UTType.data.identifier) && !p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
        if plainOnly { return nil }                      // a bare link or text — sent as text instead
        guard let type = fileTypes.first(where: { p.hasItemConformingToTypeIdentifier($0) }) else { return nil }
        if let url: URL = await withCheckedContinuation({ cont in
            _ = p.loadFileRepresentation(forTypeIdentifier: type == UTType.fileURL.identifier ? UTType.item.identifier : type) { url, _ in
                guard let url else { cont.resume(returning: nil); return }
                let d = dir.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
                let dest = d.appendingPathComponent(url.lastPathComponent)
                cont.resume(returning: (try? FileManager.default.copyItem(at: url, to: dest)) != nil ? dest : nil)
            }
        }) { return url }
        // Some apps share pictures as in-memory data only (screenshot editor, Markup).
        if p.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return await withCheckedContinuation { cont in
                _ = p.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                    guard let data else { cont.resume(returning: nil); return }
                    let isPNG = data.starts(with: [0x89, 0x50, 0x4E, 0x47])
                    let dest = dir.appendingPathComponent("Image-\(UUID().uuidString.prefix(6)).\(isPNG ? "png" : "jpg")")
                    cont.resume(returning: (try? data.write(to: dest)) != nil ? dest : nil)
                }
            }
        }
        return nil
    }

    private static func loadText(_ p: NSItemProvider) async -> String? {
        for type in [UTType.url, .plainText, .text] where p.hasItemConformingToTypeIdentifier(type.identifier) {
            let value: String? = await withCheckedContinuation { cont in
                _ = p.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                    if let u = item as? URL { cont.resume(returning: u.absoluteString) }
                    else if let s = item as? String { cont.resume(returning: s) }
                    else if let d = item as? Data { cont.resume(returning: String(data: d, encoding: .utf8)) }
                    else { cont.resume(returning: nil) }
                }
            }
            if let value, !value.isEmpty { return value }
        }
        return nil
    }

    // MARK: discovery

    func browse() {
        let b = NetServiceBrowser()
        b.delegate = self
        b.searchForServices(ofType: "_crossdrop._tcp.", inDomain: "local.")
        browser = b
    }

    func stopBrowsing() {
        browser?.stop(); browser = nil
        resolving.values.forEach { $0.stop() }
        resolving = [:]
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        MainActor.assumeIsolated {
            resolving[service.name] = service
            service.delegate = self
            service.resolve(withTimeout: 6)
        }
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        MainActor.assumeIsolated {
            let name = service.name
            resolving[name] = nil
            devices.removeAll { $0.id == Self.idFor(name, txt: nil) }
        }
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        MainActor.assumeIsolated { error = "CrossDrop can't search the network. Open the CrossDrop app once and allow Local Network access." }
    }

    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        MainActor.assumeIsolated {
            let txt = sender.txtRecordData().map { NetService.dictionary(fromTXTRecord: $0) } ?? [:]
            func str(_ k: String) -> String? { txt[k].flatMap { String(data: $0, encoding: .utf8) } }
            guard let host = Self.pickHost(sender.addresses ?? []) else { return }
            let id = Self.idFor(sender.name, txt: str("id"))
            let d = ShareDevice(id: id, name: str("name").flatMap { $0.isEmpty ? nil : $0 } ?? "Nearby device", host: host, port: sender.port)
            devices.removeAll { $0.id == id }
            devices.append(d)
            devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    nonisolated private static func idFor(_ serviceName: String, txt: String?) -> String {
        txt ?? serviceName.replacingOccurrences(of: "crossdrop-", with: "")
    }

    nonisolated private static func pickHost(_ addrs: [Data]) -> String? {
        var v6: String?
        for data in addrs {
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let family: Int32 = data.withUnsafeBytes { Int32($0.load(as: sockaddr.self).sa_family) }
            let rc: Int32 = data.withUnsafeBytes { raw in
                getnameinfo(raw.baseAddress!.assumingMemoryBound(to: sockaddr.self), socklen_t(data.count),
                            &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
            guard rc == 0 else { continue }
            let s = String(cString: host)
            if family == AF_INET { return s }
            if family == AF_INET6, v6 == nil { v6 = s }
        }
        return v6
    }

    // MARK: sending

    func toggle(_ d: ShareDevice) { if selected.contains(d.id) { selected.remove(d.id) } else { selected.insert(d.id) } }

    func send(to targets: [ShareDevice]) {
        guard let payload, !targets.isEmpty else { return }
        var files: [OutgoingFile] = []
        var options = SendOptions()
        switch payload {
        case .files(let urls):
            for u in urls {
                let size = Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                files.append(OutgoingFile(meta: FileMeta(name: u.lastPathComponent, size: size,
                                                         mime: UTType(filenameExtension: u.pathExtension)?.preferredMIMEType), url: u))
            }
        case .text(let t):
            let url = tmp.appendingPathComponent(isLink ? "Link.txt" : "Text.txt")
            try? Data(t.utf8).write(to: url)
            files = [OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(t.utf8.count), mime: "text/plain"), url: url)]
            options = SendOptions(kind: OfferKind.text, text: t)
        }
        rows = targets.map { Row(id: $0.id, name: $0.name, status: "Connecting…", fraction: 0, done: false, failed: false) }
        let engine = self.engine
        for d in targets {
            let opts = options, fs = files
            Thread.detachNewThread { [weak self] in
                engine.send(host: d.host, port: d.port, files: fs, options: opts) { e in
                    DispatchQueue.main.async { self?.update(d.id, e) }
                }
            }
        }
    }

    private func update(_ id: String, _ e: TransferEvent) {
        guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
        switch e {
        case .awaitingAccept: rows[i].status = "Waiting for them to accept"
        case .progress(let d, let t, _, _, _): rows[i].status = "\(Int(Double(d) / Double(max(t, 1)) * 100))%"; rows[i].fraction = Double(d) / Double(max(t, 1))
        case .completed: rows[i].status = "Sent"; rows[i].fraction = 1; rows[i].done = true
        case .declinedByPeer: rows[i].status = "Declined"; rows[i].failed = true
        case .cancelled: rows[i].status = "Cancelled"; rows[i].failed = true
        case .failed(let m): rows[i].status = "Failed: \(m)"; rows[i].failed = true
        default: break
        }
        if rows.allSatisfy(\.done) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in self?.finish() }
        }
    }

    func finish() {
        try? FileManager.default.removeItem(at: tmp)
        onFinish?()
    }
}

// MARK: - UI

private enum Palette {
    static let ink = Color(red: 0.024, green: 0.031, blue: 0.047)
    static let sheet = Color(red: 0.075, green: 0.09, blue: 0.114)
    static let accent = Color(red: 0.361, green: 0.949, blue: 0.753)
    static let onAccent = Color(red: 0.016, green: 0.075, blue: 0.051)
    static let dim = Color(red: 0.604, green: 0.651, blue: 0.698)
    static let danger = Color(red: 1, green: 0.608, blue: 0.608)
}

struct ShareRoot: View {
    @ObservedObject var model: ShareModel

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 16) {
                Capsule().fill(Color.white.opacity(0.18)).frame(width: 40, height: 5).frame(maxWidth: .infinity)
                HStack(spacing: 12) {
                    Image(systemName: model.isLink ? "link" : "arrow.up")
                        .font(.system(size: 20, weight: .semibold)).foregroundColor(Palette.accent)
                        .frame(width: 46, height: 46)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.accent.opacity(0.14)))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send with CrossDrop").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                        Text(model.summary).font(.system(size: 12)).foregroundColor(Palette.dim).lineLimit(1)
                    }
                }
                if !model.rows.isEmpty { progress } else { picker }
            }
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 34, style: .continuous).fill(Palette.sheet))
            .frame(maxWidth: 520)
            .padding(8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { if model.rows.isEmpty { model.finish() } })
        .preferredColorScheme(.dark)
    }

    private var picker: some View {
        VStack(spacing: 8) {
            if let e = model.error { Text(e).font(.system(size: 13)).foregroundColor(Palette.danger) }
            if model.devices.isEmpty {
                HStack(spacing: 12) {
                    ProgressView().tint(Palette.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Looking for devices…").font(.system(size: 14, weight: .semibold)).foregroundColor(.white)
                        Text("Open CrossDrop on the other device, on this Wi-Fi").font(.system(size: 12)).foregroundColor(Palette.dim)
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
            }
            ScrollView {
                VStack(spacing: 8) {
                    ForEach(model.devices) { d in deviceRow(d) }
                }
            }
            .frame(maxHeight: 320)
            if !model.selected.isEmpty {
                Button {
                    model.send(to: model.devices.filter { model.selected.contains($0.id) })
                } label: {
                    Text("Send to \(model.selected.count) device\(model.selected.count == 1 ? "" : "s")")
                        .font(.system(size: 15, weight: .semibold)).foregroundColor(Palette.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Capsule().fill(Palette.accent))
                }
                .buttonStyle(.plain)
                .disabled(model.payload == nil)
            }
            Button("Cancel") { model.finish() }
                .font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(Capsule().fill(Color.white.opacity(0.07)))
                .buttonStyle(.plain)
        }
    }

    private func deviceRow(_ d: ShareDevice) -> some View {
        let on = model.selected.contains(d.id)
        return HStack(spacing: 12) {
            Image(systemName: icon(d.name)).font(.system(size: 18)).foregroundColor(.white)
                .frame(width: 40, height: 40).background(Circle().fill(Color.white.opacity(0.08)))
            Text(d.name).font(.system(size: 15, weight: .semibold)).foregroundColor(.white).lineLimit(1)
            Spacer()
            Button { model.toggle(d) } label: {
                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 24)).foregroundColor(on ? Palette.accent : Palette.dim)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(on ? "Selected" : "Select")
        }
        .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(on ? Palette.accent.opacity(0.14) : Color.white.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(on ? Palette.accent : Color.white.opacity(0.08), lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            // One tap sends straight away; once something is ticked, taps add to the selection.
            if model.selected.isEmpty { model.send(to: [d]) } else { model.toggle(d) }
        }
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Sends to this device")
    }

    private var progress: some View {
        VStack(spacing: 12) {
            ForEach(model.rows) { r in
                VStack(spacing: 6) {
                    HStack {
                        Image(systemName: r.done ? "checkmark.circle.fill" : icon(r.name))
                            .foregroundColor(r.done ? Palette.accent : .white)
                        Text(r.name).font(.system(size: 14, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                        Spacer()
                        Text(r.status).font(.system(size: 13, weight: .semibold))
                            .foregroundColor(r.failed ? Palette.danger : r.done ? Palette.accent : Palette.dim).lineLimit(1)
                    }
                    ProgressView(value: r.fraction).tint(Palette.accent)
                }
            }
            // Closing the sheet ends the extension, and the send with it.
            Button(model.rows.allSatisfy({ $0.done || $0.failed }) ? "Done" : "Cancel") { model.finish() }
                .font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(Capsule().fill(Color.white.opacity(0.07)))
                .buttonStyle(.plain)
            Text("Keep this open until it's sent.").font(.system(size: 12)).foregroundColor(Palette.dim)
        }
    }

    private func icon(_ name: String) -> String {
        let n = name.lowercased()
        if n.contains("mac") || n.contains("book") || n.contains("laptop") { return "laptopcomputer" }
        if n.contains("ipad") || n.contains("tab") { return "ipad" }
        if n.contains("pc") || n.contains("desktop") || n.contains("windows") { return "desktopcomputer" }
        return "iphone"
    }
}
