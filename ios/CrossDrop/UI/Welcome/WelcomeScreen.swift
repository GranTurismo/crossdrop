import SwiftUI

/// First-run welcome: four short, animated pages (find → send → send to anyone → private) that show what the
/// app does before it asks for anything. Skippable from every page; the last page's button finishes it.
/// Mirrors `ui/welcome/WelcomeScreen.kt` on Android and desktop.
struct WelcomeScreen: View {
    var permissionNote: String?
    let onFinish: () -> Void

    @State private var page = 0
    @State private var pickingLanguage = false

    init(permissionNote: String? = nil, onFinish: @escaping () -> Void) {
        self.permissionNote = permissionNote
        self.onFinish = onFinish
        #if DEBUG
        _page = State(initialValue: min(max(UserDefaults.standard.integer(forKey: "dev_welcome_page"), 0), Self.pages.count - 1))
        #endif
    }

    /// A computed list, not a stored one: the strings must be looked up in the current language each time.
    private static var pages: [(title: String, body: String)] {
        [
            (tr("Your devices,\none radar."),
             tr("Open CrossDrop on two devices on the same Wi-Fi. They find each other on their own: no pairing, no setup.")),
            (tr("Tap a device.\nPick files."),
             tr("Photos, videos, any file or folder go straight to the other device at full Wi-Fi speed, never through a server.")),
            (tr("No app on the\nother side?"),
             tr("Tap “Send to anyone” and show the QR code. Any phone or computer opens the files in its browser. Nothing to install.")),
            (tr("Private by design."),
             tr("Transfers between CrossDrop devices are end-to-end encrypted. No account, no ads, no tracking, no cloud. Browser shares stay on your local network.")),
        ]
    }

    private var last: Bool { page == Self.pages.count - 1 }

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            RadialGradient(colors: [CD.accent.opacity(0.14), .clear], center: .top, startRadius: 0, endRadius: 420)
                .ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    ZStack {
                        Circle().strokeBorder(CD.accent, lineWidth: 2).frame(width: 24, height: 24)
                        Circle().fill(CD.accent).frame(width: 8, height: 8)
                    }
                    Text("CrossDrop").displayStyle(16, tracking: -0.01)
                    Spacer()
                    LanguageButton { pickingLanguage = true }
                    if !last {
                        ChipButton(text: tr("Skip"), label: tr("Skip the introduction"), action: onFinish)
                    }
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, 20)
                .frame(height: 60)

                TabView(selection: $page) {
                    ForEach(0..<Self.pages.count, id: \.self) { i in
                        WelcomePage(index: i, title: Self.pages[i].title, text: Self.pages[i].body,
                                    note: i == Self.pages.count - 1 ? permissionNote : nil)
                            .tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))

                VStack(spacing: 20) {
                    HStack(spacing: 7) {
                        ForEach(0..<Self.pages.count, id: \.self) { i in
                            Capsule()
                                .fill(i == page ? CD.accent : Color.white.opacity(0.2))
                                .frame(width: i == page ? 22 : 7, height: 7)
                        }
                    }
                    .animation(.easeOut(duration: 0.2), value: page)
                    .accessibilityHidden(true)
                    PillButton(last ? tr("Get started") : tr("Next"), glow: true) {
                        if last { onFinish() } else { withAnimation(.easeInOut(duration: 0.3)) { page += 1 } }
                    }
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, 24)
                .padding(.top, 6)
                .padding(.bottom, 22)
            }
        }
        .sheet(isPresented: $pickingLanguage) { LanguageSheet() }
    }
}

private struct WelcomePage: View {
    let index: Int
    let title: String
    let text: String
    let note: String?

    var body: some View {
        VStack(spacing: 0) {
            TimelineView(.animation) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 5) / 5
                switch index {
                case 0: RadarArt(t: t)
                case 1: SendArt(t: t)
                case 2: QrArt(t: t)
                default: PrivateArt(t: t)
                }
            }
            .frame(maxWidth: 520, maxHeight: 340)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 10) {
                Text(title).displayStyle(32, tracking: -0.03).lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text(text).body(15, CD.textDim).lineSpacing(5).fixedSize(horizontal: false, vertical: true)
                if let note { FootNote(note, icon: CdIcons.lock).padding(.top, 4) }
            }
            .frame(maxWidth: 520, alignment: .leading)
            .padding(.top, 12)
            .padding(.bottom, 8)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity)
    }
}

