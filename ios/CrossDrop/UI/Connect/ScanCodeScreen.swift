import SwiftUI
import AVFoundation
@preconcurrency import CrossDropCore

/// The back camera with a QR reader on it (AVFoundation's own, no third-party code). Each new code read while
/// `active` comes out once as `read`; the same code staying in view doesn't repeat.
@MainActor
final class CodeScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    enum Access { case unknown, granted, denied, noCamera }
    struct Read: Equatable { let text: String; let seq: Int }

    @Published private(set) var access: Access = .unknown
    @Published private(set) var read: Read?
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "crossdrop.scanner")
    private var lastText: String?
    private var seq = 0
    private var stopped = false

    /// Off while a code is being acted on; a new scan (after a failure) may read the same code again.
    var active = true {
        didSet { if active && !oldValue { lastText = nil } }
    }

    func start() async {
        let ok = await AVCaptureDevice.requestAccess(for: .video)
        guard ok else { access = .denied; return }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
                ?? AVCaptureDevice.default(for: .video) else { access = .noCamera; return }
        let session = self.session
        let output = AVCaptureMetadataOutput()
        output.setMetadataObjectsDelegate(self, queue: .main)
        let configured: Bool = await withCheckedContinuation { cont in
            queue.async {
                session.beginConfiguration()
                var ready = false
                if let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
                    session.addInput(input)
                    if session.canAddOutput(output) {
                        session.addOutput(output)
                        // The types a metadata output offers are only known once it is in the session.
                        if output.availableMetadataObjectTypes.contains(.qr) {
                            output.metadataObjectTypes = [.qr]
                            ready = true
                        }
                    }
                }
                session.commitConfiguration()
                if ready { session.startRunning() }
                cont.resume(returning: ready)
            }
        }
        access = configured ? .granted : .noCamera
        if stopped { stop() }       // the screen closed while the camera was starting
    }

    func stop() {
        stopped = true
        let session = self.session
        queue.async { if session.isRunning { session.stopRunning() } }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                                    from connection: AVCaptureConnection) {
        guard let text = metadataObjects.lazy.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        Task { @MainActor [weak self] in self?.deliver(text) }
    }

    private func deliver(_ text: String) {
        guard active, text != lastText else { return }
        lastText = text
        seq += 1
        read = Read(text: text, seq: seq)
    }
}

/// "Scan a code": the camera reads another phone's private-link code (or any Wi-Fi code), CrossDrop joins that network
/// and connects to the phone. Closes itself once joined. Mirrors Android's `ui/connect/ScanCodeScreen.kt`.
struct ScanCodeScreen: View {
    @ObservedObject var join: WifiJoin
    let onClose: () -> Void
    @StateObject private var scanner = CodeScanner()
    @State private var notWifi = false
    @State private var lastCode: JoinCode?

    /// Reading codes: nothing in progress. Opened while already on a link (its device never answered, so the radar
    /// sent them here), a new code replaces that link; the screen closes on a fresh join, never shows this state.
    private var scanning: Bool {
        switch join.state {
        case .idle, .failed, .joined: return true
        default: return false
        }
    }
    private var active: Bool { scanning && lastCode == nil }

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            if scanner.access == .granted {
                PreviewLayer(session: scanner.session).ignoresSafeArea()
                Color.black.opacity(0.25).ignoresSafeArea()
            }
            VStack(spacing: 0) {
                BackHeader(tr("Scan a code"), onBack: { join.dismiss(); onClose() })
                    .frame(maxWidth: 560)
                ZStack {
                    if scanner.access == .granted {
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .strokeBorder(CD.accent.opacity(0.9), lineWidth: 3)
                            .frame(width: 250, height: 250)
                            .accessibilityHidden(true)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                statusCard.frame(maxWidth: 560)
            }
            .padding(16)
        }
        .onAppear { join.dismiss(); scanner.active = active }
        .task { await scanner.start() }
        .onDisappear { scanner.stop() }
        .onChange(of: active) { scanner.active = $0 }
        .onChange(of: scanner.read) { read in
            guard let read else { return }
            if let code = WifiQr.parse(read.text) {
                notWifi = false
                lastCode = code
                join.join(code)
            } else {
                notWifi = true
            }
        }
        .onChange(of: join.state) { state in
            // The other phone answered: back to the radar, where it now shows.
            if case .joined = state { onClose(); return }
            if let spoken = announcement(state) { UIAccessibility.post(notification: .announcement, argument: spoken) }
        }
    }

