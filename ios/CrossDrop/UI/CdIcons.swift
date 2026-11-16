import SwiftUI

/// One stroke icon: 24-unit viewBox, round caps/joins, built from the same SVG path data as the design
/// (and Android's `ui/CdIcons.kt`). No SF Symbols for these — the set has to match across platforms.
struct CdIcon: Equatable {
    enum Part: Equatable {
        case d(String)
        case rect(CGFloat, CGFloat, CGFloat, CGFloat, CGFloat)   // x y w h rx
        case circle(CGFloat, CGFloat, CGFloat)                   // cx cy r
    }

    let parts: [Part]
    var stroke: CGFloat = 1.9
    var filled = false

    init(_ parts: Part..., stroke: CGFloat = 1.9, filled: Bool = false) {
        self.parts = parts; self.stroke = stroke; self.filled = filled
    }

    /// The icon in viewBox units.
    var path: Path {
        var p = Path()
        for part in parts {
            switch part {
            case .d(let d): p.addPath(SVGPath.parse(d))
            case .rect(let x, let y, let w, let h, let r):
                p.addRoundedRect(in: CGRect(x: x, y: y, width: w, height: h), cornerSize: CGSize(width: r, height: r), style: .circular)
            case .circle(let cx, let cy, let r):
                p.addEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
            }
        }
        return p
    }
}

private func d(_ s: String) -> CdIcon.Part { .d(s) }
private let folder = "M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"

enum CdIcons {
    // navigation
    static let radar = CdIcon(.circle(12, 12, 9), .circle(12, 12, 4.5))
    static let transfers = CdIcon(d("M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3"))
    static let inbox = CdIcon(d("M3 13l3-8h12l3 8v6H3z"), d("M3 13h5l1 2h6l1-2h5"))
    static let sliders = CdIcon(d("M4 7h10M18 7h2M4 17h4M12 17h8"), .circle(16, 7, 2), .circle(10, 17, 2))

    // devices
    static let phone = CdIcon(.rect(7, 2.5, 10, 19, 2.5), d("M11 18.5h2"), stroke: 1.8)
    static let laptop = CdIcon(.rect(4, 5, 16, 11, 1.5), d("M2 19h20"), stroke: 1.8)
    static let tablet = CdIcon(.rect(4.5, 2.5, 15, 19, 2.5), d("M11 18.5h2"), stroke: 1.8)
    static let monitor = CdIcon(.rect(3, 4, 18, 12, 1.5), d("M9 20h6M12 16v4"), stroke: 1.8)
    static let drive = CdIcon(.rect(3, 13, 18, 7, 2), d("M5 13l2.5-8h9L19 13M7 16.5h.01M10 16.5h.01"))

    // radios
    static let wifi = CdIcon(d("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"))
    static let bluetooth = CdIcon(d("M7 7l10 10-5 4V3l5 4L7 17"), stroke: 2.1)

    // content types
    static let photo = CdIcon(.rect(3, 4, 18, 16, 3), .circle(9, 10, 1.8), d("M21 16l-5-5-9 9"), stroke: 1.8)
    static let doc = CdIcon(d("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"), d("M14 3v5h5M9 13h6M9 17h4"), stroke: 1.8)
    static let video = CdIcon(.rect(3, 6, 13, 12, 2), d("M16 10l5-3v10l-5-3"))
    static let archive = CdIcon(d(folder), d("M12 11v1M12 14v1"))
    static let folderIcon = CdIcon(d(folder), stroke: 1.7)
    static let music = CdIcon(d("M9 18V5l11-2v13"), .circle(6, 18, 3), .circle(17, 16, 3))
    static let apk = CdIcon(.rect(5, 9, 14, 11, 2.5), d("M8 9a4 4 0 0 1 8 0M9 5L7.5 3M15 5l1.5-2M10 14.5h.01M14 14.5h.01"))
    static let file = CdIcon(d("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"), d("M14 3v5h5"), stroke: 1.8)