// MARK: 1 · radar

private struct Blip {
    let name: String
    let icon: CdIcon
    let tint: Color
    let angle: Double      // degrees, 0 = 3 o'clock, clockwise (y points down)
    let radius: CGFloat
}

/// Sample devices for the radar picture (a function: the names are looked up in the current language).
private func makeBlips() -> [Blip] {
    [
        Blip(name: tr("Mia’s phone"), icon: CdIcons.phone, tint: CD.accent, angle: -55, radius: 0.66),
        Blip(name: tr("Work laptop"), icon: CdIcons.laptop, tint: CD.sky, angle: 200, radius: 0.80),
        Blip(name: "iPad", icon: CdIcons.tablet, tint: CD.violet, angle: 85, radius: 0.56),
    ]
}

private struct RadarArt: View {
    let t: Double

    var body: some View {
        GeometryReader { geo in
            let c = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let r = min(geo.size.width, geo.size.height) / 2 * 0.92
            let blips = makeBlips()
            ZStack {
                Canvas { ctx, _ in
                    func circle(_ rad: CGFloat) -> Path {
                        Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: rad * 2, height: rad * 2))
                    }
                    ctx.fill(circle(r * 1.15), with: .radialGradient(
                        Gradient(colors: [CD.accent.opacity(0.18), .clear]), center: c, startRadius: 0, endRadius: r * 1.15))
                    for k in [0.33, 0.66, 1.0] as [CGFloat] {
                        ctx.stroke(circle(r * k), with: .color(CD.accent.opacity(0.2)), lineWidth: 1)
                    }
                    let ping = (t * 2).truncatingRemainder(dividingBy: 1)
                    ctx.stroke(circle(r * ping), with: .color(CD.accent.opacity(0.32 * (1 - ping))), lineWidth: 2)
                    ctx.drawLayer { layer in
                        layer.translateBy(x: c.x, y: c.y)
                        layer.rotate(by: .degrees(t * 360))
                        let sweep = Path(ellipseIn: CGRect(x: -r, y: -r, width: r * 2, height: r * 2))
                        layer.fill(sweep, with: .conicGradient(
                            Gradient(stops: [
                                .init(color: .clear, location: 0),
                                .init(color: .clear, location: 0.78),
                                .init(color: CD.accent.opacity(0.38), location: 1),
                            ]), center: .zero, angle: .zero))
                    }
                }
                Circle().fill(CD.accent.opacity(0.25)).frame(width: 18, height: 18)
                    .overlay(Circle().fill(CD.accent).frame(width: 8, height: 8))
                    .position(c)
                ForEach(0..<blips.count, id: \.self) { i in
                    let b = blips[i]
                    let rad = b.angle * .pi / 180
                    // Lit when the sweep passes (it starts at 3 o'clock, so angle/360 is its time), then dims.
                    let seen = ((b.angle / 360).truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
                    let age = ((t - seen).truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
                    VStack(spacing: 4) {
                        ZStack {
                            Circle().fill(b.tint.opacity(0.14))
                            Circle().strokeBorder(b.tint.opacity(0.5), lineWidth: 1)
                            CdIconView(b.icon, color: b.tint, size: 22)
                        }
                        .frame(width: 44, height: 44)
                        Text(b.name).body(11, CD.textSecondary).lineLimit(1)
                    }
                    .opacity(0.4 + 0.6 * (1 - age))
                    .position(x: c.x + r * b.radius * CGFloat(cos(rad)), y: c.y + r * b.radius * CGFloat(sin(rad)))
                }
            }
        }
    }
}

// MARK: 2 · send

private struct DeviceTile: View {
    let icon: CdIcon
    let label: String
    let tint: Color

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(CD.glass)
                RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CD.hairline, lineWidth: 1)
                CdIconView(icon, color: tint, size: 30)
            }
            .frame(width: 64, height: 64)
            Text(label).body(12, CD.textDim)
        }
    }
}