    // MARK: status card

    private var statusCard: some View {
        GlassCard(fill: CD.ink.opacity(0.82), padding: .all(16), spacing: 10) {
            switch join.state {
            case .joining(let ssid):
                status(busy: true, title: tr("Joining {0}…", ssid), body: tr("Your {0} asks you to confirm. Tap Join.", Platform.deviceNoun))
            case .connecting(let ssid):
                status(busy: true, title: tr("Connected to {0}", ssid), body: tr("Reaching the other phone…"))
            case .joined:
                idle
            case .failed(let ssid, let reason):
                let (title, body) = failureText(ssid, reason)
                HStack(spacing: 8) {
                    Dot(color: CD.amber)
                    Text(title).body(15, weight: .semibold).fixedSize(horizontal: false, vertical: true)
                }
                Text(body).body(13, CD.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
                ChipButton(text: tr("Scan again")) { join.dismiss(); lastCode = nil }
            case .idle:
                idle
            }
            if case .joining = join.state { cancelButton }
            if case .connecting = join.state { cancelButton }
        }
    }

    @ViewBuilder private var idle: some View {
        switch scanner.access {
        case .denied:
            Text(tr("CrossDrop needs the camera to read the code.")).body(14, CD.textSecondary).fixedSize(horizontal: false, vertical: true)
            PillButton(tr("Open Settings")) { openSystemSettings() }
        case .noCamera:
            Text(tr("No camera available")).body(14, CD.textSecondary)
        case .unknown, .granted:
            Text(tr("Point at the code on the other phone")).body(15, weight: .semibold).fixedSize(horizontal: false, vertical: true)
            Text(notWifi ? tr("That code isn't for a Wi-Fi network. On the other phone, tap Connect directly, then Make a CrossDrop Wi-Fi.")
                 : tr("On the other phone: Connect directly, then Make a CrossDrop Wi-Fi. Any Wi-Fi code works too."))
                .body(13, notWifi ? CD.amber : CD.textDim).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var cancelButton: some View {
        PillButton(tr("Cancel"), style: .glass) { join.leave(); lastCode = nil }
    }

    private func status(busy: Bool, title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if busy { ProgressView().tint(CD.accent).controlSize(.small) } else { Dot(color: CD.accent) }
                Text(title).body(15, weight: .semibold).lineLimit(2)
            }
            if !body.isEmpty { Text(body).body(13, CD.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true) }
        }
    }

    private func failureText(_ ssid: String, _ reason: WifiJoin.Failure) -> (String, String) {
        switch reason {
        case .notJoined:
            return (tr("Couldn't join {0}", ssid),
                    tr("Is the code still on the other phone's screen, and is it close by? If asked, tap Join."))
        case .lost:
            return (tr("The link to {0} ended", ssid), tr("The other phone stopped it, or it's out of range. Scan its code again."))
        case .unsupported:
            return (tr("This network can't be joined from an app"), tr("Join it in your phone's Wi-Fi settings instead."))
        }
    }

    /// What VoiceOver says when the state moves on (the card is a live region on Android).
    private func announcement(_ state: WifiJoin.State) -> String? {
        switch state {
        case .joining(let ssid): return tr("Joining {0}…", ssid)
        case .connecting: return tr("Reaching the other phone…")
        case .failed(let ssid, let reason): return failureText(ssid, reason).0
        default: return nil
        }
    }
}

/// On the radar while this device is on another phone's private link (it scanned the code); Leave goes back to the
/// usual Wi-Fi. Mirrors Android's `JoinedLinkRow`.
struct JoinedLinkRow: View {
    @ObservedObject var join: WifiJoin

