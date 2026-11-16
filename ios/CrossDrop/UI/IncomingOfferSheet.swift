import SwiftUI
@preconcurrency import CrossDropCore

/// Full-screen inbound OFFER. The ring around the sender counts down the coordinator's auto-decline
/// window (`TransferCoordinator.offerTimeout`); the human answers only with the two buttons.
struct IncomingOfferSheet: View {
    let offer: IncomingOffer
    let onRespond: (Bool) -> Void

    var body: some View {
        let files = offer.offer.files
        let total = files.reduce(Int64(0)) { $0 + $1.size }
        let timeout = TransferCoordinator.offerTimeout
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(centerY: 215, radius: 275)
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 22) {
                        HStack(spacing: 8) {
                            Dot(color: CD.accent)
                            Text("INCOMING FILES").font(.cd(12, weight: .semibold)).tracking(CDFont.scaled(12) * 0.06)
                                .foregroundColor(CD.textSecondary)
                        }
                        .padding(.horizontal, 14).frame(height: 32)
                        .background(Capsule().fill(CD.glassMid))
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.1), lineWidth: 1))

                        TimelineView(.periodic(from: offer.receivedAt, by: 1.0 / 30)) { ctx in
                            let elapsed = max(0, ctx.date.timeIntervalSince(offer.receivedAt))
                            CountdownAvatar(peerName: offer.peerName, fraction: 1 - elapsed / timeout)
                        }
                        .padding(.top, 10)

                        VStack(spacing: 8) {
                            Text("\(offer.peerName) wants to send you " + (files.count == 1 ? "a file" : "\(files.count) files"))
                                .displayStyle(28, tracking: -0.035).multilineTextAlignment(.center).lineLimit(3)
                                .accessibilityAddTraits(.isHeader)
                            TimelineView(.periodic(from: offer.receivedAt, by: 1)) { ctx in
                                let left = max(0, Int(ceil(timeout - ctx.date.timeIntervalSince(offer.receivedAt))))
                                (Text("\(Fmt.bytes(total)) · auto-declines in ").foregroundColor(CD.textDim).font(.cd(14))
                                    + Text("\(left)").font(.mono(14)).foregroundColor(CD.accent)
                                    + Text(" s").font(.cd(14)).foregroundColor(CD.accent))
                                    .multilineTextAlignment(.center)
                            }
                        }

                        GlassCard(radius: 28, fill: CD.glass, border: CD.hairline, padding: .all(6)) {
                            ForEach(Array(files.prefix(4).enumerated()), id: \.offset) { i, f in
                                if i > 0 { RowSeparator() }
                                HStack(spacing: 12) {
                                    FileTypeTile(fileName: f.name, size: 42)
                                    Text(f.name).body(14, weight: .semibold).lineLimit(1).truncationMode(.middle)
                                    Spacer(minLength: 8)
                                    Text(Fmt.bytes(f.size)).monoStyle(12, color: CD.textDim)
                                }
                                .padding(10)
                                .accessibilityElement(children: .combine)
                            }
                            if files.count > 4 {
                                RowSeparator()
                                Text("+ \(files.count - 4) more").body(13, CD.textDim)
                                    .padding(.horizontal, 10).padding(.vertical, 12)
                            }
                        }
                    }
                    .padding(.top, 24)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
                }

                GeometryReader { g in
                    let w = g.size.width - 10
                    HStack(spacing: 10) {
                        PillButton("Decline", style: .outline, height: 62, fontSize: 16) { onRespond(false) }
                            .frame(width: w * 0.4)
                        PillButton("Accept", style: .accent, height: 62, icon: CdIcons.check, fontSize: 16, glow: true) { onRespond(true) }
                            .frame(width: w * 0.6)
                    }
                }
                .frame(height: 62)
                .frame(maxWidth: 520)
                .padding(.top, 16)
            }
            .padding(.horizontal, 16).padding(.bottom, 22)
        }
        .accessibilityAddTraits(.isModal)
    }
}

/// Sender glyph in a glass disc, ringed by the auto-decline countdown and a soft pulse.
private struct CountdownAvatar: View {
    let peerName: String
    let fraction: Double
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().strokeBorder(CD.accent, lineWidth: 1.5)
                .scaleEffect(pulse ? 1.25 : 0.6)
                .opacity(pulse ? 0 : 0.6)
            Circle().inset(by: 4).stroke(Color.white.opacity(0.08), lineWidth: 4)
            Circle().inset(by: 4).trim(from: 0, to: min(1, max(0, fraction)))
                .stroke(CD.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            CdIconView(deviceIconFor(peerName), color: CD.textPrimary, size: 44)
                .frame(width: 112, height: 112)
                .background(
                    ZStack {
                        Circle().fill(CD.ink).shadow(color: CD.accent.opacity(0.18), radius: 25)
                        Circle().fill(CD.glassRaised)
                    }
                )
                .overlay(Circle().strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
        }
        .frame(width: 168, height: 168)
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 2.4).repeatForever(autoreverses: false)) { pulse = true }
        }
    }
}