private struct SendArt: View {
    let t: Double

    private let flying: [(CdIcon, Color)] = [(CdIcons.photo, CD.sky), (CdIcons.video, CD.violet), (CdIcons.doc, CD.amber)]

    var body: some View {
        let progress = min(1, t / 0.85)
        let done = progress >= 1
        VStack(spacing: 26) {
            HStack(spacing: 0) {
                DeviceTile(icon: CdIcons.phone, label: tr("You"), tint: CD.accent)
                GeometryReader { geo in
                    ZStack(alignment: .topLeading) {
                        Path { p in
                            p.move(to: CGPoint(x: 0, y: 41)); p.addLine(to: CGPoint(x: geo.size.width, y: 41))
                        }
                        .stroke(CD.accent.opacity(0.35), style: StrokeStyle(lineWidth: 2, dash: [7, 7]))
                        if !done {
                            ForEach(0..<flying.count, id: \.self) { i in
                                let p = (t * 2 + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                                let item = flying[i]
                                ZStack {
                                    RoundedRectangle(cornerRadius: 11, style: .continuous).fill(item.1.opacity(0.18))
                                    RoundedRectangle(cornerRadius: 11, style: .continuous).strokeBorder(item.1.opacity(0.45), lineWidth: 1)
                                    CdIconView(item.0, color: item.1, size: 18)
                                }
                                .frame(width: 34, height: 34)
                                .opacity(max(0, min(1, sin(.pi * p))))
                                .offset(x: (geo.size.width - 34) * CGFloat(p), y: 24)
                            }
                        }
                    }
                }
                .padding(.horizontal, 6)
                .frame(height: 110)
                DeviceTile(icon: CdIcons.laptop, label: tr("Laptop"), tint: CD.sky)
            }
            .frame(height: 110)

            VStack(spacing: 10) {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.08))
                        Capsule().fill(CD.accent).frame(width: geo.size.width * CGFloat(progress))
                    }
                }
                .frame(height: 6)
                HStack(spacing: 5) {
                    if done { CdIconView(CdIcons.check, color: CD.accent, size: 14) }
                    Text(done ? tr("Delivered") : tr("Sending 3 files…"))
                        .body(13, done ? CD.accent : CD.textSecondary, weight: .semibold)
                    Spacer()
                    Text(tr("direct · encrypted")).monoStyle(11, color: CD.textDim)
                }
            }
            .frame(maxWidth: 380)
        }
        .frame(maxHeight: .infinity)
    }
}

// MARK: 3 · QR

private let qrN = 21

/// A made-up QR-looking grid (never a real code): three finder squares and seeded noise.
private let fakeQr: [[Bool]] = {
    var m = Array(repeating: Array(repeating: false, count: qrN), count: qrN)
    var s: UInt32 = 0x2545F491
    for y in 0..<qrN {
        for x in 0..<qrN {
            s = s &* 1103515245 &+ 12345
            m[y][x] = ((s >> 16) & 0xFF) < 124
        }
    }
    for (ox, oy) in [(0, 0), (qrN - 7, 0), (0, qrN - 7)] {
        for dy in -1...7 {
            for dx in -1...7 {
                let x = ox + dx, y = oy + dy
                guard x >= 0, x < qrN, y >= 0, y < qrN else { continue }
                m[y][x] = (0...6).contains(dx) && (0...6).contains(dy) &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || ((2...4).contains(dx) && (2...4).contains(dy)))
            }
        }
    }
    return m
}()

private struct QrArt: View {
    let t: Double

