import SwiftUI

/// App-wide settings: identity, discovery, wireless drive, storage, about, developer tools.
struct SettingsScreen: View {
    @EnvironmentObject var container: AppContainer
    @ObservedObject var inbox: InboxStore
    @ObservedObject var history: HistoryStore
    var onShowWelcome: () -> Void = {}
    @Environment(\.navInset) private var navInset
    @State private var renaming = false
    @State private var renameText = ""
    @State private var addingPeer = false
    @State private var peerAddress = ""
    @State private var confirmWipe = false
    @State private var confirmClear = false
    @State private var rescanned = false
    @State private var pickingLanguage = false

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    var body: some View {
        let files = inbox.files
        let usedBytes = Fmt.bytes(files.reduce(0) { $0 + $1.size })
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.2, centerX: 0.26, centerY: 160, radius: 240)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ScreenTitle(tr("Settings")).padding(.bottom, 2)
                    identity

                    LanguageRow { pickingLanguage = true }

                    SectionLabel(tr("Discovery")).padding(.top, 4)
                    SettingsGroup {
                        ToggleRow(title: "Wi-Fi", subtitle: tr("Find devices on this network"),
                                  isOn: Binding(get: { container.discoveryEnabled }, set: { container.setDiscoveryEnabled($0) }),
                                  icon: CdIcons.wifi)
                        RowSeparator()
                        NavRow(icon: CdIcons.radar, title: rescanned ? tr("Rescanning…") : tr("Rescan network"),
                               subtitle: tr("After allowing Local Network access, or switching Wi-Fi")) {
                            container.restartDiscovery()
                            rescanned = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { rescanned = false }
                        }
                    }

                    DriveSettingsCard(host: container.vaultHost) { container.driveOpen = true }

                    SectionLabel(tr("Storage")).padding(.top, 4)
                    SettingsGroup {
                        HStack {
                            Text(tr("Inbox")).body(14, weight: .semibold)
                            Spacer()
                            Text(trPlural(files.count, one: "1 file", other: "{n} files") + " · " + usedBytes)
                                .monoStyle(12, color: CD.textSecondary)
                        }
                        .frame(minHeight: 54)
                        .accessibilityElement(children: .combine)
                        RowSeparator()
                        ActionRow(title: tr("Wipe inbox"), color: CD.dangerText, enabled: !files.isEmpty) { confirmWipe = true }
                        RowSeparator()
                        ActionRow(title: tr("Clear transfer history"), color: CD.textPrimary, enabled: !history.entries.isEmpty) { confirmClear = true }
                    }

                    about

                    #if DEBUG
                    SectionLabel("Developer").padding(.top, 4)
                    SettingsGroup {
                        NavRow(icon: CdIcons.wrench, title: "Add peer by address", subtitle: "Place a peer on the radar manually") {
                            addingPeer = true
                        }
                    }
                    #endif
                }
                .padding(.horizontal, 16).padding(.top, 28).padding(.bottom, navInset + 8)
                .readableWidth()
            }
        }
        .sheet(isPresented: $pickingLanguage) { LanguageSheet() }
        .renameDialog(isPresented: $renaming, text: $renameText) { container.setDeviceName($0) }
        .confirmDialog(tr("Wipe the inbox?"), isPresented: $confirmWipe,
                       message: tr("Every received file is overwritten with zeros and deleted. This can't be undone."),
                       confirmLabel: tr("Wipe inbox")) { inbox.shredAll() }
        .confirmDialog(tr("Clear transfer history?"), isPresented: $confirmClear,
                       message: tr("This forgets the log of past transfers. Received files stay in your inbox."),
                       confirmLabel: tr("Clear")) { history.clear() }
        .alert("Add peer by address", isPresented: $addingPeer) {
            TextField("host:port — e.g. 192.168.1.20:43595", text: $peerAddress)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Button(tr("Add")) {
                let parts = peerAddress.trimmingCharacters(in: .whitespaces).split(separator: ":", maxSplits: 1).map(String.init)
                if parts.count == 2, let p = Int(parts[1]), (1...65535).contains(p), !parts[0].isEmpty {
                    container.addManualPeer(name: nil, host: parts[0], port: p)
                }
                peerAddress = ""
            }
            Button(tr("Cancel"), role: .cancel) { peerAddress = "" }
        } message: {
            Text("Places a peer on the radar as if discovery had found it. Useful when Bonjour can't cross a network.")
        }
    }

    // MARK: identity

    private var identity: some View {
        GlassCard(radius: 28, padding: .all(18), spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(CD.accent.opacity(0.15)).frame(width: 68, height: 68)
                    Circle().fill(CD.accent).frame(width: 56, height: 56).shadow(color: CD.accent.opacity(0.4), radius: 15)
                    CdIconView(Platform.deviceIcon, color: CD.onAccent, size: 24)
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Name others see")).body(12, CD.textDim)
                    Text(container.deviceName).displayStyle(22).lineLimit(1)
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 4)
                CircleButton(icon: CdIcons.pencil, label: tr("Rename this device"), iconSize: 18, fill: CD.glassHigh) {
                    renameText = container.deviceName
                    renaming = true
                }
            }
            HStack {
                Text("id \(Self.formatId(container.instanceId))").monoStyle(12, color: CD.textSecondary)
                Spacer(minLength: 8)
                Text(portLabel).body(11, CD.accent)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(CD.glassLow))
            .accessibilityElement(children: .combine)
        }
    }

    private var portLabel: String {
        #if DEBUG
        if container.listenerPort > 0 { return "port :\(container.listenerPort) · new every launch" }
        #endif
        return tr("new every launch")
    }

    /// "a3f9·71c2·e08b" — the rotating instance id, grouped for reading.
    static func formatId(_ id: String) -> String {
        var groups: [String] = []
        var rest = Substring(id.prefix(12))
        while !rest.isEmpty { groups.append(String(rest.prefix(4))); rest = rest.dropFirst(4) }
        return groups.joined(separator: "·")
    }

    // MARK: about

    private var about: some View {
        GlassCard(radius: 26, fill: Color.white.opacity(0.03), border: nil, padding: .all(18), spacing: 12) {
            HStack {
                Text("CrossDrop").displayStyle(15, tracking: 0)
                Spacer()
                Text("v\(version)").monoStyle(12, color: CD.textDim)
            }
            HStack(spacing: 6) {
                MonoTag(text: "X25519")
                MonoTag(text: "HKDF-SHA256")
                MonoTag(text: "ChaCha20-Poly1305")
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(tr("Encryption: X25519, HKDF-SHA256, ChaCha20-Poly1305"))
            Text(tr("No cloud. No accounts. No tracking.")).body(13, CD.accent, weight: .semibold)
            ChipButton(text: tr("Show the welcome tour"), icon: CdIcons.help, action: onShowWelcome)
        }
    }
}

