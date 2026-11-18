import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

enum AppTab: CaseIterable {
    case radar, transfers, inbox, settings

    var label: String {
        switch self {
        case .radar: return tr("Radar")
        case .transfers: return tr("Transfers")
        case .inbox: return tr("Inbox")
        case .settings: return tr("Settings")
        }
    }

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
    /// First-run welcome (skippable); Settings can replay it.
    @State private var welcome = !AppContainer.welcomeSeen
    /// "Can't see the other device?" how-to.
    @State private var guideOpen = false
    @Environment(\.sizeCategory) private var sizeCategory
    /// Bumped when the language changes: rebuilds the views so every translated string is read again.
    @ObservedObject private var i18n = I18nModel.shared

    init() {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "dev_tab"), let tab = AppTab.allCases.first(where: { String(describing: $0) == t }) {
            _tab = State(initialValue: tab)
        }
        if UserDefaults.standard.bool(forKey: "dev_hide_transfer") { _transferHidden = State(initialValue: true) }
        if UserDefaults.standard.bool(forKey: "dev_connect_guide") { _guideOpen = State(initialValue: true) }
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

                // Contact cards that just arrived: a floating card above the nav.
                ContactDropHost(inbox: container.contactInbox)
                    .padding(.horizontal, 16)
                    .padding(.bottom, navBottom + 66 + 12)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .ignoresSafeArea(.container, edges: .bottom)
                    .zIndex(4)

                FloatingSheetLayer(host: sheets).zIndex(5)

                // Inbound offer — always on top. Only its two buttons answer it: no swipe, no Esc, and a
                // system alert appearing over it can't decline it.
                if let offer = coordinator.incomingOffer {
                    IncomingOfferSheet(offer: offer) { accept in
                        if accept { transferHidden = false }
                        if accept, offer.kind == OfferKind.contact, ContactsBridge.undetermined() {
                            // Ask now, while they're looking at it; the answer goes out after the alert.
                            Task {
                                _ = await ContactsBridge.requestAccess()
                                coordinator.respondToOffer(offer, accept: true)
                            }
                        } else {
                            coordinator.respondToOffer(offer, accept: accept)
                        }
                    }
                    .transition(.opacity)
                    .zIndex(6)
                }

                if guideOpen {
                    ConnectGuideScreen(onClose: { guideOpen = false })
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .zIndex(3)
                }

                if welcome {
                    WelcomeScreen(permissionNote: tr("Your device will ask to find devices on your local network. Anything else, like photos or camera, is asked only when you use it."),
                                  onFinish: finishWelcome)
                        .transition(.opacity)
                        .zIndex(3)
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
        .animation(.easeInOut(duration: 0.3), value: welcome)
        .animation(.easeInOut(duration: 0.3), value: guideOpen)
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
        .id("\(sizeCategory)-\(i18n.version)")       // fonts follow the text-size setting and text follows the language: rebuild when either changes
    }

    private func finishWelcome() {
        let first = !AppContainer.welcomeSeen
        AppContainer.welcomeSeen = true
        welcome = false
        if first { container.restartDiscovery() }      // held back until now: the Local Network prompt follows the tour
    }

    // MARK: tabs

