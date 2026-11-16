import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

enum AppTab: String, CaseIterable {
    case radar = "Radar", transfers = "Transfers", inbox = "Inbox", settings = "Settings"
    var icon: CdIcon {
        switch self {
        case .radar: return CdIcons.radar
        case .transfers: return CdIcons.transfers
        case .inbox: return CdIcons.inbox
        case .settings: return CdIcons.sliders
        }
    }
}

/// App shell: four tabs behind a floating glass pill nav. Full-screen overlays (live transfer, QR share,
/// wireless drive, mounted drive) slide up over everything; the floating sheet sits above those; an
/// inbound offer takes the whole screen on top of all of it. The live transfer can be minimised to the
/// Transfers tab and reopened from its card.
struct RootView: View {
    @EnvironmentObject var container: AppContainer
    @EnvironmentObject var coordinator: TransferCoordinator
    @StateObject private var sheets = SheetHost()
    @State private var tab: AppTab = .radar
    @State private var dropTargeted = false
    /// The live transfer view is minimised to the Transfers tab.
    @State private var transferHidden = false
    @State private var liveHidden = false
    @Environment(\.sizeCategory) private var sizeCategory

    init() {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "dev_tab"), let tab = AppTab.allCases.first(where: { $0.rawValue.lowercased() == t }) {
            _tab = State(initialValue: tab)
        }
        if UserDefaults.standard.bool(forKey: "dev_hide_transfer") { _transferHidden = State(initialValue: true) }
        #endif
    }

    var body: some View {
        GeometryReader { geo in
            let safeBottom = geo.safeAreaInsets.bottom
            // Float just above the home indicator; 18 pt from the edge where there is none (Mac, iPad).
            let navBottom = max(18, safeBottom - 10)
            let navInset = max(0, navBottom + 66 - safeBottom) + 16
            ZStack {
                CD.ink.ignoresSafeArea()
                tabs.environment(\.navInset, navInset)

                // Scrolled content fades out under the status bar instead of colliding with the clock.
                LinearGradient(stops: [.init(color: CD.ink, location: 0), .init(color: CD.ink.opacity(0.85), location: 0.6),
                                       .init(color: CD.ink.opacity(0), location: 1)], startPoint: .top, endPoint: .bottom)
                    .frame(height: geo.safeAreaInsets.top + 12)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(edges: .top)
                    .allowsHitTesting(false)

                FloatingNav(tab: $tab, inbox: container.inbox,
                            transferLive: coordinator.groups.contains { !$0.isTerminal } && (transferHidden || coordinator.focusedGroup == nil))
                    .padding(.horizontal, 18)
                    .padding(.bottom, navBottom)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .ignoresSafeArea(.container, edges: .bottom)

                overlays

                FloatingSheetLayer(host: sheets).zIndex(5)

                // Inbound offer — always on top. Only its two buttons answer it: no swipe, no Esc, and a
                // system alert appearing over it can't decline it.
                if let offer = coordinator.incomingOffer {
                    IncomingOfferSheet(offer: offer) { accept in
                        if accept { transferHidden = false }
                        coordinator.respondToOffer(accept: accept)
                    }
                    .transition(.opacity)
                    .zIndex(6)
                }

                if dropTargeted { dropHint.zIndex(7) }
            }
        }
        .ignoresSafeArea(.keyboard)
        .environmentObject(sheets)
        .onDrop(of: [UTType.item], isTargeted: $dropTargeted, perform: handleDrop)
        .animation(.easeInOut(duration: 0.3), value: coordinator.focusedGroupId)
        .animation(.easeInOut(duration: 0.3), value: transferHidden)
        .animation(.easeInOut(duration: 0.3), value: container.cameraTargets == nil)
        .animation(.easeInOut(duration: 0.3), value: container.switchTarget == nil)
        .animation(.easeInOut(duration: 0.3), value: coordinator.live == nil || liveHidden)
        .animation(.easeInOut(duration: 0.25), value: coordinator.incomingOffer?.id)
        // A newly focused group always opens the transfer view; one that ends while minimised is cleared.
        .onChange(of: coordinator.focusedGroupId) { id in if id != nil { transferHidden = false } }
        .onChange(of: coordinator.focusedGroup?.isTerminal) { done in
            if transferHidden, done == true, let id = coordinator.focusedGroupId { coordinator.dismissGroup(id) }
        }
        .onChange(of: coordinator.live?.grantId) { _ in liveHidden = false }
        // An offer outranks whatever sheet was open.
        .onChange(of: coordinator.incomingOffer?.id) { id in if id != nil { sheets.dismiss() } }
        // ⌘O "Send Files…" lands on the radar, which raises the picker.
        .onChange(of: container.sendRequest) { _ in tab = .radar }
        .id(sizeCategory)       // fonts are computed from the text-size setting — rebuild when it changes
    }

    // MARK: tabs

    @ViewBuilder private var tabs: some View {
        ZStack {
            // The radar stays mounted (its pickers and ⌘O handler live there); hidden it stops animating.
            RadarScreen(active: tab == .radar)
                .opacity(tab == .radar ? 1 : 0)
                .allowsHitTesting(tab == .radar)
                .accessibilityHidden(tab != .radar)
            switch tab {
            case .radar: EmptyView()
            case .transfers:
                TransfersScreen(groups: coordinator.groups, history: container.history) { id in
                    coordinator.focus(id); transferHidden = false
                }
            case .inbox:
                InboxScreen(inbox: container.inbox)
            case .settings:
                SettingsScreen(inbox: container.inbox, history: container.history)
            }
        }
    }

    // MARK: overlays

    @ViewBuilder private var overlays: some View {
        if let g = coordinator.focusedGroup, !transferHidden {
            Group {
                if g.multi {
                    GroupTransferScreen(group: g, onCancelAll: { coordinator.cancelGroup(g.id) },
                                        onRetry: { coordinator.retryFailed(g.id) },
                                        onClose: { coordinator.dismissGroup(g.id) },
                                        onHide: { transferHidden = true })
                } else {
                    TransferScreen(
                        session: g.primary, selfName: container.deviceName,
                        onCancel: { coordinator.cancelGroup(g.id) },
                        onClose: { coordinator.dismissGroup(g.id) },
                        onHide: { transferHidden = true },
                        onOpenInbox: {
                            coordinator.dismissGroup(g.id)
                            tab = .inbox
                        })
                }
            }
            .background(CD.ink.ignoresSafeArea())
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .zIndex(1)
        }
        if let targets = container.cameraTargets {
            CameraScreen(targets: targets) { container.cameraTargets = nil }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(3)
        }
        if let feed = coordinator.live, !liveHidden {
            LiveViewerScreen(feed: feed, onStop: coordinator.stopLive, onHide: { liveHidden = true })
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(3)
        }
        if let peer = container.switchTarget {
            SwitchSendHost(peer: peer, sender: container.switchSender) { container.switchTarget = nil }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(3)
        }
        SwitchReceiveHost(importer: container.switchImporter).zIndex(3)
        QrShareHost(qrShare: container.qrShare).zIndex(2)
        DriveHostHost(host: container.vaultHost, open: $container.driveOpen).zIndex(2)
        VaultExplorerHost(explorer: container.vaultExplorer).zIndex(3)
    }

    // MARK: drag & drop

    /// Files dropped from Finder / Files / another app wait on the radar for a tap on a device — same as "Open in".
    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        let wanted = providers.filter { $0.hasItemConformingToTypeIdentifier(UTType.item.identifier) }
        guard !wanted.isEmpty else { return false }
        let container = self.container
        tab = .radar
        Task.detached {
            var urls: [URL] = [], temps: [URL] = []
            for p in wanted {
                // The provider's file is only valid inside the callback — copy it somewhere we own.
                let copied: URL? = await withCheckedContinuation { cont in
                    _ = p.loadFileRepresentation(forTypeIdentifier: UTType.item.identifier) { url, _ in
                        guard let url else { cont.resume(returning: nil); return }
                        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
                        do {
                            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                            let dest = dir.appendingPathComponent(url.lastPathComponent)
                            try FileManager.default.copyItem(at: url, to: dest)
                            cont.resume(returning: dest)
                        } catch { cont.resume(returning: nil) }
                    }
                }
                if let copied { urls.append(copied); temps.append(copied.deletingLastPathComponent()) }
            }
            let ready = (urls, temps)
            await MainActor.run {
                guard !ready.0.isEmpty else { container.networkNotice = "Couldn't read the dropped files."; return }
                container.queueShare(ready.0, temporary: ready.1)
            }
        }
        return true
    }

    private var dropHint: some View {
        RoundedRectangle(cornerRadius: 30, style: .continuous)
            .strokeBorder(CD.accent, style: StrokeStyle(lineWidth: 2, dash: [10, 8]))
            .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(CD.accent.opacity(0.08)))
            .overlay(Text("Drop to send").displayStyle(20, color: CD.accent))
            .padding(16)
            .allowsHitTesting(false)
            .transition(.opacity)
    }
}