private struct SettingsGroup<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        GlassCard(radius: 26, fill: CD.glassLow, padding: .hv(16, 2), content: content)
    }
}

private struct ActionRow: View {
    let title: String
    let color: Color
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).body(14, color, weight: .semibold)
                .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .pointerHover()
    }
}

private struct NavRow: View {
    let icon: CdIcon
    let title: String
    let subtitle: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                CdIconView(icon, color: CD.textPrimary, size: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).body(14, weight: .semibold)
                    Text(subtitle).body(12, CD.textDim).fixedSize(horizontal: false, vertical: true)
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
}

/// Accent call-to-action while off; a live status card (pulsing dot) while the drive is on. Observes the
/// host directly — nested ObservableObjects don't propagate through `AppContainer`.
struct DriveSettingsCard: View {
    @ObservedObject var host: VaultHostManager
    let onOpen: () -> Void
    @State private var glow = false

    var body: some View {
        if let s = host.state {
            GlassCard(radius: 28, border: CD.accent.opacity(0.35), glow: CD.accent.opacity(0.13), padding: .all(18),
                      action: onOpen, actionLabel: tr("Opens the drive")) {
                HStack(spacing: 14) {
                    IconTile(icon: CdIcons.drive, tint: CD.accent, bg: CD.accent.opacity(0.14), size: 48, radius: 16, iconSize: 24)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 8) {
                            Circle().fill(CD.accent).frame(width: 8, height: 8).opacity(glow ? 1 : 0.4)
                            Text(tr("Drive is on")).displayStyle(17, tracking: -0.02)
                        }
                        let n = s.clients.count
                        Text("\(s.rootLabel) · " + (n == 0 ? tr("no one connected") : tr("{0} connected", n)))
                            .body(12, CD.textDim).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    CdIconView(CdIcons.chevronRight, color: CD.textDim, size: 20)
                }
            }
            .accessibilityElement(children: .combine)
            .onAppear { withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { glow = true } }
        } else {
            GlassCard(radius: 28, fill: CD.accent, border: nil, glow: CD.accent.opacity(0.2), glowRadius: 44, glowOffsetY: 14,
                      padding: .all(18), action: onOpen, actionLabel: tr("Opens the drive setup")) {
                HStack(spacing: 14) {
                    IconTile(icon: CdIcons.drive, tint: CD.onAccent, bg: CD.onAccent.opacity(0.12), size: 48, radius: 16, iconSize: 24)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(tr("Turn into drive")).displayStyle(17, tracking: -0.02, color: CD.onAccent)
                        Text(tr("Open this {0} from Finder, Windows or any browser", Platform.deviceNoun)).body(12, CD.onAccentMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 4)
                    CdIconView(CdIcons.chevronRight, color: CD.onAccent, size: 20)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}
