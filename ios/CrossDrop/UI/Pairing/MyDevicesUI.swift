import SwiftUI
@preconcurrency import CrossDropCore

/// How asking another device to pair ended.
enum AddDeviceResult { case paired, declined, failed }

/// Settings → "My devices": the devices this one is paired with, each with "Accept files without asking", "Share clipboard"
/// and Unpair, plus "Add a device". Twin of the Kotlin `MyDevicesSection` (keep the texts in step).
struct MyDevicesSection: View {
    @EnvironmentObject var container: AppContainer
    @ObservedObject var pairings: PairingStore
    @ObservedObject var clipboard: ClipboardSync
    @State private var adding = false
    @State private var unpairing: PairedDevice?

    var body: some View {
        let devices = pairings.devices.filter { !$0.backup }
        SectionLabel(tr("My devices")).padding(.top, 4)
        GlassCard(radius: 26, fill: CD.glassLow, padding: .hv(16, 2)) {
            ForEach(devices) { d in
                HStack(spacing: 12) {
                    CdIconView(deviceIconFor(d.name), color: CD.textPrimary, size: 22)
                    Text(d.name).body(14, weight: .semibold).lineLimit(1)
                    Spacer(minLength: 4)
                    ChipButton(text: tr("Unpair"), height: 32, fontSize: 12, label: tr("Unpair {0}", d.name)) { unpairing = d }
                }
                .padding(.top, 12)
                ToggleRow(title: tr("Accept files without asking"), subtitle: tr("Only from {0}", d.name),
                          isOn: Binding(get: { d.accept }, set: { container.setAccept(d.id, $0) }))
                    .padding(.vertical, 8)
                ToggleRow(title: tr("Share clipboard"), subtitle: clipboardLine(d),
                          isOn: Binding(get: { d.clipboard }, set: { container.setClipboard(d.id, $0) }))
                    .padding(.bottom, 10)
                RowSeparator()
            }
            Button { adding = true } label: {
                HStack(spacing: 12) {
                    CdIconView(CdIcons.link, color: CD.accent, size: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Add a device")).body(14, weight: .semibold)
                        if devices.isEmpty {
                            Text(tr("Your own phones and computers can then send each other files without asking.")).body(12, CD.textDim)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 4)
                    CdIconView(CdIcons.chevronRight, color: CD.textDim, size: 18)
                }
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle())
            .accessibilityElement(children: .combine)
            .pointerHover()
        }
        .sheet(isPresented: $adding) { AddDeviceSheet() }
        .confirmDialog(tr("Unpair {0}?", unpairing?.name ?? ""),
                       isPresented: Binding(get: { unpairing != nil }, set: { if !$0 { unpairing = nil } }),
                       message: tr("{0} will have to ask before sending files.", unpairing?.name ?? ""),
                       confirmLabel: tr("Unpair")) {
            if let d = unpairing { container.unpair(d.id) }
            unpairing = nil
        }
    }

    private func clipboardLine(_ d: PairedDevice) -> String {
        let s = clipboard.status[d.id]
        if !d.clipboard && s == .wanted { return tr("{0} is sharing its clipboard. Turn this on to get it.", d.name) }
        if !d.clipboard { return tr("Copy on one device, paste on the other (text)") }
        if s == .offThere { return tr("Off on {0}: turn it on there too", d.name) }
        if s == .oldVersion { return tr("Update CrossDrop on {0} first", d.name) }
        #if targetEnvironment(macCatalyst)
        return tr("Copy on one device, paste on the other (text)")
        #else
        return tr("This {0} shares a copy when you tap Paste on the radar, and gets copies while CrossDrop is open.", Platform.deviceNoun)
        #endif
    }
}

/// Pick a device on the radar, then pair with it: both screens show the same 6-digit code and the other person agrees.
/// Leaving the sheet cancels a request still waiting.
struct AddDeviceSheet: View {
    @EnvironmentObject var container: AppContainer
    @Environment(\.dismiss) private var dismiss
    private enum Phase: Equatable { case pick, waiting(String, String?), done(String, AddDeviceResult) }
    @State private var phase: Phase = .pick
    @State private var token: CancelToken?

