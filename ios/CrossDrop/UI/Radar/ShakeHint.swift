import SwiftUI

/// "Shake to connect" taught by showing it (the twin of Android `ui/radar/ShakeHint.kt`): two little phones shake, then
/// a mint pulse links them. Shown when several devices are on the radar and shaking hasn't found anyone here yet; a tap
/// dismisses it.
struct ShakeHintCard: View {
    let onDismiss: () -> Void

    var body: some View {
        GlassCard(radius: 24, fill: CD.accent.opacity(0.07), border: CD.accent.opacity(0.32),
                  glow: CD.accent.opacity(0.12), glowRadius: 30, padding: .hv(14, 12),
                  action: onDismiss, actionLabel: tr("Dismiss")) {
            HStack(spacing: 14) {
                ShakingPhones().frame(width: 78, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Shake to connect")).body(15, weight: .semibold)
                    Text(tr("Several devices? Shake both phones to find each other.")).body(12, CD.textDim)
                        .lineSpacing(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                CdIconView(CdIcons.close, color: CD.textDim, size: 16)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("{0}, {1}", tr("Shake to connect"), tr("Several devices? Shake both phones to find each other.")))
        .accessibilityAddTraits(.isButton)
    }
}

/// One 2.6 s loop: both phones shake hard and settle (0–40 %), a mint ring spreads from between them and a dot lights
/// up (40–80 %), then a short rest. Drawn into one canvas. With Reduce Motion it holds the linked state.
private struct ShakingPhones: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()
    static let loop: TimeInterval = 2.6

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            let t = reduceMotion ? 0.8 : timeline.date.timeIntervalSince(start).truncatingRemainder(dividingBy: Self.loop) / Self.loop
            Canvas { ctx, size in Self.draw(ctx, size, CGFloat(max(0, t))) }
        }
        .accessibilityHidden(true)
    }

    private static func draw(_ ctx: GraphicsContext, _ size: CGSize, _ t: CGFloat) {
        let shaking = t < 0.4
        let shake = min(max(t / 0.4, 0), 1)
        let decay = shaking ? 1 - shake * shake : 0
        let angle = shaking ? sin(shake * 2 * .pi * 4) * 16 * decay : 0
        let pulse = min(max((t - 0.4) / 0.4, 0), 1)
        let linked = t >= 0.4

        let phoneW = size.height * 0.46, phoneH = size.height * 0.8
        let left = CGPoint(x: size.width * 0.2, y: size.height / 2)
        let right = CGPoint(x: size.width * 0.8, y: size.height / 2)
        let mid = CGPoint(x: size.width / 2, y: size.height / 2)

        // The link: a ring that spreads and fades, and a dot that stays lit until the loop rests.
        if linked {
            let r = size.height * (0.12 + 0.38 * pulse)
            ctx.stroke(circle(mid, r), with: .color(CD.accent.opacity(0.55 * (1 - pulse))), lineWidth: 1.6)
            let dot = min(pulse * 3, 1) * (t > 0.9 ? 1 - (t - 0.9) / 0.1 : 1)
            ctx.fill(circle(mid, size.height * 0.16), with: .color(CD.accent.opacity(0.25 * dot)))
            ctx.fill(circle(mid, size.height * 0.07), with: .color(CD.accent.opacity(dot)))
        }
        let color = linked ? CD.accent : CD.textPrimary
        phone(ctx, left, phoneW, phoneH, angle, color, motion: decay, side: -1)
        phone(ctx, right, phoneW, phoneH, -angle, color, motion: decay, side: 1)
    }

    /// A phone outline tilted by `angle` degrees, with motion lines on its outer side while it shakes.
    private static func phone(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat, _ angle: CGFloat,
                              _ color: Color, motion: CGFloat, side: CGFloat) {
        let stroke: CGFloat = 1.8
        var g = ctx
        g.translateBy(x: c.x, y: c.y)
        g.rotate(by: .degrees(Double(angle)))
        g.translateBy(x: -c.x, y: -c.y)
        g.stroke(Path(roundedRect: CGRect(x: c.x - w / 2, y: c.y - h / 2, width: w, height: h), cornerRadius: w * 0.24),
                 with: .color(color), lineWidth: stroke)
        g.stroke(line(CGPoint(x: c.x - w * 0.12, y: c.y + h * 0.36), CGPoint(x: c.x + w * 0.12, y: c.y + h * 0.36)),
                 with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
        guard motion > 0.05 else { return }
        for i in 0..<2 {
            let dx = side * (w / 2 + 4 + CGFloat(i) * 4)
            let half = h * (0.22 + 0.1 * CGFloat(i))
            ctx.stroke(line(CGPoint(x: c.x + dx, y: c.y - half), CGPoint(x: c.x + dx, y: c.y + half)),
                       with: .color(CD.accent.opacity(0.8 * motion)), style: StrokeStyle(lineWidth: stroke * 0.9, lineCap: .round))
        }
    }

    private static func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
    }

    private static func line(_ a: CGPoint, _ b: CGPoint) -> Path {
        var p = Path(); p.move(to: a); p.addLine(to: b); return p
    }
}
