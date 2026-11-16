import SwiftUI
import UniformTypeIdentifiers

/// Observes the host manager directly (nested ObservableObjects don't propagate through AppContainer)
/// and slides the drive screen over the app while it is open.
struct DriveHostHost: View {
    @ObservedObject var host: VaultHostManager
    @Binding var open: Bool

    var body: some View {
        ZStack {
            if open {
                DriveHostScreen(host: host, onClose: { open = false })
                    .background(CD.ink.ignoresSafeArea())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: open)
    }
}

/// "Wireless Drive" — the plain-language face of the vault + WebDAV engine. Off: choose guest access, then
/// a folder (or the built-in vault) to share. On: live status, QR + address + passcode, per-platform
/// connect steps and the stop button. No "server", "mount" or "WebDAV" anywhere a user looks.
struct DriveHostScreen: View {
    @ObservedObject var host: VaultHostManager
    let onClose: () -> Void
    @State private var showGuide = false

    init(host: VaultHostManager, onClose: @escaping () -> Void) {
        self.host = host; self.onClose = onClose
        #if DEBUG
        // `-dev_open drive-guide` lands on the guide.
        _showGuide = State(initialValue: UserDefaults.standard.string(forKey: "dev_open") == "drive-guide")
        #endif
    }

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.13, centerX: 0.36, centerY: 160, radius: 260)
            if showGuide, let s = host.state {
                DriveGuideScreen(state: s, onClose: { showGuide = false })
                    .transition(.move(edge: .trailing))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        BackHeader("Wireless Drive", onBack: onClose).padding(.bottom, 4)
                        if let s = host.state {
                            LiveContent(state: s, onStop: host.stop) { showGuide = true }
                        } else {
                            StartContent(host: host)
                        }
                    }
                    .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 26)
                    .readableWidth(560)
                }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showGuide)
    }
}

// MARK: - Off

private struct StartContent: View {
    @ObservedObject var host: VaultHostManager
    @State private var writable = false
    @State private var usePin = true
    @State private var pickingFolder = false

    var body: some View {
        GlassCard(radius: 30, padding: .all(20), spacing: 10) {
            IconTile(icon: CdIcons.drive, tint: CD.accent, bg: CD.accent.opacity(0.14), size: 52, radius: 17, iconSize: 26)
            Text("Turn this \(Platform.deviceNoun) into a drive").displayStyle(24, tracking: -0.035)
                .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
            Text("Macs, PCs, tablets and phones on this Wi-Fi can browse, stream and download — like plugging in a USB stick.")
                .body(13, CD.textDim).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
        }

        SectionLabel("Guest access").padding(.top, 6)
        GlassCard(radius: 26, fill: CD.glassLow, padding: .hv(16, 4)) {
            ToggleRow(title: "Let guests add & edit files", subtitle: "Off: guests can only view and download", isOn: $writable)
            RowSeparator()
            ToggleRow(title: "4-digit passcode", subtitle: "Recommended on shared Wi-Fi", isOn: $usePin)
        }

        SectionLabel("What to share").padding(.top, 6)
        ShareChoice(icon: CdIcons.folderIcon, title: "Pick a folder", subtitle: "Photos, movies, work files — any folder you choose",
                    primary: true) { pickingFolder = true }
        ShareChoice(icon: CdIcons.drive, title: "Share the CrossDrop vault",
                    subtitle: "Files saved inside this app — also in the Files app", primary: false) {
            host.start(writable: writable, usePin: usePin)
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { host.start(writable: writable, usePin: usePin, folder: url) }
        }

        if let f = host.failure {
            Text(f).body(13, CD.dangerText).padding(.horizontal, 4).fixedSize(horizontal: false, vertical: true)
        }
        FootNote("Everything stays on this Wi-Fi — nothing touches the internet."
                 + (Platform.pausesInBackground ? " The drive answers while CrossDrop is open on screen — iOS pauses it when you switch apps." : ""),
                 icon: CdIcons.shield)
            .padding(.top, 2)
    }
}

private struct ShareChoice: View {
    let icon: CdIcon
    let title: String
    let subtitle: String
    let primary: Bool
    let action: () -> Void