    var body: some View {
        let devices = container.peers.filter { $0.isReachable && $0.vault == nil }
        ZStack {
            CD.inkSheet.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text(title).displayStyle(22).accessibilityAddTraits(.isHeader).lineLimit(2)
                        Spacer()
                        if case .done(_, .paired) = phase {
                            ChipButton(text: tr("Done"), fg: CD.onAccent, bg: CD.accent) { close() }
                        } else {
                            ChipButton(text: tr("Cancel")) { close() }
                        }
                    }
                    switch phase {
                    case .pick:
                        Text(devices.isEmpty ? tr("No devices found. Open CrossDrop on the other device, on this Wi-Fi.")
                             : tr("Pick a device that has CrossDrop open. Both screens show a code: check that they match."))
                            .body(14, CD.textSecondary).fixedSize(horizontal: false, vertical: true)
                        ForEach(devices) { p in
                            Button { start(p) } label: {
                                HStack(spacing: 12) {
                                    CdIconView(deviceIconFor(p.name), color: CD.textPrimary, size: 22)
                                    Text(p.name).body(15, weight: .semibold).lineLimit(1)
                                    Spacer(minLength: 4)
                                    CdIconView(CdIcons.chevronRight, color: CD.textDim, size: 18)
                                }
                                .padding(.vertical, 12).padding(.horizontal, 14)
                                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(CD.glassLow))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressableStyle())
                            .pointerHover()
                        }
                    case .waiting(let name, let code):
                        Text(code.map(Self.spaced) ?? tr("Connecting…")).monoStyle(36, color: CD.accent)
                            .frame(maxWidth: .infinity).padding(.vertical, 18)
                            .accessibilityLabel(code ?? tr("Connecting…"))
                        Text(tr("Ask {0} to check the code and tap Pair.", name)).body(14, CD.textSecondary)
                            .multilineTextAlignment(.center).frame(maxWidth: .infinity)
                    case .done(_, let result):
                        Text(result == .paired ? tr("You can now send each other files without asking. Turn that off any time in Settings.")
                             : result == .declined ? FailureAdvice.declinedHint()
                             : tr("Open CrossDrop on that device and make sure you're both on the same Wi-Fi."))
                            .body(14, result == .paired ? CD.textSecondary : CD.dangerText).fixedSize(horizontal: false, vertical: true)
                        if result != .paired {
                            ChipButton(text: tr("Try again"), fg: CD.onAccent, bg: CD.accent) { phase = .pick }
                        }
                    }
                }
                .frame(maxWidth: 560)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
        }
        .presentationDetents([.medium, .large])
        .onDisappear { token?.cancel() }
    }

    private var title: String {
        switch phase {
        case .pick: return tr("Add a device")
        case .waiting(let name, _): return tr("Pairing with {0}", name)
        case .done(let name, .paired): return tr("Paired with {0}", name)
        case .done(let name, .declined): return tr("{0} didn't pair", name)
        case .done(let name, .failed): return tr("Couldn't reach {0}", name)
        }
    }

    private func start(_ peer: Peer) {
        phase = .waiting(peer.name, nil)
        token = container.pair(with: peer, onCode: { code in
            if case .waiting(let n, _) = phase { phase = .waiting(n, code) }
        }, done: { result in
            if case .waiting = phase { phase = .done(peer.name, result) }
        })
    }

    private func close() { token?.cancel(); dismiss() }

    static func spaced(_ code: String) -> String {
        code.count == 6 ? "\(code.prefix(3)) \(code.suffix(3))" : code
    }
}

/// A device asks to become one of "My devices". Both screens show the same code; agreeing only makes sense when they match.
/// Twin of the Kotlin `PairPromptSheet`.
struct PairPromptView: View {
    let prompt: PairPrompt
    let onAnswer: (Bool) -> Void

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.22, centerY: 220, radius: 280)
            ScrollView {
                VStack(spacing: 16) {
                    IconTile(icon: CdIcons.link, tint: CD.accent, size: 64, radius: 22, iconSize: 30)
                    Text(tr("Pair with {0}?", prompt.peerName)).displayStyle(26).multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Text(tr("{0} will be able to send you files without asking each time. You can turn that off in Settings.", prompt.peerName))
                        .body(14, CD.textSecondary).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    GlassCard(radius: 26, fill: CD.ink.opacity(0.6), padding: .all(18), spacing: 6) {
                        Text(tr("Same code on both screens?")).body(13, CD.textSecondary).frame(maxWidth: .infinity)
                        Text(AddDeviceSheet.spaced(prompt.sas)).monoStyle(38, color: CD.accent).frame(maxWidth: .infinity)
                            .accessibilityLabel(prompt.sas)
                    }
                    FootNote(tr("Pair only with your own devices. You can unpair at any time in Settings."), icon: CdIcons.shield)
                    HStack(spacing: 10) {
                        PillButton(tr("Don't pair"), style: .glass) { onAnswer(false) }
                        PillButton(tr("Pair"), glow: true) { onAnswer(true) }
                    }
                }
                .frame(maxWidth: 480)
                .padding(20)
                .padding(.top, 40)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// iPhone/iPad, on the radar: something new was copied. The system Paste button hands it over without the "Allow Paste"
/// prompt (the person tapped it), and it goes to the devices with "Share clipboard" on.
struct ClipboardShareCard: View {
    @ObservedObject var share: PasteboardShare
    @ObservedObject var pairings: PairingStore

    var body: some View {
        let names = pairings.devices.filter(\.clipboard).map(\.name).sorted().joined(separator: ", ")
        if share.waiting {
            GlassCard(radius: 24, border: CD.accent.opacity(0.35), padding: .all(14), spacing: 12) {
                HStack(spacing: 12) {
                    IconTile(icon: CdIcons.copy, tint: CD.accent, size: 40, radius: 14, iconSize: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("You copied something")).body(14, weight: .semibold)
                        Text(tr("Tap Paste to send it to {0}.", names)).body(12, CD.textDim).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                HStack(spacing: 10) {
                    PasteButton(payloadType: String.self) { strings in
                        DispatchQueue.main.async { share.share(strings) }
                    }
                    .buttonBorderShape(.capsule)
                    .tint(CD.accent)
                    .labelStyle(.titleAndIcon)
                    ChipButton(text: tr("Not now")) { share.dismiss() }
                    Spacer(minLength: 0)
                }
            }
            .accessibilityElement(children: .contain)
        } else if let note = share.note {
            HStack(spacing: 10) {
                CdIconView(CdIcons.copy, color: CD.accent, size: 16)
                Text(note).body(13, CD.textSecondary).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(CD.glassLow))
            .accessibilityElement(children: .combine)
        }
    }
}