    var body: some View {
        GeometryReader { geo in
            let side = max(120, (min(geo.size.width, geo.size.height - 56)) * 0.92)
            VStack(spacing: 16) {
                Canvas { ctx, size in
                    let cell = size.width / CGFloat(qrN)
                    for y in 0..<qrN {
                        for x in 0..<qrN where fakeQr[y][x] {
                            ctx.fill(Path(CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell + 0.6, height: cell + 0.6)),
                                     with: .color(CD.ink))
                        }
                    }
                    // the scan line sweeps down and back
                    let ph = t < 0.5 ? t * 2 : (1 - t) * 2
                    let y = CGFloat(ph) * size.height
                    let band: CGFloat = 46
                    let scan = Color(hex: 0x14C79A)
                    ctx.fill(Path(CGRect(x: 0, y: y - band, width: size.width, height: band * 2)),
                             with: .linearGradient(Gradient(colors: [.clear, scan.opacity(0.35), .clear]),
                                                   startPoint: CGPoint(x: 0, y: y - band), endPoint: CGPoint(x: 0, y: y + band)))
                    ctx.fill(Path(CGRect(x: 0, y: y - 1.5, width: size.width, height: 3)), with: .color(scan))
                }
                .padding(side * 0.09)
                .frame(width: side, height: side)
                .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color(hex: 0xF4F7F9)))
                .shadow(color: CD.accent.opacity(0.22), radius: 22)
                GlassChip(text: tr("Example"))
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

// MARK: 4 · private

private struct PrivateArt: View {
    let t: Double

    var body: some View {
        GeometryReader { geo in
            // Translated chips can wrap to three or four rows. The disc takes what is left over; on a very short
            // screen it is dropped so the chips (the actual content) are never clipped.
            let compact = geo.size.height < 200
            let disc = min(168, max(56, geo.size.height - 100))
            VStack(spacing: 14) {
                if !compact { ZStack {
                    Canvas { ctx, size in
                        let c = CGPoint(x: size.width / 2, y: size.height / 2)
                        for i in 0..<3 {
                            let p = (t * 1.5 + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                            let rad = size.width / 2 * CGFloat(0.55 + 0.45 * p)
                            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: rad * 2, height: rad * 2)),
                                       with: .color(CD.accent.opacity(0.3 * (1 - p))), lineWidth: 1.5)
                        }
                    }
                    ZStack {
                        Circle().fill(CD.accent.opacity(0.14))
                        Circle().strokeBorder(CD.accent.opacity(0.5), lineWidth: 1)
                        CdIconView(CdIcons.shield, color: CD.accent, size: disc * 0.3)
                    }
                    .frame(width: disc * 0.56, height: disc * 0.56)
                }
                .frame(width: disc, height: disc) }
                WrapLayout(spacing: 8) {
                    GlassChip(text: tr("Encrypted between devices"), fg: CD.textPrimary)
                    GlassChip(text: tr("No account"), fg: CD.textPrimary)
                    GlassChip(text: tr("No ads"), fg: CD.textPrimary)
                    GlassChip(text: tr("No tracking"), fg: CD.textPrimary)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

/// Chips that wrap onto as many centred rows as the width needs (translated labels are often longer than English).
private struct WrapLayout: Layout {
    var spacing: CGFloat = 8

    private func rows(_ width: CGFloat, _ subviews: Subviews) -> [[Int]] {
        var rows: [[Int]] = [[]]
        var x: CGFloat = 0
        for (i, s) in subviews.enumerated() {
            let w = s.sizeThatFits(.unspecified).width
            if x > 0 && x + w > width { rows.append([]); x = 0 }
            rows[rows.count - 1].append(i)
            x += w + spacing
        }
        return rows
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = rows(width, subviews)
        let heights = rows.map { $0.map { subviews[$0].sizeThatFits(.unspecified).height }.max() ?? 0 }
        let widest = rows.map { r in r.reduce(CGFloat(0)) { $0 + subviews[$1].sizeThatFits(.unspecified).width } + spacing * CGFloat(max(0, r.count - 1)) }.max() ?? 0
        return CGSize(width: min(width, widest), height: heights.reduce(0, +) + spacing * CGFloat(max(0, rows.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(bounds.width, subviews) {
            let sizes = row.map { subviews[$0].sizeThatFits(.unspecified) }
            let total = sizes.reduce(CGFloat(0)) { $0 + $1.width } + spacing * CGFloat(max(0, row.count - 1))
            var x = bounds.minX + (bounds.width - total) / 2
            for (k, i) in row.enumerated() {
                subviews[i].place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(sizes[k]))
                x += sizes[k].width + spacing
            }
            y += (sizes.map(\.height).max() ?? 0) + spacing
        }
    }
}
