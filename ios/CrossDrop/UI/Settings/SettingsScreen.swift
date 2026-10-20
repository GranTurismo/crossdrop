import SwiftUI

struct SettingsScreen: View {
    @EnvironmentObject var container: AppContainer
    @EnvironmentObject var coordinator: TransferCoordinator
    @ObservedObject var inbox: InboxStore
    @ObservedObject var history: HistoryStore
    @State private var renaming = false
    @State private var addingPeer = false
    @State private var peerAddress = ""

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    var body: some View {
        ReadableScroll(maxWidth: 720) {
            VStack(alignment: .leading, spacing: 0) {
                Text("Settings").font(.cd(24, weight: .heavy)).foregroundColor(Aurora.textPrimary)
                    .padding(.top, 20).padding(.bottom, 6)

                section("Device") {
                    row("\(container.deviceName)", "visible name · tap to change") { renaming = true }
                    divider
                    row("Device id", String(container.instanceId.prefix(12)))
                    if container.listenerPort > 0 { divider; row("Listening port", ":\(container.listenerPort)") }
                }

                section("Discovery") {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Wi-Fi (Bonjour)").font(.cd(14, weight: .medium)).foregroundColor(Aurora.textPrimary)
                            Text("find devices on the same network").font(.cd(12)).foregroundColor(Aurora.textDim)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(get: { container.discoveryEnabled }, set: { container.setDiscoveryEnabled($0) }))
                            .labelsHidden().tint(Aurora.teal)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    divider
                    row("Rescan network", "re-bind after approving the Local Network prompt") { container.restartDiscovery() }
                }

                section("Wireless drive") {
                    DriveSettingsRow(host: container.vaultHost) { container.driveOpen = true }
                }

                section("Storage") {
                    let files = inbox.files
                    row("Inbox", "\(files.count) file\(files.count == 1 ? "" : "s") · \(Fmt.bytes(files.reduce(0) { $0 + $1.size }))")
                    if !files.isEmpty {
                        divider
                        row("Wipe inbox", "zero-fill and delete every received file", color: Aurora.danger) { inbox.shredAll() }
                    }
                    divider
                    row("Clear transfer history", "forget the log of past transfers", color: Aurora.danger) { history.clear() }
                }

                section("About") {
                    row("CrossDrop \(version)", "local-only file sharing")
                    divider
                    row("Encryption", "X25519 · HKDF-SHA256 · ChaCha20-Poly1305")
                    divider
                    row("No cloud. No accounts. No tracking.", "files travel directly between devices on your network", color: Aurora.phosphor)
                }

                #if DEBUG
                section("Developer") {
                    row("Add peer by address", "place a peer on the radar manually") { addingPeer = true }
                }
                #endif
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 20)
        }
        .background(Aurora.deepSpace)
        .sheet(isPresented: $renaming) {
            DeviceNameSheet(current: container.deviceName) { new in
                renaming = false
                if let new { container.setDeviceName(new) }
            }.presentationDetents([.height(260)])
        }
        .alert("Add peer", isPresented: $addingPeer) {
            TextField("host:port", text: $peerAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Add") {
                let parts = peerAddress.split(separator: ":", maxSplits: 1).map(String.init)
                if parts.count == 2, let p = Int(parts[1]) { container.addManualPeer(name: nil, host: parts[0], port: p) }
                peerAddress = ""
            }
            Button("Cancel", role: .cancel) { peerAddress = "" }
        }
    }

    private var divider: some View {
        Rectangle().fill(Aurora.glassEdge.opacity(0.5)).frame(height: 1).padding(.horizontal, 14)
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: @escaping () -> C) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title.uppercased()).font(.cd(11, weight: .semibold)).tracking(2).foregroundColor(Aurora.textDim)
                .padding(.leading, 6).padding(.top, 18).padding(.bottom, 6)
            GlassCard(radius: 20) { VStack(spacing: 0, content: content) }
        }
    }

    private func row(_ title: String, _ subtitle: String, color: Color = Aurora.textPrimary, action: (() -> Void)? = nil) -> some View {
        Button { action?() } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.cd(14, weight: .medium)).foregroundColor(color).multilineTextAlignment(.leading)
                    Text(subtitle).font(.cd(12)).foregroundColor(Aurora.textDim).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                if action != nil { Text("›").font(.cd(18)).foregroundColor(Aurora.textDim) }
            }
            .padding(.horizontal, 16).padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).pointerHover()
        .disabled(action == nil)
    }
}

/// "Turn into drive" / "● Drive is on" row. Observes the host directly — nested ObservableObjects
/// don't propagate through `AppContainer`.
struct DriveSettingsRow: View {
    @ObservedObject var host: VaultHostManager
    let onOpen: () -> Void

    var body: some View {
        let s = host.state
        Button(action: onOpen) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(s != nil ? "● Drive is on" : "Turn into drive")
                        .font(.cd(14, weight: .medium)).foregroundColor(s != nil ? Aurora.phosphor : Aurora.textPrimary)
                    Text(s.map { "\($0.rootLabel) · \($0.url) · \($0.clients.count) connected" }
                         ?? "share this \(Platform.deviceNoun)'s files with any device on the Wi-Fi")
                        .font(.cd(12)).foregroundColor(Aurora.textDim).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Text("›").font(.cd(18)).foregroundColor(Aurora.textDim)
            }
            .padding(.horizontal, 16).padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).pointerHover()
    }
}
