import SwiftUI

/// Tab labels (a computed property: the language is read when the screen is drawn, not once at launch).
var guideTabs: [String] { ["Mac", "Windows", tr("Browser")] }

/// "Can't connect?" — the full per-platform guide with copyable address and passcode, plus
/// troubleshooting for guest Wi-Fi, VPNs and public networks. Language stays physical: "address",
/// "passcode", never "server".
struct DriveGuideScreen: View {
    let state: VaultHostManager.State
    let onClose: () -> Void
    @State private var tab = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                BackHeader(tr("Connect to your drive"), onBack: onClose)
                Text(tr("Pick the device you're connecting from — no apps to install.")).body(13, CD.textDim)
                    .padding(.horizontal, 4)
                SegmentedTabs(options: guideTabs, selected: $tab)
                GlassCard(radius: 28, fill: CD.glassLow, spacing: 14) {
                    ConnectSteps(state: state, tab: tab, withCopy: true)
                }
                SectionLabel(tr("Troubleshooting")).padding(.top, 6)
                GroupCard(padding: .hv(6, 2)) {
                    TroubleItem(title: tr("Devices can't see the drive?"),
                                text: tr("Check both devices are on the exact same Wi-Fi — not one on Guest and one on Main. Turn off any VPN apps on both devices. If asked to allow CrossDrop on your local network, make sure it's switched on in Settings (System Settings → Privacy & Security → Local Network on a Mac)."))
                    if Platform.pausesInBackground {
                        RowSeparator()
                        TroubleItem(title: tr("The drive stops when I switch apps?"),
                                    text: tr("iOS pauses apps in the background, so the drive only answers while CrossDrop is open. Keep it on screen while others connect — the screen stays awake automatically."))
                    }
                    RowSeparator()
                    TroubleItem(title: tr("On hotel, school or public Wi-Fi?"),
                                text: tr("Public networks often block devices from talking to each other. Turn on a phone's Personal Hotspot and connect the other device to it — the drive works instantly at full speed."))
                    RowSeparator()
                    TroubleItem(title: tr("Video buffers or previews are slow?"),
                                text: tr("Move the devices closer to each other or to the router — the drive runs at the speed of your Wi-Fi."))
                }
            }
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 28)
            .readableWidth(560)
        }
    }
}

/// Numbered steps for one platform. `withCopy` adds copy buttons for the address, name and passcode
/// (the full guide); the live drive card uses the compact form.
struct ConnectSteps: View {
    let state: VaultHostManager.State
    let tab: Int
    let withCopy: Bool

    var body: some View {
        let pin = state.pin
        switch tab {
        case 0:
            GuideStep(n: 1) {
                keycapText(tr("In Finder, press {0}"), key: "⌘K")
                    .body(13, CD.textSecondary)
            }
            GuideStep(n: 2, text: tr("Paste the address and press Connect"), copy: withCopy ? state.url : nil)
            if let pin {
                GuideStep(n: 3, text: tr("Choose Registered User and sign in with name “drive” and the passcode. It mounts like a disk."))
                if withCopy { CredentialRows(pin: pin) }
            } else {
                GuideStep(n: 3, text: tr("Choose Guest. It mounts like a disk — drag files in or out, or scrub video right on the drive."))
            }
        case 1:
            GuideStep(n: 1, text: tr("Open This PC, then ••• (or right-click empty space) → Add a network location"))
            GuideStep(n: 2, text: tr("Choose a custom network location and paste the address"), copy: withCopy ? state.url : nil)
            if let pin {
                GuideStep(n: 3, text: tr("When asked, sign in with name “drive” and the passcode. A drive folder appears under This PC."))
                if withCopy { CredentialRows(pin: pin) }
            } else {
                GuideStep(n: 3, text: tr("A drive folder appears under This PC — it behaves like a plugged-in USB stick."))
            }
        default:
            GuideStep(n: 1, text: tr("On any laptop, tablet or phone on this Wi-Fi, open a browser — Safari, Chrome, Edge"))
            GuideStep(n: 2, text: tr("Scan the QR, or type the address"), copy: withCopy ? state.url : nil)
            GuideStep(n: 3, text: pin != nil ? tr("Enter the 4-digit passcode. Browse, stream or download — works on iPhone and iPad too.")
                                             : tr("Browse folders, stream video or download any file — works on iPhone and iPad too."),
                      copy: withCopy ? pin : nil)
        }
    }
}

/// One translated sentence with a `{0}` where the key goes, shown as a keycap.
private func keycapText(_ sentence: String, key: String) -> Text {
    let cap = Text(verbatim: " \(key) ").font(.mono(13)).foregroundColor(CD.textPrimary)
    guard let at = sentence.range(of: "{0}") else { return Text(sentence) }
    return Text(String(sentence[..<at.lowerBound])) + cap + Text(String(sentence[at.upperBound...]))
}

private struct GuideStep<Body_: View>: View {
    let n: Int
    var copy: String?
    @ViewBuilder var content: () -> Body_

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            StepNumber(n: n)
            VStack(alignment: .leading, spacing: 8) {
                content().fixedSize(horizontal: false, vertical: true)
                if let copy { CopyField(value: copy) }
            }
            .padding(.top, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
    }
}

extension GuideStep where Body_ == Text {
    init(n: Int, text: String, copy: String? = nil) {
        self.init(n: n, copy: copy) { Text(text).body(13, CD.textSecondary) }
    }
}

private struct CredentialRows: View {
    let pin: String
    var body: some View {
        VStack(spacing: 8) {
            CopyField(value: "drive", label: tr("name"))
            CopyField(value: pin, label: tr("passcode"))
        }
        .padding(.leading, 38)
    }
}

struct CopyField: View {
    let value: String
    var label: String?
    var body: some View {
        HStack(spacing: 0) {
            if let label { Text("\(label)  ").body(12, CD.textDim) }
            Text(value).monoStyle(13, color: CD.accent).lineLimit(1).minimumScaleFactor(0.7).truncationMode(.middle)
                .textSelection(.enabled)
            Spacer(minLength: 8)
            CopyButton(value: value)
        }
        .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 0)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(CD.glassLow))
    }
}

private struct TroubleItem: View {
    let title: String
    let text: String
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation(.easeInOut(duration: 0.2)) { open.toggle() } } label: {
                HStack(spacing: 12) {
                    CdIconView(CdIcons.help, color: CD.amber, size: 20)
                    Text(title).body(14, weight: .medium).multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    CdIconView(open ? CdIcons.arrowUp : CdIcons.chevronRight, color: CD.textDim, size: 16)
                }
                .padding(.horizontal, 10).padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle())
            .accessibilityValue(open ? tr("Expanded") : tr("Collapsed"))
            .pointerHover()
            if open {
                Text(text).body(13, CD.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 42).padding(.trailing, 12).padding(.bottom, 14)
                    .transition(.opacity)
            }
        }
    }
}
