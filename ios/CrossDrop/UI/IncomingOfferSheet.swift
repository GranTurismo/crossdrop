import SwiftUI
@preconcurrency import CrossDropCore

/// Full-screen inbound OFFER. The ring around the sender counts down the coordinator's auto-decline
/// window (`TransferCoordinator.offerTimeout`); the human answers only with the two buttons.
struct IncomingOfferSheet: View {
    let offer: IncomingOffer
    let onRespond: (Bool) -> Void

    var body: some View {
        let files = offer.offer.files
        let batchBytes = offer.offer.batch?.totalBytes ?? 0
        let total = batchBytes > 0 ? batchBytes : files.reduce(Int64(0)) { $0 + $1.size }
        let timeout = TransferCoordinator.offerTimeout
        let text = offer.text
        let link = text.map(isLinkText) ?? false
        let camera = offer.kind == OfferKind.camera
        let switching = offer.kind == OfferKind.switchPhones
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(centerY: 215, radius: 275)
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 22) {
                        HStack(spacing: 8) {
                            Dot(color: CD.accent)
                            Text(text != nil ? (link ? "INCOMING LINK" : "INCOMING TEXT") : camera ? "LIVE CAMERA"
                                 : switching ? "SWITCH PHONES" : "INCOMING FILES").font(.cd(12, weight: .semibold)).tracking(CDFont.scaled(12) * 0.06)
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
                            Text(text != nil ? "\(offer.peerName) sent you " + (link ? "a link" : "some text")
                                 : camera ? "\(offer.peerName) wants to share their camera"
                                 : switching ? "\(offer.peerName) wants to move to this \(Platform.deviceNoun)"
                                 : "\(offer.peerName) wants to send you " + (files.count == 1 ? "a file" : "\(files.count) files"))
                                .displayStyle(28, tracking: -0.035).multilineTextAlignment(.center).lineLimit(3)
                                .accessibilityAddTraits(.isHeader)
                            TimelineView(.periodic(from: offer.receivedAt, by: 1)) { ctx in
                                let left = max(0, Int(ceil(timeout - ctx.date.timeIntervalSince(offer.receivedAt))))
                                (Text((text == nil && !camera ? "\(Fmt.bytes(total)) · " : "") + "auto-declines in ").foregroundColor(CD.textDim).font(.cd(14))
                                    + Text("\(left)").font(.mono(14)).foregroundColor(CD.accent)
                                    + Text(" s").font(.cd(14)).foregroundColor(CD.accent))
                                    .multilineTextAlignment(.center)
                            }
                        }

                        if let text {
                            GlassCard(radius: 28, fill: CD.glass, border: CD.hairline, padding: .all(16)) {
                                Text(text).font(link ? .mono(14) : .cd(15)).foregroundColor(CD.textPrimary).lineLimit(12)
                                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                            }
                        } else if camera {
                            GlassCard(radius: 28, fill: CD.glass, border: CD.hairline, padding: .all(16)) {
                                Text("Every photo and video they take shows up here the moment it's taken, without asking again. Closing the viewer ends it.")
                                    .body(14, CD.textSecondary).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        } else if switching {
                            SwitchSummary(offer: offer.offer)
                        } else {
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
                    }
                    .padding(.top, 24)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
                }

                GeometryReader { g in
                    let w = g.size.width - 10
                    HStack(spacing: 10) {
                        if let text {
                            PillButton("Dismiss", style: .outline, height: 62, fontSize: 16) { onRespond(false) }
                            PillButton("Copy", style: link ? .glass : .accent, height: 62, icon: CdIcons.copy, fontSize: 16, glow: !link) {
                                UIPasteboard.general.string = text
                                onRespond(true)
                            }
                            if link {
                                PillButton("Open", style: .accent, height: 62, icon: CdIcons.globe, fontSize: 16, glow: true) {
                                    onRespond(true)
                                    if let u = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) { UIApplication.shared.open(u) }
                                }
                            }
                        } else {
                            PillButton("Decline", style: .outline, height: 62, fontSize: 16) { onRespond(false) }
                                .frame(width: w * 0.4)
                            PillButton(camera ? "Watch" : switching ? "Start moving" : "Accept", style: .accent, height: 62,
                                       icon: CdIcons.check, fontSize: 16, glow: true) { onRespond(true) }
                                .frame(width: w * 0.6)
                        }
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

/// What a phone move brings, from the batch totals.
private struct SwitchSummary: View {
    let offer: Offer
    var body: some View {
        let b = offer.batch
        let rows: [(CdIcon, String, Int)] = [
            (CdIcons.photo, "Photos", b?.photos ?? offer.files.filter { $0.cat == ItemCategory.photo }.count),
            (CdIcons.video, "Videos", b?.videos ?? offer.files.filter { $0.cat == ItemCategory.video }.count),
            (CdIcons.contacts, "Contacts", b?.contacts ?? 0),
            (CdIcons.doc, "Files", b?.files ?? 0),
        ].filter { $0.2 > 0 }
        GlassCard(radius: 28, fill: CD.glass, border: CD.hairline, padding: .all(6)) {
            ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                if i > 0 { RowSeparator() }
                HStack(spacing: 12) {
                    CdIconView(r.0, color: CD.accent, size: 22)
                    Text(r.1).body(15, weight: .semibold)
                    Spacer()
                    Text("\(r.2)").monoStyle(14, color: CD.textSecondary)
                }
                .padding(12)
            }
            Text("Photos and videos go to your library with their original dates, contacts to your address book. Keep both devices awake and on this Wi-Fi.")
                .body(12, CD.textDim).padding(.horizontal, 12).padding(.vertical, 10)
        }
    }
}