// MARK: - Floating nav

/// The design's floating glass pill: the active tab expands into an accent capsule with its label.
private struct FloatingNav: View {
    @Binding var tab: AppTab
    @ObservedObject var inbox: InboxStore
    let transferLive: Bool
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(AppTab.allCases.enumerated()), id: \.element) { i, t in item(t, index: i) }
        }
        .padding(7)
        .frame(height: 66)
        .frame(maxWidth: 440)
        .background(
            ZStack {
                Capsule().fill(CD.inkNav).shadow(color: .black.opacity(0.55), radius: 15, x: 0, y: 10)
                Capsule().fill(.ultraThinMaterial)
                Capsule().fill(CD.inkNav.opacity(0.82))
            }
        )
        .overlay(Capsule().strokeBorder(CD.hairline, lineWidth: 1))
        .animation(.spring(response: 0.34, dampingFraction: 0.86), value: tab)
    }

    private func item(_ t: AppTab, index: Int) -> some View {
        let selected = t == tab
        let count = inbox.files.count
        let badge: String? = {
            if t == .inbox && count > 0 { return count > 99 ? "99+" : "\(count)" }
            if t == .transfers && transferLive { return "" }
            return nil
        }()
        let extra: String = {
            if t == .inbox && count > 0 { return ", \(count) file\(count == 1 ? "" : "s")" }
            if t == .transfers && transferLive { return ", transfer in progress" }
            return ""
        }()
        return Button { tab = t } label: {
            HStack(spacing: 6) {
                CdIconView(t.icon, color: selected ? CD.onAccent : CD.navIdle, size: selected ? 20 : 22)
                    .overlay(alignment: .topTrailing) {
                        if let badge, !selected { NavBadge(text: badge).offset(x: 9, y: -6) }
                    }
                if selected {
                    Text(t.rawValue).font(.cd(13, weight: .semibold)).foregroundColor(CD.onAccent)
                        .lineLimit(1).fixedSize()
                        .transition(.opacity.combined(with: .scale(scale: 0.8, anchor: .leading)))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if selected { Capsule().fill(CD.accent).matchedGeometryEffect(id: "tab", in: ns) }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(t.rawValue + extra)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .keyboardShortcut(KeyEquivalent(Character("\(index + 1)")), modifiers: .command)
        .pointerHover()
    }
}

private struct NavBadge: View {
    let text: String
    var body: some View {
        if text.isEmpty {
            Dot(color: CD.accent, size: 8)
        } else {
            Text(text).font(.cd(10, weight: .bold)).foregroundColor(CD.onAccent)
                .padding(.horizontal, 4)
                .frame(minWidth: 16, minHeight: 16)
                .background(Capsule().fill(CD.accent))
                .fixedSize()
        }
    }
}

// MARK: - Switch phones hosts (observe their own models)

private struct SwitchSendHost: View {
    let peer: Peer
    @ObservedObject var sender: SwitchSender
    let onClose: () -> Void
    var body: some View { SwitchSendScreen(peer: peer, sender: sender, onClose: onClose) }
}

private struct SwitchReceiveHost: View {
    @ObservedObject var importer: SwitchImporter
    @EnvironmentObject var coordinator: TransferCoordinator
    @State private var hidden = false
    var body: some View {
        Group {
            if let st = importer.state, !hidden {
                SwitchReceiveScreen(importer: importer, state: st,
                                    receiving: coordinator.sessions.last { $0.kind == OfferKind.switchPhones && !$0.status.isTerminal },
                                    onHide: { hidden = true })
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .onChange(of: importer.state?.peerName) { _ in hidden = false }
    }
}
