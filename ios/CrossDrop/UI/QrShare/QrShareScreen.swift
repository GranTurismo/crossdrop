import SwiftUI
import CoreImage.CIFilterBuiltins
import CrossDropCore

/// Observes the share manager directly (nested ObservableObjects don't propagate through AppContainer)
/// and slides the QR overlay over the app while a share is live.
struct QrShareHost: View {
    @ObservedObject var qrShare: QrShareManager

    var body: some View {
        ZStack {
            if let s = qrShare.state {
                QrShareScreen(state: s, onStop: qrShare.stop)
                    .background(Aurora.deepSpace.ignoresSafeArea())
                    .transition(.move(edge: .bottom))
                    .zIndex(2)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: qrShare.state != nil)
    }
}

/// "Send to anyone": the receiver scans the QR with any camera app and gets a browser-hosted file
/// list — no CrossDrop install needed on their end.
struct QrShareScreen: View {
    let state: QrShareManager.State
    let onStop: () -> Void
    @State private var copied = false

    var body: some View {
        ZStack {
            AuroraBackdrop()
            ReadableScroll {
                VStack(spacing: 0) {
                    Text("S H A R E   T O   A N Y O N E").font(.cd(12, weight: .medium)).tracking(1)
                        .foregroundColor(Aurora.textDim).padding(.top, 24)
                    Text("Scan with any camera — no app needed")
                        .font(.cd(18, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                        .padding(.top, 4).padding(.bottom, 22)

                    QrCard(text: state.url)

                    Button {
                        UIPasteboard.general.string = state.url
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { copied = false }
                    } label: {
                        VStack(spacing: 3) {
                            Text(state.url).font(.cd(11)).foregroundColor(Aurora.textDim)
                                .multilineTextAlignment(.center).textSelection(.enabled)
                            Text(copied ? "copied" : "tap to copy").font(.cd(10, weight: .medium))
                                .foregroundColor(copied ? Aurora.phosphor : Aurora.teal)
                        }
                        .padding(.horizontal, 20)
                    }
                    .buttonStyle(.plain).padding(.top, 14)

                    statusLine.padding(.top, 10)

                    GlassCard(radius: 18) {
                        VStack(spacing: 8) {
                            ForEach(Array(state.files.prefix(6).enumerated()), id: \.offset) { _, f in
                                HStack(spacing: 10) {
                                    FileTypeTile(fileName: f.name, size: 34)
                                    Text(f.name).font(.cd(13)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                                    Spacer(minLength: 8)
                                    Text(Fmt.bytes(f.size)).font(.cd(12)).foregroundColor(Aurora.textDim)
                                }
                            }
                            if state.files.count > 6 {
                                Text("+ \(state.files.count - 6) more").font(.cd(11)).foregroundColor(Aurora.textDim)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 44)
                            }
                        }
                        .padding(12)
                    }
                    .padding(.top, 18)

                    Text("Both devices must stay on this Wi-Fi. The link stops working when you close this screen.")
                        .font(.cd(11)).foregroundColor(Aurora.textDim).multilineTextAlignment(.center)
                        .padding(.horizontal, 12).padding(.top, 16)

                    Group {
                        if state.delivered {
                            Button("Done", action: onStop).buttonStyle(PrimaryButtonStyle()).frame(maxWidth: 220)
                        } else {
                            Button("Stop sharing", action: onStop)
                                .font(.cd(15, weight: .medium)).foregroundColor(Aurora.danger).padding(.vertical, 12)
                        }
                    }
                    .padding(.top, 20).padding(.bottom, 24)
                }
                .padding(.horizontal, 24)
            }
        }
    }

    @ViewBuilder private var statusLine: some View {
        let (text, color): (String, Color) = {
            if state.delivered { return ("Delivered — all files downloaded", Aurora.phosphor) }
            if state.downloadsStarted > 0 {
                return ("\(state.downloadsDone) of \(state.downloadsStarted) downloads done · \(Fmt.bytes(state.bytesServed))", Aurora.cyan)
            }
            if state.connected { return ("Receiver connected — waiting for downloads", Aurora.teal) }
            return ("Waiting for a scan…", Aurora.textDim)
        }()
        Text(text).font(.cd(13, weight: .medium)).foregroundColor(color)
    }
}

/// QR drawn on a light card — scanners need a light quiet zone, so the dark theme inverts here.
struct QrCard: View {
    let text: String
    var size: CGFloat = 252

    var body: some View {
        RoundedRectangle(cornerRadius: 24, style: .continuous)
            .fill(Color(hex: 0xF2FBFF))
            .frame(width: size, height: size)
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Aurora.gradient, lineWidth: 2))
            .overlay(
                Group {
                    if let img = Self.image(for: text) {
                        Image(uiImage: img).interpolation(.none).resizable().scaledToFit().padding(18)
                    }
                }
            )
    }

    /// Dark modules on a light ground, rendered at an integer module scale so edges stay crisp.
    static func image(for text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let raw = f.outputImage else { return nil }
        let tint = CIFilter.falseColor()
        tint.inputImage = raw
        tint.color0 = CIColor(red: 3 / 255, green: 0, blue: 20 / 255)
        tint.color1 = CIColor(red: 0xF2 / 255, green: 0xFB / 255, blue: 1)
        guard let colored = tint.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(colored, from: colored.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