    var body: some View {
        if let link = join.state.link {
            let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
            HStack(spacing: 10) {
                CdIconView(CdIcons.wifi, color: CD.accent, size: 18)
                VStack(alignment: .leading, spacing: 1) {
                    Text(link.hostName.map { tr("Linked with {0}", $0) } ?? tr("On {0}", link.ssid))
                        .body(14, weight: .semibold).lineLimit(1)
                    Text(tr("Private link · no internet")).body(12, CD.textDim)
                }
                Spacer(minLength: 4)
                ChipButton(text: tr("Leave"), height: 34, fontSize: 13) { join.leave() }
            }
            .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 6)
            .background(shape.fill(CD.accent.opacity(0.08)))
            .overlay(shape.strokeBorder(CD.accent.opacity(0.3), lineWidth: 1))
            .accessibilityElement(children: .contain)
        }
    }
}

/// On the radar while a one-tap "Join a CrossDrop Wi-Fi" is waiting on iOS, or didn't work. Once on the network,
/// `JoinedLinkRow` takes over. The twin of Android's `JoinWifiScreen` states, as a row.
struct CrossDropWifiStatusRow: View {
    @ObservedObject var join: WifiJoin
    let onScan: () -> Void

    var body: some View {
        if join.isCrossDropWifi {
            switch join.state {
            case .joining:
                row(tint: CD.accent, busy: true, title: tr("Joining {0}…", DirectWifi.ssid),
                    body: tr("Your {0} asks you to confirm. Tap Join.", Platform.deviceNoun)) {
                    ChipButton(text: tr("Cancel"), height: 34, fontSize: 13) { join.leave() }
                }
            case .failed(_, let reason):
                let (title, body) = texts(reason)
                VStack(alignment: .leading, spacing: 8) {
                    row(tint: CD.amber, busy: false, title: title, body: body) {
                        ChipButton(text: tr("Close"), height: 34, fontSize: 13) { join.dismiss() }
                    }
                    HStack(spacing: 8) {
                        ChipButton(text: tr("Try again"), fg: CD.onAccent, bg: CD.accent, height: 34, fontSize: 13) { join.joinCrossDropWifi() }
                        ChipButton(text: tr("Scan a code instead"), height: 34, fontSize: 13) { join.dismiss(); onScan() }
                    }
                    .padding(.leading, 16)
                }
            default:
                EmptyView()
            }
        }
    }

    private func texts(_ reason: WifiJoin.Failure) -> (String, String) {
        switch reason {
        case .notJoined:
            return (tr("Couldn't find a CrossDrop Wi-Fi"),
                    tr("On the other phone, tap Connect directly, then Make a CrossDrop Wi-Fi. If asked, tap Join."))
        case .lost:
            return (tr("The CrossDrop Wi-Fi ended"), tr("The phone stopped it, or it's out of range."))
        case .unsupported:
            return (tr("This network can't be joined from an app"), tr("Join it in your phone's Wi-Fi settings instead."))
        }
    }

    private func row<Trailing: View>(tint: Color, busy: Bool, title: String, body: String,
                                     @ViewBuilder trailing: () -> Trailing) -> some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        return HStack(spacing: 10) {
            if busy { ProgressView().tint(CD.accent).scaleEffect(0.8) } else { CdIconView(CdIcons.wifi, color: tint, size: 18) }
            VStack(alignment: .leading, spacing: 1) {
                Text(title).body(14, weight: .semibold).lineLimit(2)
                Text(body).body(12, CD.textDim).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            trailing()
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
        .background(shape.fill(tint.opacity(0.08)))
        .overlay(shape.strokeBorder(tint.opacity(0.3), lineWidth: 1))
        .accessibilityElement(children: .contain)
    }
}
