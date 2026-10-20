import SwiftUI

/// Orbital-beacon action sheet: mount in the native explorer, or hand the browser URL to anyone
/// nearby (QR + copyable link).
struct VaultMountSheet: View {
    let peer: Peer
    let onMount: () -> Void
    @State private var showQr = false
    @State private var copied = false

    private var url: String { peer.host.map { "http://\(Self.hostLiteral($0)):\(peer.port)/" } ?? "" }

    var body: some View {
        let vault = peer.vault
        ZStack {
            Aurora.deepSpaceSoft.ignoresSafeArea()
            ReadableScroll {
                VStack(spacing: 10) {
                    Text(vault?.vaultName ?? peer.name).font(.cd(18, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                    HStack(spacing: 0) {
                        Text(vault?.writable == true ? "read · write" : "read-only").foregroundColor(vault?.writable == true ? Aurora.phosphor : Aurora.textDim)
                        if vault?.needsPin == true { Text("  ·  🔒 PIN protected").foregroundColor(Aurora.textDim) }
                    }.font(.cd(12))
                    PickerOption(icon: "externaldrive.connected.to.line.below", tint: Aurora.teal, title: "Mount vault",
                                 subtitle: "browse and pull files in-app (encrypted)", action: onMount)
                        .padding(.top, 8)
                    PickerOption(icon: "qrcode", tint: Aurora.cyan, title: "Open in browser",
                                 subtitle: "share the link — no app needed on their end") {
                        UIPasteboard.general.string = url
                        copied = true
                        withAnimation { showQr.toggle() }
                    }
                    if showQr, !url.isEmpty {
                        QrCard(text: url, size: 190).padding(.top, 8)
                        Text(url).font(.cd(11)).foregroundColor(Aurora.textDim).textSelection(.enabled)
                        Text(copied ? "link copied — or let them scan" : "").font(.cd(11)).foregroundColor(Aurora.textDim)
                    }
                }
                .padding(24)
            }
        }
    }

    /// IPv6 literals need brackets in a URL.
    static func hostLiteral(_ h: String) -> String { h.contains(":") ? "[\(h)]" : h }
}
