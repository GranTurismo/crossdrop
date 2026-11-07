import SwiftUI

/// "Need help connecting?" — the foolproof per-platform guide. Mac (Finder), Windows (File Explorer)
/// and Browser (any device, including another iPhone/iPad via Safari). Every address/code is
/// one-tap copyable. Language stays physical: "address", "passcode".
struct DriveGuideScreen: View {
    let state: VaultHostManager.State
    let onClose: () -> Void
    @State private var tab: GuideTab = .mac

    enum GuideTab: String, CaseIterable { case mac = "Mac", windows = "Windows", browser = "Browser" }

    var body: some View {
        ReadableScroll {
            VStack(alignment: .leading, spacing: 0) {
                Button(action: onClose) { Text("‹ back").font(.cd(15)).foregroundColor(Aurora.textDim) }
                Text("Connect to your drive").font(.cd(22, weight: .bold)).foregroundColor(Aurora.textPrimary).padding(.top, 8)
                Text("pick your device — two steps, no apps to install").font(.cd(13)).foregroundColor(Aurora.textDim)
                HStack(spacing: 4) {
                    ForEach(GuideTab.allCases, id: \.self) { t in
                        Button { tab = t } label: {
                            Text(t.rawValue).font(.cd(13, weight: .semibold))
                                .foregroundColor(tab == t ? Aurora.teal : Aurora.textDim)
                                .frame(maxWidth: .infinity).padding(.vertical, 9)
                                .background(tab == t ? Aurora.teal.opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain).pointerHover()
                    }
                }
                .padding(.top, 16)

                DriveCard {
                    VStack(alignment: .leading, spacing: 0) {
                        switch tab {
                        case .mac: macSteps
                        case .windows: windowsSteps
                        case .browser: browserSteps
                        }
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.top, 16)

                Text("T R O U B L E S H O O T I N G").font(.cd(11, weight: .medium)).tracking(1).foregroundColor(Aurora.textDim)
                    .padding(.top, 22).padding(.bottom, 8)
                DriveCard {
                    TroubleItem(title: "Devices can't see the drive?",
                                body: "Check both devices are on the exact same Wi-Fi — not one on Guest and one on Main. Turn off any VPN apps on both devices. If asked to allow CrossDrop on your local network, make sure it's switched on in Settings (System Settings → Privacy & Security → Local Network on a Mac).")
                    if Platform.pausesInBackground {
                        RowDivider()
                        TroubleItem(title: "The drive stops when I switch apps?",
                                    body: "iOS pauses apps in the background, so the drive only answers while CrossDrop is open. Keep it on screen while others connect — the screen stays awake automatically.")
                    }
                    RowDivider()
                    TroubleItem(title: "On hotel, school or public Wi-Fi?",
                                body: "Public networks often block devices from talking to each other. Fix: turn on a phone's Personal Hotspot and join it from both devices — the drive works instantly at full speed.")
                    RowDivider()
                    TroubleItem(title: "Video buffers or previews are slow?",
                                body: "Move the devices closer to each other or to the router — the drive runs at the speed of your Wi-Fi.")
                }
                Spacer().frame(height: 24)
            }
            .padding(24)
        }
    }

    // MARK: steps

    @ViewBuilder private var macSteps: some View {
        Step(n: 1, text: "Open Finder, then press ⌘ + K (\"Connect to Server\").")
        Step(n: 2, text: "Paste this address and click Connect:") { CopyRow(text: state.url) }
        if let pin = state.pin {
            Step(n: 3, text: "Choose \"Registered User\" and enter:") {
                CopyRow(text: "drive", label: "name")
                CopyRow(text: pin, label: "passcode")
            }
        }
        ResultLine("Your \(Platform.deviceNoun) appears as a disk on the Mac desktop. Drag files in or out, open them in Final Cut, or scrub through 4K video right on the drive.")
    }

    @ViewBuilder private var windowsSteps: some View {
        Step(n: 1, text: "Open \"This PC\", click ••• (or right-click empty space) → \"Add a network location\".")
        Step(n: 2, text: "Choose \"Custom network location\" and paste:") { CopyRow(text: state.url) }
        if let pin = state.pin {
            Step(n: 3, text: "When asked for a password:") {
                CopyRow(text: "drive", label: "name")
                CopyRow(text: pin, label: "passcode")
            }
        }
        ResultLine("A new drive folder shows up under \"This PC\". It behaves like a plugged-in USB drive.")
    }

    @ViewBuilder private var browserSteps: some View {
        Step(n: 1, text: "On any laptop, tablet or phone on this Wi-Fi, open any browser — Safari, Chrome, Edge.")
        Step(n: 2, text: "Scan the QR on the previous screen, or type:") { CopyRow(text: state.url) }
        if let pin = state.pin {
            Step(n: 3, text: "Enter the 4-digit passcode when asked:") { CopyRow(text: pin) }
        }
        ResultLine("Browse folders, stream video, or download any file — nothing to install. Works on iPhone and iPad too.")
    }
}

private struct Step<Extra: View>: View {
    let n: Int
    let text: String
    @ViewBuilder var extra: () -> Extra

    init(n: Int, text: String, @ViewBuilder extra: @escaping () -> Extra) { self.n = n; self.text = text; self.extra = extra }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.cd(13, weight: .bold)).foregroundColor(Aurora.teal)
                .padding(.horizontal, 8).padding(.vertical, 2).background(Aurora.teal.opacity(0.2), in: Capsule())
            VStack(alignment: .leading, spacing: 6) {
                Text(text).font(.cd(14)).foregroundColor(Aurora.textPrimary).fixedSize(horizontal: false, vertical: true)
                extra()
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }
}

extension Step where Extra == EmptyView {
    fileprivate init(n: Int, text: String) { self.init(n: n, text: text) { EmptyView() } }
}

private struct CopyRow: View {
    let text: String
    var label: String?
    var body: some View {
        HStack(spacing: 8) {
            if let label { Text("\(label)  ").font(.cd(12)).foregroundColor(Aurora.textDim) }
            Text(text).font(.cd(14, design: .monospaced)).foregroundColor(Aurora.phosphor).textSelection(.enabled)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(Aurora.deepSpaceSoft, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Aurora.glassEdge, lineWidth: 1))
            CopyChip(text: text)
        }
    }
}

private struct ResultLine: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text).font(.cd(12)).foregroundColor(Aurora.phosphor).padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(Aurora.teal.opacity(0.1), in: RoundedRectangle(cornerRadius: 10)).padding(.top, 10)
    }
}

private struct TroubleItem: View {
    let title: String
    let body_: String
    @State private var open = false
    init(title: String, body: String) { self.title = title; self.body_ = body }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { open.toggle() } label: {
                HStack {
                    Text(title).font(.cd(14)).foregroundColor(Aurora.textPrimary).multilineTextAlignment(.leading)
                    Spacer()
                    Text(open ? "−" : "+").font(.cd(18)).foregroundColor(Aurora.textDim)
                }
                .padding(14).contentShape(Rectangle())
            }
            .buttonStyle(.plain).pointerHover()
            if open {
                Text(body_).font(.cd(13)).foregroundColor(Aurora.textDim).padding(.horizontal, 14).padding(.bottom, 14)
            }
        }
    }
}