    @ViewBuilder private var tabs: some View {
        ZStack {
            // The radar stays mounted (its pickers and ⌘O handler live there); hidden it stops animating.
            RadarScreen(active: tab == .radar, onShowConnectGuide: { guideOpen = true })
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
                SettingsScreen(inbox: container.inbox, history: container.history, onShowWelcome: { welcome = true })
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
                guard !ready.0.isEmpty else { container.networkNotice = tr("Couldn't read the dropped files."); return }
                container.queueShare(ready.0, temporary: ready.1)
            }
        }
        return true
    }

    private var dropHint: some View {
        RoundedRectangle(cornerRadius: 30, style: .continuous)
            .strokeBorder(CD.accent, style: StrokeStyle(lineWidth: 2, dash: [10, 8]))
            .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(CD.accent.opacity(0.08)))
            .overlay(Text(tr("Drop to send")).displayStyle(20, color: CD.accent))
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
        let spoken: String = {
            if t == .inbox && count > 0 { return trPlural(count, one: "{0}, 1 file", other: "{0}, {n} files", t.label) }
            if t == .transfers && transferLive { return tr("{0}, transfer in progress", t.label) }
            return t.label
        }()
        return Button { tab = t } label: {
            HStack(spacing: 6) {
                CdIconView(t.icon, color: selected ? CD.onAccent : CD.navIdle, size: selected ? 20 : 22)
                    .overlay(alignment: .topTrailing) {
                        if let badge, !selected { NavBadge(text: badge).offset(x: 9, y: -6) }
                    }
                if selected {
                    Text(t.label).font(.cd(13, weight: .semibold)).foregroundColor(CD.onAccent)
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
        .accessibilityLabel(spoken)
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

/// The last contact cards that arrived: who was added (tap View to see them), who was already there, or
/// — without contacts access — a one-tap way to add what was kept in the inbox.
private struct ContactDropHost: View {
    @ObservedObject var inbox: ContactInbox
    @Environment(\.openURL) private var openURL

    var body: some View {
        Group {
            if let d = inbox.drop {
                card(d)
                    .id(d.id)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: d.id) {
                        // A finished add goes away by itself; a card waiting for access stays until answered.
                        guard d.kept == nil else { return }
                        try? await Task.sleep(nanoseconds: 12_000_000_000)
                        inbox.dismiss(d.id)
                    }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: inbox.drop?.id)
    }

    private func card(_ d: ContactInbox.Drop) -> some View {
        let first = d.names.first ?? tr("Contact")
        let n = d.names.count
        let waiting = d.kept != nil
        let title: String = {
            if d.unreadable { return tr("Couldn't read that contact card") }
            if waiting { return n == 1 ? tr("Add {0} to your contacts?", first) : trPlural(n, one: "Add 1 contact?", other: "Add {n} contacts?") }
            if d.added > 0 && d.skipped == 0 { return d.added == 1 ? tr("Added {0}", first) : trPlural(d.added, one: "Added 1 contact", other: "Added {n} contacts") }
            if d.added == 0 && d.skipped > 0 { return d.skipped == 1 && n == 1 ? tr("{0} is already in your contacts", first) : tr("Already in your contacts") }
            if d.added > 0 { return tr("Added {0} · {1} already there", d.added, d.skipped) }
            return tr("Couldn't add the contact")
        }()
        let subtitle = d.unreadable ? tr("From {0} · saved to your Inbox", d.peerName)
            : waiting ? tr("From {0} · CrossDrop needs contacts access. Until then it's in your Inbox.", d.peerName)
            : tr("From {0}", d.peerName)
        return GlassCard(radius: 24, fill: CD.glassRaised, border: CD.hairlineStrong, glow: CD.accent.opacity(0.16), glowRadius: 30,
                         padding: .all(14), spacing: 12) {
            HStack(spacing: 12) {
                ContactInitials(name: n == 1 ? first : "\(n)", size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).body(15, weight: .semibold).lineLimit(2)
                    Text(subtitle).body(12, CD.textDim).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                Spacer()
                if waiting && !d.unreadable {
                    ChipButton(text: tr("Later")) { inbox.dismiss(d.id) }
                    ChipButton(text: tr("Allow"), icon: CdIcons.contacts, fg: CD.onAccent, bg: CD.accent) { allow() }
                } else if let id = d.firstAdded {
                    ChipButton(text: tr("Done")) { inbox.dismiss(d.id) }
                    ChipButton(text: tr("View"), icon: CdIcons.contacts, fg: CD.onAccent, bg: CD.accent) {
                        ContactPickerPresenter.show(contactId: id)
                        inbox.dismiss(d.id)
                    }
                } else {
                    ChipButton(text: tr("Done")) { inbox.dismiss(d.id) }
                }
            }
        }
        .frame(maxWidth: 520)
        .accessibilityElement(children: .contain)
    }

    private func allow() {
        if ContactsBridge.undetermined() {
            Task { if await ContactsBridge.requestAccess() { await inbox.addKept() } }
        } else if ContactsBridge.canAdd() {
            Task { await inbox.addKept() }
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            // Refused once: iOS won't ask again, the switch is in Settings now.
            openURL(url)
        }
    }
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