    // actions & glyphs
    static let qr = CdIcon(.rect(3, 3, 7, 7, 1), .rect(14, 3, 7, 7, 1), .rect(3, 14, 7, 7, 1),
                           d("M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3"), stroke: 1.8)
    static let arrowRight = CdIcon(d("M5 12h14M13 6l6 6-6 6"), stroke: 2.2)
    static let arrowUp = CdIcon(d("M12 19V5M6 11l6-6 6 6"), stroke: 2.2)
    static let arrowDown = CdIcon(d("M12 5v14M6 13l6 6 6-6"), stroke: 2.2)
    static let chevronLeft = CdIcon(d("M15 6l-6 6 6 6"), stroke: 2)
    static let chevronRight = CdIcon(d("M9 6l6 6-6 6"), stroke: 2.2)
    static let check = CdIcon(d("M5 12.5l4.5 4.5L19 7"), stroke: 2.4)
    static let close = CdIcon(d("M6 6l12 12M18 6L6 18"), stroke: 2.2)
    static let lock = CdIcon(.rect(5, 11, 14, 10, 2), d("M8 11V8a4 4 0 0 1 8 0v3"), stroke: 2.2)
    static let shield = CdIcon(d("M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"), d("M8.5 12l2.5 2.5 4.5-5"))
    static let trash = CdIcon(d("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"), stroke: 2)
    static let pencil = CdIcon(d("M4 20h4L19 9l-4-4L4 16z"), d("M13.5 6.5l4 4"))
    static let copy = CdIcon(.rect(8, 8, 12, 12, 2.5), d("M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"), stroke: 2.2)
    static let help = CdIcon(.circle(12, 12, 9), d("M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.4M12 17h.01"))
    static let globe = CdIcon(.circle(12, 12, 9), d("M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z"))
    static let download = CdIcon(d("M12 4v12M6 11l6 6 6-6M5 20h14"))
    static let upload = CdIcon(d("M12 20V8M6 13l6-6 6 6M5 4h14"))
    static let play = CdIcon(d("M8 5.5v13l10.5-6.5z"), filled: true)
    static let bolt = CdIcon(d("M13 3L5 13.5h6L10 21l8-10.5h-6z"))
    static let wrench = CdIcon(d("M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.5 2.5-2.5-.5-.5-2.5z"))
}

/// Draws a `CdIcon` scaled from its 24-unit viewBox to `size`. Decorative unless `label` is set.
struct CdIconView: View {
    let icon: CdIcon
    var color: Color = CD.textPrimary
    var size: CGFloat = 22
    var label: String?

    init(_ icon: CdIcon, color: Color = CD.textPrimary, size: CGFloat = 22, label: String? = nil) {
        self.icon = icon; self.color = color; self.size = size; self.label = label
    }

