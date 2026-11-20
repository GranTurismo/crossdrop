import SwiftUI

/// What the radar should tell someone about the network, if anything.
/// Mirrors `core/connect/ConnectAdvice.kt`: keep the thresholds and the rule in step.
enum ConnectHint {
    case none
    /// No Wi-Fi or Ethernet at all.
    case noNetwork
    /// On a network, but nobody has shown up: the other device is elsewhere, or the network blocks it.
    case nobodyFound
}

enum ConnectAdvice {
    static let stuckAfter: TimeInterval = 10
    static let noNetworkAfter: TimeInterval = 2

    static func hint(hasNetwork: Bool, deviceCount: Int, emptyFor: TimeInterval) -> ConnectHint {
        if deviceCount > 0 { return .none }
        if !hasNetwork { return emptyFor >= noNetworkAfter ? .noNetwork : .none }
        return emptyFor >= stuckAfter ? .nobodyFound : .none
    }
}

/// Counts how long the radar has been empty (restarting when a device appears or the network changes)
/// and turns that into a [ConnectHint].
@MainActor
final class ConnectHintModel: ObservableObject {
    @Published private(set) var hint: ConnectHint = .none
    private var emptySince = Date()
    private var hasNetwork = true
    private var deviceCount = 0
    private var timer: Timer?

    func update(hasNetwork: Bool, deviceCount: Int) {
        if hasNetwork != self.hasNetwork || (deviceCount == 0) != (self.deviceCount == 0) { emptySince = Date() }
        self.hasNetwork = hasNetwork
        self.deviceCount = deviceCount
        recompute()
        if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.recompute() }
            }
        }
    }

    func stop() { timer?.invalidate(); timer = nil }

    private func recompute() {
        let next = ConnectAdvice.hint(hasNetwork: hasNetwork, deviceCount: deviceCount, emptyFor: Date().timeIntervalSince(emptySince))
        if next != hint { hint = next }
    }
}

/// "Can't see the other device?" — shown on the radar when the hint says so.
/// An iPhone can't create a network for others, but it can join one: `onScan` ("Scan a code", iPhone/iPad only) reads
/// the other phone's private-link code; `onGuide` opens the plain how-to.
struct ConnectHelpCard: View {
    let hint: ConnectHint
    let onGuide: () -> Void
    var onScan: (() -> Void)? = nil

    private var title: String { hint == .noNetwork ? tr("You're not on Wi-Fi") : tr("Can't see the other device?") }
    private var message: String {
        hint == .noNetwork ? tr("Join the same Wi-Fi, or turn on a hotspot on one device.")
            : tr("Is CrossDrop open there, on the same Wi-Fi? Guest and hotel Wi-Fi can hide devices.")
    }

    var body: some View {
        GlassCard(radius: 26, fill: CD.amber.opacity(0.06), border: CD.amber.opacity(0.28), padding: .all(14), spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                IconTile(icon: CdIcons.wifi, tint: CD.amber, size: 36, radius: 12, iconSize: 18)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).body(15, CD.textPrimary, weight: .semibold)
                    Text(message).body(13, CD.textDim).lineSpacing(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                if let onScan {
                    ChipButton(text: tr("Scan a code"), fg: CD.onAccent, bg: CD.accent, height: 38, fontSize: 14, action: onScan)
                }
                ChipButton(text: tr("Show me how"), height: 38, fontSize: 14, action: onGuide)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// Plain-words guide to getting two devices on one network. Mirrors `ui/connect/ConnectHelp.kt`.
struct ConnectGuideScreen: View {
    let onClose: () -> Void

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.16, centerX: 0.3, centerY: 120, radius: 240)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    BackHeader(tr("Connect two devices"), onBack: onClose)
                    Text(tr("CrossDrop sends straight between devices on the same network, without the internet. Pick what fits:"))
                        .body(14, CD.textDim).lineSpacing(3).fixedSize(horizontal: false, vertical: true)

                    SectionLabel(tr("Use a hotspot")).padding(.top, 4)
                    GroupCard(padding: .hv(14, 4)) {
                        step(1, tr("Turn on a hotspot on one phone"), tr("Settings → Hotspot (Personal Hotspot on iPhone)."))
                        RowSeparator()
                        step(2, tr("Join it from the other device"), tr("Pick that hotspot's name in the other device's Wi-Fi list."))
                        RowSeparator()
                        step(3, tr("Open CrossDrop on both"), tr("They find each other in a few seconds. Transfers don't use mobile data."))
                    }

                    SectionLabel(tr("The other person has no app?")).padding(.top, 4)
                    GlassCard(padding: .all(16)) {
                        Text(tr("Once you're on the same network, tap Send to anyone on the radar. They scan the code and download the files in their browser. Nothing to install."))
                            .body(13, CD.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
                    }

                    SectionLabel(tr("Still stuck?")).padding(.top, 4)
                    GroupCard(padding: .hv(14, 4)) {
                        tip(tr("Turn Wi-Fi off and on, on both devices."))
                        RowSeparator()
                        tip(tr("Turn off any VPN on both devices."))
                        RowSeparator()
                        tip(tr("Close CrossDrop and open it again on both."))
                    }
                }
                .frame(maxWidth: 560)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func step(_ n: Int, _ title: String, _ body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            StepNumber(n: n)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).body(14, CD.textPrimary, weight: .semibold)
                Text(body).body(13, CD.textDim).lineSpacing(2).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
    }

    private func tip(_ text: String) -> some View {
        Text(text).body(13, CD.textSecondary).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12)
    }
}
