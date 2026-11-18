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
        let contact = offer.kind == OfferKind.contact
        let cards = offer.contacts
        let cardCount = max(cards.count, 1)
        let kicker: String = {
            if text != nil { return link ? tr("Incoming link") : tr("Incoming text") }
            if camera { return tr("Live camera") }
            if switching { return tr("Switch phones") }
            if contact { return cardCount == 1 ? tr("Incoming contact") : tr("Incoming contacts") }
            return tr("Incoming files")
        }()
        let headline: String = {
            if text != nil { return link ? tr("{0} sent you a link", offer.peerName) : tr("{0} sent you some text", offer.peerName) }
            if camera { return tr("{0} wants to share their camera", offer.peerName) }
            if switching { return tr("{0} wants to move to this {1}", offer.peerName, Platform.deviceNoun) }
            if contact { return trPlural(cardCount, one: "{0} sent you a contact", other: "{0} sent you {n} contacts", offer.peerName) }
            return trPlural(files.count, one: "{0} wants to send you a file", other: "{0} wants to send you {n} files", offer.peerName)
        }()
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(centerY: 215, radius: 275)
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 22) {
                        HStack(spacing: 8) {
                            Dot(color: CD.accent)
                            Text(kicker.caps).font(.cd(12, weight: .semibold)).tracking(CDFont.tracking(12, em: 0.06))
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
                            Text(headline)
                                .displayStyle(28, tracking: -0.035).multilineTextAlignment(.center).lineLimit(3)
                                .accessibilityAddTraits(.isHeader)
                            TimelineView(.periodic(from: offer.receivedAt, by: 1)) { ctx in
                                let left = max(0, Int(ceil(timeout - ctx.date.timeIntervalSince(offer.receivedAt))))
                                countdownLabel(lead: text == nil && !camera && !contact ? "\(Fmt.bytes(total)) · " : "", left: left)
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
                                Text(tr("Every photo and video they take shows up here the moment it's taken, without asking again. Closing the viewer ends it."))
                                    .body(14, CD.textSecondary).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        } else if switching {
                            SwitchSummary(offer: offer.offer)
                        } else if contact && !cards.isEmpty {
                            ContactCardsView(cards: cards)
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
                                Text(tr("+ {0} more", files.count - 4)).body(13, CD.textDim)
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
                            PillButton(tr("Dismiss"), style: .outline, height: 62, fontSize: 16) { onRespond(false) }
                            PillButton(tr("Copy"), style: link ? .glass : .accent, height: 62, icon: CdIcons.copy, fontSize: 16, glow: !link) {
                                UIPasteboard.general.string = text
                                onRespond(true)
                            }
                            if link {
                                PillButton(tr("Open"), style: .accent, height: 62, icon: CdIcons.globe, fontSize: 16, glow: true) {
                                    onRespond(true)
                                    if let u = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) { UIApplication.shared.open(u) }
                                }
                            }
                        } else {
                            PillButton(tr("Decline"), style: .outline, height: 62, fontSize: 16) { onRespond(false) }
                                .frame(width: w * 0.4)
                            PillButton(camera ? tr("Watch") : switching ? tr("Start moving") : contact ? tr("Add to contacts") : tr("Accept"),
                                       style: .accent, height: 62, icon: contact ? CdIcons.contacts : CdIcons.check,
                                       fontSize: 16, glow: true) { onRespond(true) }
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

/// "auto-declines in 12 s" with the seconds picked out in mono accent. The translated sentence ("auto-declines in
/// {0}", same key as Android) is split at its placeholder so word order stays the translator's call; `lead` is a
/// language-neutral prefix ("4.2 MB · ").
private func countdownLabel(lead: String, left: Int) -> Text {
    let marker = "\u{1}"
    let parts = tr("auto-declines in {0}", marker).components(separatedBy: marker)
    let head = Text(lead + parts[0]).foregroundColor(CD.textDim).font(.cd(14))
    guard parts.count > 1 else { return head }
    return head
        + Text("\(left)").font(.mono(14)).foregroundColor(CD.accent)
        + Text(" s").font(.cd(14)).foregroundColor(CD.accent)
        + Text(parts.dropFirst().joined(separator: "")).font(.cd(14)).foregroundColor(CD.textDim)
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

/// Who the cards are: initials, name and the first number or address, up to four of them.
private struct ContactCardsView: View {
    let cards: [ContactPreview]
    var body: some View {
        GlassCard(radius: 28, fill: CD.glass, border: CD.hairline, padding: .all(6)) {
            ForEach(Array(cards.prefix(4).enumerated()), id: \.offset) { i, c in
                if i > 0 { RowSeparator() }
                HStack(spacing: 12) {
                    ContactInitials(name: c.name)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(c.name).body(15, weight: .semibold).lineLimit(1)
                        if let d = c.detail { Text(d).monoStyle(12, color: CD.textDim).lineLimit(1) }
                    }
                    Spacer(minLength: 0)
                }
                .padding(10)
                .accessibilityElement(children: .combine)
            }
            if cards.count > 4 {
                RowSeparator()
                Text(tr("+ {0} more", cards.count - 4)).body(13, CD.textDim)
                    .padding(.horizontal, 10).padding(.vertical, 12)
            }
            Text(tr("Goes straight into your contacts. Anyone who's already there is skipped."))
                .body(12, CD.textDim).padding(.horizontal, 10).padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Round initials badge for a contact without a photo.
struct ContactInitials: View {
    let name: String
    var size: CGFloat = 42
    var body: some View {
        let initials = name.split(whereSeparator: { $0 == " " || $0 == "-" }).prefix(2)
            .compactMap { $0.first.map { String($0).uppercased() } }.joined()
        Text(initials.isEmpty ? "?" : initials)
            .font(.cd(size * 0.36, weight: .semibold)).foregroundColor(CD.accent)
            .frame(width: size, height: size)
            .background(Circle().fill(CD.accent.opacity(0.14)))
            .overlay(Circle().strokeBorder(CD.accent.opacity(0.3), lineWidth: 1))
            .accessibilityHidden(true)
    }
}

/// What a phone move brings, from the batch totals.
private struct SwitchSummary: View {
    let offer: Offer
    var body: some View {
        let b = offer.batch
        let rows: [(CdIcon, String, Int)] = [
            (CdIcons.photo, tr("Photos"), b?.photos ?? offer.files.filter { $0.cat == ItemCategory.photo }.count),
            (CdIcons.video, tr("Videos"), b?.videos ?? offer.files.filter { $0.cat == ItemCategory.video }.count),
            (CdIcons.contacts, tr("Contacts"), b?.contacts ?? 0),
            (CdIcons.doc, tr("Files"), b?.files ?? 0),
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
            Text(tr("Photos and videos go to your library with their original dates, contacts to your address book. Keep both devices awake and on this Wi-Fi."))
                .body(12, CD.textDim).padding(.horizontal, 12).padding(.vertical, 10)
        }
    }
}