    var body: some View {
        GlassCard(radius: 26, fill: primary ? CD.accent : CD.glass, border: primary ? nil : CD.hairline,
                  glow: primary ? CD.accent.opacity(0.18) : nil, glowOffsetY: 12, padding: .all(18), action: action) {
            HStack(spacing: 14) {
                IconTile(icon: icon, tint: primary ? CD.onAccent : CD.amber,
                         bg: primary ? CD.onAccent.opacity(0.12) : CD.amber.opacity(0.14), size: 46, radius: 16, iconSize: 24)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).displayStyle(17, tracking: -0.02, color: primary ? CD.onAccent : CD.textPrimary)
                    Text(subtitle).body(12, primary ? CD.onAccentMuted : CD.textDim).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                CdIconView(CdIcons.chevronRight, color: primary ? CD.onAccent : CD.textDim, size: 20)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - On

private struct LiveContent: View {
    let state: VaultHostManager.State
    let onStop: () -> Void
    let onShowGuide: () -> Void
    @State private var tab = 0

    var body: some View {
        // status
        GlassCard(radius: 30, border: CD.accent.opacity(0.35), glow: CD.accent.opacity(0.13), glowRadius: 50,
                  padding: .all(20), spacing: 16) {
            HStack(spacing: 12) {
                PulseDot()
                Text("Drive is on").displayStyle(28, tracking: -0.04).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 4)
                let n = state.clients.count
                Text(n == 0 ? "No one connected" : "\(n) connected").body(12, n == 0 ? CD.textDim : CD.accent, weight: .semibold)
            }
            HStack(spacing: 10) {
                CdIconView(CdIcons.folderIcon, color: CD.amber, size: 22)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Sharing").body(11, CD.textDim)
                    Text(state.rootLabel).body(14, weight: .semibold).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(CD.glass))
            .accessibilityElement(children: .combine)
            HStack(spacing: 10) {
                Stat(label: "↑ Uploaded by guests", value: Fmt.bytes(state.bytesIn))
                Stat(label: "↓ Downloaded", value: Fmt.bytes(state.bytesOut))
            }
        }

        // QR + address + passcode
        GlassCard(radius: 28, fill: CD.glassLow, padding: .all(14)) {
            HStack(spacing: 14) {
                QrCard(text: state.url, size: 128, halo: false)
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Address").body(11, CD.textDim)
                        Text(state.url).monoStyle(13).lineLimit(1).minimumScaleFactor(0.6).textSelection(.enabled)
                    }
                    .accessibilityElement(children: .combine)
                    if let pin = state.pin {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Passcode").body(11, CD.textDim)
                            Text(pin).font(.mono(20)).tracking(CDFont.scaled(20) * 0.3).foregroundColor(CD.accent)
                                .textSelection(.enabled)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Passcode " + pin.map(String.init).joined(separator: " "))
                    }
                    CopyButton(value: state.url, label: "Copy address")
                }
                Spacer(minLength: 0)
            }
        }

        // guest access (fixed while the drive is on)
        GlassCard(radius: 26, fill: CD.glassLow, padding: .hv(16, 4)) {
            ModeRow(title: "Guests can add & edit files",
                    subtitle: state.writable ? "Guests can upload, rename and delete" : "Guests can only view and download", on: state.writable)
            RowSeparator()
            ModeRow(title: "4-digit passcode",
                    subtitle: state.pin != nil ? "Needed to open the drive" : "Anyone on this Wi-Fi can open it", on: state.pin != nil)
        }

        // how to connect
        GlassCard(radius: 28, fill: CD.glassLow, spacing: 14) {
            Text("Connect from another device").body(14, weight: .semibold).accessibilityAddTraits(.isHeader)
            SegmentedTabs(options: guideTabs, selected: $tab)
            VStack(alignment: .leading, spacing: 12) { ConnectSteps(state: state, tab: tab, withCopy: false) }
        }

        GlassCard(radius: 22, fill: CD.glassLow, border: nil, padding: .hv(16, 14), action: onShowGuide) {
            HStack(spacing: 12) {
                CdIconView(CdIcons.help, color: CD.amber, size: 20)
                Text("Can't connect? Guest Wi-Fi, VPNs, hotels…").body(14, weight: .medium)
                Spacer(minLength: 4)
                CdIconView(CdIcons.chevronRight, color: CD.textDim, size: 18)
            }
            .frame(minHeight: 24)
        }
        .accessibilityElement(children: .combine)

        FootNote((Platform.pausesInBackground
                  ? "Keep CrossDrop open while others connect — iOS pauses the drive when you switch apps. The screen stays awake."
                  : "Keeps serving while CrossDrop is running, even with the window closed.")
                 + (state.pin != nil ? " Five wrong passcodes lock a device out for 60 seconds." : ""))
        PillButton("Stop sharing drive", style: .danger, height: 56, action: onStop).padding(.top, 4)
    }
}

private struct Stat: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).body(11, CD.textDim)
            Text(value).monoStyle(16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct ModeRow: View {
    let title: String
    let subtitle: String
    let on: Bool
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).body(14, weight: .semibold)
                Text(subtitle).body(12, CD.textDim)
            }
            Spacer(minLength: 8)
            Text(on ? "On" : "Off").font(.cd(12, weight: .semibold)).foregroundColor(on ? CD.onAccent : CD.textSecondary)
                .padding(.horizontal, 12).padding(.vertical, 5)
                .background(Capsule().fill(on ? CD.accent : CD.glassHigh))
        }
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }
}

private struct PulseDot: View {
    @State private var p = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            Circle().fill(CD.accent).frame(width: 12, height: 12)
                .scaleEffect(p ? 3 : 1).opacity(p ? 0 : 0.8)
            Circle().fill(CD.accent).frame(width: 12, height: 12)
        }
        .frame(width: 12, height: 12)
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeOut(duration: 1.8).repeatForever(autoreverses: false)) { p = true }
        }
    }
}
