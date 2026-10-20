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
                    .background(Aurora.deepSpace.ignoresSafeArea())
                    .transition(.move(edge: .bottom))
                    .zIndex(2)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: open)
    }
}

/// "Wireless Drive" host screen — the plain-language UX over the vault + WebDAV engine. A start card
/// (built-in vault or a picked folder, read-only/read-write, optional passcode), then the live card
/// (status light, shared content, QR + address, 4-digit code). No "server", "mount" or "WebDAV"
/// anywhere the user can see.
struct DriveHostScreen: View {
    @ObservedObject var host: VaultHostManager
    let onClose: () -> Void
    @State private var showGuide = false
    /// DEBUG: `-dev_open drive-guide` lands on the guide.
    init(host: VaultHostManager, onClose: @escaping () -> Void) {
        self.host = host; self.onClose = onClose
        #if DEBUG
        _showGuide = State(initialValue: UserDefaults.standard.string(forKey: "dev_open") == "drive-guide")
        #endif
    }

    var body: some View {
        ZStack {
            AuroraBackdrop()
            if showGuide, let s = host.state {
                DriveGuideScreen(state: s, onClose: { showGuide = false })
            } else {
                ReadableScroll {
                    VStack(spacing: 0) {
                        HStack {
                            Button { onClose() } label: { Text("‹ back").font(.cd(15)).foregroundColor(Aurora.textDim) }
                            Spacer()
                        }
                        Text("W I R E L E S S   D R I V E").font(.cd(12, weight: .medium)).tracking(1)
                            .foregroundColor(Aurora.textDim).padding(.top, 8)
                        Spacer().frame(height: 20)
                        if let s = host.state {
                            LiveCard(state: s, onStop: host.stop, onShowGuide: { showGuide = true })
                        } else {
                            StartCard(host: host)
                        }
                        Spacer().frame(height: 28)
                    }
                    .padding(24)
                }
            }
        }
    }
}

// MARK: start card

private struct StartCard: View {
    @ObservedObject var host: VaultHostManager
    @State private var writable = false
    @State private var usePin = true
    @State private var pickingFolder = false

    var body: some View {
        VStack(spacing: 0) {
            Text("Turn this \(Platform.deviceNoun) into a drive").font(.cd(20, weight: .semibold)).foregroundColor(Aurora.textPrimary)
            Text("Other devices on this Wi-Fi can browse, stream\nand download — like plugging in a USB stick.")
                .font(.cd(13)).foregroundColor(Aurora.textDim).multilineTextAlignment(.center).padding(.top, 6)
            Spacer().frame(height: 22)

            DriveCard {
                ActionRow(title: "Share the CrossDrop vault", subtitle: "files saved in this app — also in the Files app", accent: Aurora.teal) {
                    host.start(writable: writable, usePin: usePin)
                }
                RowDivider()
                ActionRow(title: "Pick a folder to share", subtitle: "any folder — photos, movies, work files", accent: Aurora.cyan) {
                    pickingFolder = true
                }
                RowDivider()
                ToggleLine(title: "Let guests add & edit files", subtitle: "off = view and download only", checked: $writable)
                RowDivider()
                ToggleLine(title: "4-digit passcode", subtitle: "recommended on shared Wi-Fi", checked: $usePin)
            }
            if let f = host.failure {
                Text(f).font(.cd(13)).foregroundColor(Aurora.danger).multilineTextAlignment(.center).padding(.top, 10)
            }
            if Platform.pausesInBackground {
                Text("The drive answers while CrossDrop is open on screen — iOS pauses it when you switch apps.")
                    .font(.cd(11)).foregroundColor(Aurora.textDim).multilineTextAlignment(.center).padding(.top, 14)
            }
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { host.start(writable: writable, usePin: usePin, folder: url) }
        }
    }
}

// MARK: live card