    var body: some View {
        let shape = IconShape(icon: icon)
        Group {
            if icon.filled {
                shape.fill(color)
            } else {
                shape.stroke(color, style: StrokeStyle(lineWidth: icon.stroke * size / 24, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(label == nil)
        .accessibilityLabel(label ?? "")
    }
}

private struct IconShape: Shape {
    let icon: CdIcon
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        return icon.path.applying(CGAffineTransform(translationX: rect.minX, y: rect.minY).scaledBy(x: s, y: s))
    }
}

// MARK: - SVG path data

/// Minimal SVG path-data parser: M/L/H/V/C/S/A/Z in absolute and relative form, implicit command
/// repetition and compact numbers ("-.6.3"). Enough for the icon set above.
enum SVGPath {
    private static var cache: [String: Path] = [:]
    private static let lock = NSLock()

    static func parse(_ d: String) -> Path {
        lock.lock(); defer { lock.unlock() }
        if let p = cache[d] { return p }
        var parser = Parser(Array(d.utf8))
        let p = parser.run()
        cache[d] = p
        return p
    }

    private struct Parser {
        let s: [UInt8]
        var i = 0
        var path = Path()
        var cur = CGPoint.zero
        var start = CGPoint.zero
        var lastCubicCtrl: CGPoint?

        init(_ s: [UInt8]) { self.s = s }

        mutating func skipSeparators() {
            while i < s.count, s[i] == 0x20 || s[i] == 0x2C || s[i] == 0x0A || s[i] == 0x09 || s[i] == 0x0D { i += 1 }
        }

        mutating func number() -> CGFloat? {
            skipSeparators()
            guard i < s.count else { return nil }
            let begin = i
            if s[i] == 0x2B || s[i] == 0x2D { i += 1 }                         // + -
            var sawDot = false, sawDigit = false
            while i < s.count {
                let c = s[i]
                if c >= 0x30 && c <= 0x39 { sawDigit = true; i += 1 }
                else if c == 0x2E && !sawDot { sawDot = true; i += 1 }
                else { break }
            }
            if sawDigit, i < s.count, s[i] == 0x65 || s[i] == 0x45 {              // exponent
                var j = i + 1
                if j < s.count, s[j] == 0x2B || s[j] == 0x2D { j += 1 }
                if j < s.count, s[j] >= 0x30 && s[j] <= 0x39 {
                    i = j
                    while i < s.count, s[i] >= 0x30 && s[i] <= 0x39 { i += 1 }
                }
            }
            guard sawDigit, let str = String(bytes: s[begin..<i], encoding: .ascii), let v = Double(str) else {
                i = begin; return nil
            }
            return CGFloat(v)
        }

        mutating func flag() -> Bool? {
            skipSeparators()
            guard i < s.count, s[i] == 0x30 || s[i] == 0x31 else { return nil }
            defer { i += 1 }
            return s[i] == 0x31
        }

        mutating func point(_ rel: Bool) -> CGPoint? {
            guard let x = number(), let y = number() else { return nil }
            return rel ? CGPoint(x: cur.x + x, y: cur.y + y) : CGPoint(x: x, y: y)
        }

        mutating func run() -> Path {
            var cmd: UInt8 = 0
            while true {
                skipSeparators()
                guard i < s.count else { break }
                let c = s[i]
                if (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) { cmd = c; i += 1 }
                else if cmd == 0 { break }
                let rel = cmd >= 0x61
                switch cmd | 0x20 {                                                   // lower-cased
                case 0x6D:                                                            // m
                    guard let p = point(rel) else { return path }
                    path.move(to: p); cur = p; start = p
                    cmd = rel ? 0x6C : 0x4C                                           // implicit l / L
                    lastCubicCtrl = nil
                case 0x6C:                                                            // l
                    guard let p = point(rel) else { return path }
                    path.addLine(to: p); cur = p; lastCubicCtrl = nil
                case 0x68:                                                            // h
                    guard let x = number() else { return path }
                    cur = CGPoint(x: rel ? cur.x + x : x, y: cur.y); path.addLine(to: cur); lastCubicCtrl = nil
                case 0x76:                                                            // v
                    guard let y = number() else { return path }
                    cur = CGPoint(x: cur.x, y: rel ? cur.y + y : y); path.addLine(to: cur); lastCubicCtrl = nil
                case 0x63:                                                            // c
                    guard let c1 = point(rel), let c2 = point(rel), let p = point(rel) else { return path }
                    path.addCurve(to: p, control1: c1, control2: c2); cur = p; lastCubicCtrl = c2
                case 0x73:                                                            // s
                    guard let c2 = point(rel), let p = point(rel) else { return path }
                    let c1 = lastCubicCtrl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                    path.addCurve(to: p, control1: c1, control2: c2); cur = p; lastCubicCtrl = c2
                case 0x61:                                                            // a
                    guard let rx = number(), let ry = number(), let rot = number(),
                          let large = flag(), let sweep = flag(), let p = point(rel) else { return path }
                    arc(rx: rx, ry: ry, phiDeg: rot, large: large, sweep: sweep, to: p)
                    cur = p; lastCubicCtrl = nil
                case 0x7A:                                                            // z
                    path.closeSubpath(); cur = start; lastCubicCtrl = nil
                    cmd = 0
                default:
                    return path
                }
            }
            return path
        }

        /// SVG endpoint arc → centre parameterisation (SVG 1.1 F.6.5), drawn on a transformed unit circle.
        mutating func arc(rx rx0: CGFloat, ry ry0: CGFloat, phiDeg: CGFloat, large: Bool, sweep: Bool, to p1: CGPoint) {
            let p0 = cur
            if p0 == p1 { return }
            var rx = abs(rx0), ry = abs(ry0)
            if rx == 0 || ry == 0 { path.addLine(to: p1); return }
            let phi = phiDeg * .pi / 180
            let cosP = cos(phi), sinP = sin(phi)
            let dx2 = (p0.x - p1.x) / 2, dy2 = (p0.y - p1.y) / 2
            let x1p = cosP * dx2 + sinP * dy2
            let y1p = -sinP * dx2 + cosP * dy2
            let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if lambda > 1 { let k = sqrt(lambda); rx *= k; ry *= k }
            let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            let coef = (large != sweep ? 1 : -1) * sqrt(max(0, num / den))
            let cxp = coef * rx * y1p / ry
            let cyp = coef * -ry * x1p / rx
            let cx = cosP * cxp - sinP * cyp + (p0.x + p1.x) / 2
            let cy = sinP * cxp + cosP * cyp + (p0.y + p1.y) / 2
            func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
                atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            }
            let ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry
            let vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry
            let theta1 = angle(1, 0, ux, uy)
            var dTheta = angle(ux, uy, vx, vy)
            if !sweep && dTheta > 0 { dTheta -= 2 * .pi }
            if sweep && dTheta < 0 { dTheta += 2 * .pi }
            let t = CGAffineTransform(translationX: cx, y: cy).rotated(by: phi).scaledBy(x: rx, y: ry)
            path.addRelativeArc(center: .zero, radius: 1, startAngle: .radians(theta1), delta: .radians(dTheta), transform: t)
        }
    }
}
