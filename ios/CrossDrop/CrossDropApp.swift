import SwiftUI

@main
struct CrossDropApp: App {
    @StateObject private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .environmentObject(container.coordinator)
                .preferredColorScheme(.dark)
                .onAppear {
                    container.start()
                    Self.configureWindows()
                }
                .onOpenURL { url in container.queueShare([url]) }
                .onChange(of: scenePhase) { phase in
                    if phase == .background { container.didEnterBackground() }
                    if phase == .active { container.resume() }
                }
        }
        .commands {
            // One app-wide window; File menu on Mac, ⌘-key overlay on iPad.
            CommandGroup(replacing: .newItem) {
                Button("Send Files…") { container.requestSend() }
                    .keyboardShortcut("o", modifiers: .command)
                Button("Rescan Network") { container.restartDiscovery() }
                    .keyboardShortcut("r", modifiers: .command)
            }
        }
    }

    /// Mac window can't shrink below what the radar and tab bar need.
    private static func configureWindows() {
        #if targetEnvironment(macCatalyst)
        for scene in UIApplication.shared.connectedScenes {
            (scene as? UIWindowScene)?.sizeRestrictions?.minimumSize = CGSize(width: 420, height: 640)
            #if DEBUG
            // Store screenshots: `-dev_window 1280x800` pins the window to an exact size.
            if let v = UserDefaults.standard.string(forKey: "dev_window") {
                let p = v.split(separator: "x").compactMap { Double($0) }
                if p.count == 2, let r = (scene as? UIWindowScene)?.sizeRestrictions {
                    r.minimumSize = CGSize(width: p[0], height: p[1]); r.maximumSize = CGSize(width: p[0], height: p[1])
                }
            }
            #endif
        }
        #endif
    }
}