private struct LiveCard: View {
    let state: VaultHostManager.State
    let onStop: () -> Void
    let onShowGuide: () -> Void
    @State private var glow = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Circle().fill(Aurora.teal).frame(width: 10, height: 10).opacity(glow ? 1 : 0.45)
                    .animation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true), value: glow)
                Text("Drive is on").font(.cd(18, weight: .bold)).foregroundColor(Aurora.teal)
            }
            Text("visible to devices on your Wi-Fi").font(.cd(12)).foregroundColor(Aurora.textDim)
            Spacer().frame(height: 18)

            DriveCard {
                HStack(spacing: 10) {
                    Image(systemName: "folder.fill").font(.cd(20)).foregroundColor(Aurora.cyan)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.rootLabel).font(.cd(15, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                        Text((state.writable ? "view · edit · upload" : "view & download only") + (state.pin != nil ? "  ·  passcode on" : ""))
                            .font(.cd(12)).foregroundColor(Aurora.textDim)
                    }
                    Spacer()
                }
                .padding(14)
                RowDivider()
                HStack {
                    let n = state.clients.count
                    Text(n == 0 ? "no devices connected" : "\(n) device\(n > 1 ? "s" : "") connected")
                        .font(.cd(12)).foregroundColor(n == 0 ? Aurora.textDim : Aurora.phosphor)
                    Spacer()
                    Text("↑ \(Fmt.bytes(state.bytesOut))  ↓ \(Fmt.bytes(state.bytesIn))")
                        .font(.cd(11)).foregroundColor(Aurora.textDim)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
            }
            Spacer().frame(height: 18)

            Text("F A S T E S T").font(.cd(11, weight: .medium)).tracking(1).foregroundColor(Aurora.textDim)
            QrCard(text: state.url).padding(.top, 6)
            Text("scan with any camera — files open instantly")
                .font(.cd(12)).foregroundColor(Aurora.textDim).padding(.top, 6)
            Spacer().frame(height: 20)

            DriveCard {
                VStack(alignment: .leading, spacing: 0) {
                    Text("O R   T Y P E   T H I S   A D D R E S S").font(.cd(10)).tracking(0.8).foregroundColor(Aurora.textDim)
                    HStack {
                        Text(state.url).font(.cd(15, weight: .medium, design: .monospaced)).foregroundColor(Aurora.phosphor)
                            .textSelection(.enabled)
                        Spacer(minLength: 4)
                        CopyChip(text: state.url)
                    }
                    .padding(.top, 6)
                    if let pin = state.pin {
                        HStack(spacing: 10) {
                            Text("passcode").font(.cd(12)).foregroundColor(Aurora.textDim)
                            Text(pin.map(String.init).joined(separator: " "))
                                .font(.cd(22, weight: .bold, design: .monospaced)).tracking(4).foregroundColor(Aurora.textPrimary)
                            Spacer()
                            CopyChip(text: pin)
                        }
                        .padding(.top, 10)
                    }
                }
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            }
            Button(action: onShowGuide) {
                Text("Need help connecting? See the 2-step guide →").font(.cd(13)).foregroundColor(Aurora.cyan)
            }
            .padding(.top, 14)
            Text("Everything stays on this Wi-Fi — nothing touches the internet." + (Platform.pausesInBackground ? "\nKeep CrossDrop open: iOS pauses the drive when you leave the app." : ""))
                .font(.cd(11)).foregroundColor(Aurora.textDim).multilineTextAlignment(.center).padding(.top, 10)
            Button(action: onStop) {
                Text("Stop Sharing Drive").font(.cd(15, weight: .bold)).foregroundColor(Aurora.deepSpace)
                    .frame(maxWidth: .infinity).frame(height: 48)
                    .background(Aurora.danger, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(.top, 20)
        }
        .onAppear { glow = true }
    }
}

// MARK: shared bits

struct DriveCard<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(spacing: 0, content: content)
            .background(Aurora.deepSpaceRaised, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Aurora.glassEdge, lineWidth: 1))
    }
}

struct RowDivider: View {
    var body: some View { Rectangle().fill(Aurora.glassEdge.opacity(0.5)).frame(height: 1) }
}

private struct ActionRow: View {
    let title: String
    let subtitle: String
    let accent: Color
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.cd(15, weight: .semibold)).foregroundColor(accent)
                    Text(subtitle).font(.cd(12)).foregroundColor(Aurora.textDim).multilineTextAlignment(.leading)
                }
                Spacer()
                Text("›").font(.cd(20)).foregroundColor(Aurora.textDim)
            }
            .padding(14).contentShape(Rectangle())
        }
        .buttonStyle(.plain).pointerHover()
    }
}

private struct ToggleLine: View {
    let title: String
    let subtitle: String
    @Binding var checked: Bool
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.cd(14)).foregroundColor(Aurora.textPrimary)
                Text(subtitle).font(.cd(11)).foregroundColor(Aurora.textDim)
            }
            Spacer()
            Toggle("", isOn: $checked).labelsHidden().tint(Aurora.teal)
        }
        .padding(.horizontal, 14).padding(.vertical, 8)
    }
}

struct CopyChip: View {
    let text: String
    @State private var copied = false
    var body: some View {
        Button {
            UIPasteboard.general.string = text
            copied = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { copied = false }
        } label: {
            Text(copied ? "copied ✓" : "copy").font(.cd(13)).foregroundColor(copied ? Aurora.teal : Aurora.cyan)
                .padding(.horizontal, 6).padding(.vertical, 4)
        }
        .buttonStyle(.plain).pointerHover()
    }
}
