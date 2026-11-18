import SwiftUI
import CrossDropCore

/// Observes the share manager directly (nested ObservableObjects don't propagate through AppContainer)
/// and slides the QR overlay over the app while a share is live.
struct QrShareHost: View {
    @ObservedObject var qrShare: QrShareManager

    var body: some View {
        ZStack {
            if let s = qrShare.state {
                QrShareScreen(state: s, onStop: qrShare.stop)
                    .background(CD.ink.ignoresSafeArea())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: qrShare.state != nil)
    }
}

/// "Send to anyone": the receiver scans the QR with any camera and gets a browser page with the files —
/// no CrossDrop needed on their end. A three-step strip tracks scan → connected → delivered. The screen
/// stays awake (KeepAwake "share") so the code stays scannable; leaving it stops the share.
struct QrShareScreen: View {
    let state: QrShareManager.State
    let onStop: () -> Void

    private var step: Int {
        if state.delivered { return 2 }
        if state.connected || state.downloadsStarted > 0 { return 1 }
        return 0
    }

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.14, centerY: 310, radius: 240)
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        BackHeader(tr("Send to anyone"), backLabel: tr("Stop sharing and go back"), onBack: onStop)
                        StepStrip(step: step, state: state)
                        GeometryReader { g in
                            QrCard(text: state.url, size: min(236, g.size.width - 40)).frame(maxWidth: .infinity)
                        }
                        .frame(height: 236)
                        .padding(.top, 8)
                        Text(tr("Scan with any camera — no app needed")).body(14, CD.textSecondary)
                            .multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 6)

                        HStack(spacing: 10) {
                            Text(state.url.replacingOccurrences(of: "http://", with: ""))
                                .monoStyle(13, color: CD.textSecondary).lineLimit(1).truncationMode(.middle)
                                .textSelection(.enabled)
                            Spacer(minLength: 0)
                            CopyButton(value: state.url, label: tr("Copy link"), accent: true, height: 44)
                        }
                        .padding(.leading, 16).padding(.trailing, 6).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(CD.glass))
                        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(CD.hairline, lineWidth: 1))

                        FilesSummary(files: state.files)

                        FootNote(tr("They need to be on this Wi-Fi. Without the secret link the page doesn't exist, and it stops working the moment you leave this screen.")
                                 + (Platform.pausesInBackground ? " " + tr("Keep CrossDrop open while they download.") : ""))
                    }
                }
                Group {
                    if state.delivered { PillButton(tr("Done"), height: 56, action: onStop) }
                    else { PillButton(tr("Stop sharing"), style: .danger, height: 56, action: onStop) }
                }
                .padding(.top, 16)
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 22)
        }
    }
}

private struct StepStrip: View {
    let step: Int
    let state: QrShareManager.State
    @State private var blink = false

    var body: some View {
        let labels = [
            tr("Waiting for a scan"),
            state.downloadsStarted > 0 && !state.delivered ? tr("{0} of {1} downloaded", state.downloadsDone, state.files.count) : tr("Receiver connected"),
            tr("Delivered"),
        ]
        HStack(alignment: .top, spacing: 6) {
            ForEach(0..<3, id: \.self) { i in
                let done = i < step || (i == 2 && step == 2)
                let current = i == step
                let lit = done || current
                VStack(alignment: .leading, spacing: 6) {
                    Capsule().fill(lit ? CD.accent : Color.white.opacity(0.1)).frame(height: 4)
                        .shadow(color: current ? CD.accent : .clear, radius: current ? 5 : 0)
                    HStack(spacing: 5) {
                        if current && step < 2 {
                            Circle().fill(CD.accent).frame(width: 6, height: 6).opacity(blink ? 0.35 : 1)
                        }
                        Text(labels[i]).font(.cd(11, weight: lit ? .semibold : .regular))
                            .foregroundColor(lit ? CD.accent : CD.textFaint).lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Step {0} of 3: {1}", step + 1, labels[step]))
        .onAppear { withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { blink = true } }
        .onChange(of: step) { _ in UIAccessibility.post(notification: .announcement, argument: labels[step]) }
    }
}

private struct FilesSummary: View {
    let files: [FileMeta]
    var body: some View {
        let usedBytes = Fmt.bytes(files.reduce(0) { $0 + $1.size })
        GlassCard(radius: 22, fill: CD.glassLow, border: nil, padding: .hv(14, 12)) {
            HStack(spacing: 0) {
                ZStack(alignment: .leading) {
                    ForEach(Array(files.prefix(2).enumerated()), id: \.offset) { i, f in
                        FileTypeTile(fileName: f.name, size: 36)
                            .padding(2)
                            .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(CD.inkSheet))
                            .offset(x: CGFloat(i) * 26)
                    }
                }
                .frame(width: files.count > 1 ? 66 : 40, alignment: .leading)
                .padding(.trailing, 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(trPlural(files.count, one: "1 file", other: "{n} files") + " · " + usedBytes)
                        .body(14, weight: .semibold)
                    Text(files.prefix(3).map(\.name).joined(separator: ", ")
                         + (files.count > 3 ? " +\(files.count - 3)" : "")
                         + (files.count > 1 ? " · " + tr("or all as one zip") : ""))
                        .body(12, CD.textDim).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
