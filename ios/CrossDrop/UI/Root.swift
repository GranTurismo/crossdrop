import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

enum AppTab: String, CaseIterable {
    case radar = "Radar", transfers = "Transfers", inbox = "Inbox", settings = "Settings"
    var symbol: String {
        switch self {
        case .radar: return "dot.radiowaves.left.and.right"
        case .transfers: return "arrow.up.arrow.down"
        case .inbox: return "tray"
        case .settings: return "gearshape"
        }
    }
}

struct RootView: View {
    @EnvironmentObject var container: AppContainer
    @EnvironmentObject var coordinator: TransferCoordinator
    @State private var tab: AppTab = .radar
    @State private var dropTargeted = false
    @Environment(\.sizeCategory) private var sizeCategory

    init() {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "dev_tab"), let tab = AppTab.allCases.first(where: { $0.rawValue.lowercased() == t }) {
            _tab = State(initialValue: tab)
        }
        #endif
        let a = UITabBarAppearance()
        a.configureWithOpaqueBackground()
        a.backgroundColor = UIColor(Aurora.deepSpaceSoft)
        UITabBar.appearance().standardAppearance = a
        UITabBar.appearance().scrollEdgeAppearance = a
    }

    var body: some View {
        ZStack {
            Aurora.deepSpace.ignoresSafeArea()
            content
            if dropTargeted { dropHint }
        }
        .onDrop(of: [UTType.item], isTargeted: $dropTargeted, perform: handleDrop)
        .animation(.easeInOut(duration: 0.3), value: coordinator.session != nil)
        // Only the Accept/Decline buttons answer an offer — a stray system alert or swipe must not decline it.
        .sheet(item: Binding(get: { coordinator.incomingOffer }, set: { _ in })) { offer in
            IncomingOfferSheet(offer: offer) { coordinator.respondToOffer(accept: $0) }
                .presentationDetents([.medium, .large])
                .interactiveDismissDisabled(true)
        }
    }

    /// Files dropped from Finder / Files / another app wait on the radar for a tap on a device — same as "Open in".
    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        let wanted = providers.filter { $0.hasItemConformingToTypeIdentifier(UTType.item.identifier) }
        guard !wanted.isEmpty else { return false }
        let container = self.container
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
        RoundedRectangle(cornerRadius: 24, style: .continuous)
            .strokeBorder(Aurora.teal, style: StrokeStyle(lineWidth: 3, dash: [10, 8]))
            .background(Aurora.teal.opacity(0.08), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(Text("Drop to send").font(.cd(18, weight: .semibold)).foregroundColor(Aurora.teal))
            .padding(16)
            .allowsHitTesting(false)
            .transition(.opacity)
    }

    private var content: some View {
        ZStack {
            TabView(selection: $tab) {
                RadarScreen().tag(AppTab.radar)
                    .tabItem { Label(AppTab.radar.rawValue, systemImage: AppTab.radar.symbol) }
                TransfersScreen(session: coordinator.session, history: container.history).tag(AppTab.transfers)
                    .tabItem { Label(AppTab.transfers.rawValue, systemImage: AppTab.transfers.symbol) }
                InboxScreen(inbox: container.inbox).tag(AppTab.inbox)
                    .tabItem { Label(AppTab.inbox.rawValue, systemImage: AppTab.inbox.symbol) }
                    .badge(container.inbox.files.count)
                SettingsScreen(inbox: container.inbox, history: container.history).tag(AppTab.settings)
                    .tabItem { Label(AppTab.settings.rawValue, systemImage: AppTab.settings.symbol) }
            }
            .tint(Aurora.teal)

            if let s = coordinator.session {
                TransferOverlay(session: s, onCancel: coordinator.cancelSession, onClose: coordinator.dismissSession)
                    .background(Aurora.deepSpace.ignoresSafeArea())
                    .transition(.move(edge: .bottom))
                    .zIndex(1)
            }
            QrShareHost(qrShare: container.qrShare)
            VaultExplorerHost(explorer: container.vaultExplorer)
            DriveHostHost(host: container.vaultHost, open: $container.driveOpen)
        }
        .id(sizeCategory)       // fonts are computed from the text-size setting — rebuild when it changes
    }
}
