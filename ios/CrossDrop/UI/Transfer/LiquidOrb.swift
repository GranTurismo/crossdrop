import SwiftUI
import CoreMotion

/// Low-pass-filtered gravity → slosh input for the liquid orb. `x`/`y` roughly in [-1, 1].
@MainActor
final class TiltSource: ObservableObject {
    struct Tilt { var x: Double = 0, y: Double = 0, energy: Double = 0 }
    @Published private(set) var tilt = Tilt()

    private let motion = CMMotionManager()
    private var fx = 0.0, fy = 0.0, lastX = 0.0, lastY = 0.0, lastZ = 0.0, energy = 0.0

    func start() {
        guard motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        motion.accelerometerUpdateInterval = 1.0 / 30
        motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self, let a = data?.acceleration else { return }
            // iOS reports acceleration in g (Android: m/s²) — convert for identical tuning.
            let ax = a.x * 9.81, ay = a.y * 9.81, az = a.z * 9.81
            self.fx += 0.12 * (ax - self.fx); self.fy += 0.12 * (ay - self.fy)
            let jerk = abs(ax - self.lastX) + abs(ay - self.lastY) + abs(az - self.lastZ)
            self.lastX = ax; self.lastY = ay; self.lastZ = az
            self.energy = self.energy * 0.92 + jerk * 0.08
            // iOS axes are flipped vs Android (y up when upright, x right).
            self.tilt = Tilt(x: max(-1, min(1, self.fx / 9.81)),
                             y: max(-1, min(1, -self.fy / 9.81)),
                             energy: max(0, min(1, self.energy / 4)))
        }
    }

    func stop() {
        motion.stopAccelerometerUpdates()
        tilt = Tilt()
    }
}

/// The Dynamic Reactive Canvas: a liquid-glass orb replacing the progress bar.
/// `progress` sets the fluid level, `turbulence` drives wave amplitude + bubbles, `tilt` sloshes the
/// surface against gravity, and `completed` crystallises the rim.
struct LiquidOrb: View {
    let progress: Double
    let turbulence: Double
    let tilt: TiltSource.Tilt
    let completed: Bool
    var size: CGFloat = 260

    @State private var level = 0.0
    @State private var turb = 0.0
    @State private var flare = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Reduce Motion: no sloshing waves — the fluid level itself still rises with progress.
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            Canvas { gc, sz in draw(&gc, sz, t) }
        }
        .frame(width: size, height: size)
        .onAppear { sync() }
        .onChange(of: progress) { _ in sync() }
        .onChange(of: turbulence) { _ in sync() }
        .onChange(of: completed) { _ in sync() }
    }

    private func sync() {
        withAnimation(.interpolatingSpring(stiffness: 40, damping: 9)) { level = min(1, max(0, progress)) }
        withAnimation(.easeInOut(duration: 0.6)) { turb = completed ? 0 : min(1, max(0, turbulence)) }
        withAnimation(.easeInOut(duration: 0.9)) { flare = completed ? 1 : 0 }
    }

    private func draw(_ gc: inout GraphicsContext, _ sz: CGSize, _ time: Double) {
        let tA = (time / 3.0).truncatingRemainder(dividingBy: 1) * 2 * .pi
        let tB = (time / 4.7).truncatingRemainder(dividingBy: 1) * 2 * .pi
        let bubbleT = (time / 9.0).truncatingRemainder(dividingBy: 1)
        let center = CGPoint(x: sz.width / 2, y: sz.height / 2)
        let r = min(sz.width, sz.height) / 2 * 0.94

        if turb > 0.02 {
            gc.fill(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                    with: .radialGradient(Gradient(colors: [Aurora.teal.opacity(0.26 * turb), Aurora.violet.opacity(0.16 * turb), .clear]),
                                          center: center, startRadius: 0, endRadius: r))
        }
        let orb = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
        gc.fill(orb, with: .color(Color(hex: 0x0C0626)))
        gc.stroke(orb, with: .linearGradient(Gradient(colors: [Color(hex: 0xEAF9FF, alpha: 0.4), Color(hex: 0xEAF9FF, alpha: 0.1), Color(hex: 0xEAF9FF, alpha: 0.27)]),
                                              startPoint: CGPoint(x: center.x - r, y: center.y - r), endPoint: CGPoint(x: center.x + r, y: center.y + r)), lineWidth: 2.4)

        let slosh = atan2(-tilt.x, max(0.15, min(1, tilt.y))) * 0.85
        let amp = (r * 0.045) * (0.4 + turb * 1.8 + tilt.energy * 1.2)
        let surfaceY = center.y + r - CGFloat(level) * 2 * r

        gc.drawLayer { layer in
            let inner = r * 0.965
            layer.clip(to: Path(ellipseIn: CGRect(x: center.x - inner, y: center.y - inner, width: inner * 2, height: inner * 2)))
            if level > 0.005 {
                layer.drawLayer { rot in
                    rot.translateBy(x: center.x, y: center.y)
                    rot.rotate(by: .radians(slosh))
                    rot.translateBy(x: -center.x, y: -center.y)
                    wave(&rot, center, r, surfaceY + r * 0.03, amp * 0.7, tA + 1.7, tB, .color(Aurora.violet.opacity(0.42)))
                    wave(&rot, center, r, surfaceY, amp, tA, tB + 0.9,
                         .linearGradient(Gradient(colors: [Aurora.phosphor.opacity(0.92), Aurora.teal.opacity(0.95), Aurora.cyan.opacity(0.90), Aurora.violet.opacity(0.88), Color(hex: 0x241052)]),
                                         startPoint: CGPoint(x: center.x, y: surfaceY - amp * 2), endPoint: CGPoint(x: center.x, y: center.y + r)))
                }
            }
            if turb > 0.22 { bubbles(&layer, center, r, surfaceY, bubbleT) }
        }

        if flare > 0 {
            gc.stroke(orb, with: .color(Aurora.phosphor.opacity(0.9 * flare)), lineWidth: 3 + 8 * flare)
            let big = r * (1 + 0.12 * flare)
            gc.stroke(Path(ellipseIn: CGRect(x: center.x - big, y: center.y - big, width: big * 2, height: big * 2)),
                      with: .color(Aurora.phosphor.opacity(0.25 * flare)), lineWidth: 1.5)
        }
        if level > 0.005 && level < 0.995 {
            let pct = gc.resolve(Text("\(Int((level * 100).rounded()))%").font(.system(size: size * 0.17, weight: .light)).foregroundColor(Aurora.phosphor.opacity(0.9)))
            gc.draw(pct, at: center, anchor: .center)
        }
    }

    private func waveAt(_ x: CGFloat, _ cx: CGFloat, _ r: CGFloat, _ amp: CGFloat, _ tA: Double, _ tB: Double) -> CGFloat {
        let u = Double((x - cx) / r)
        return CGFloat(sin(u * 2.1 + tA) * 0.6 + sin(u * 3.7 - tB * 1.3) * 0.4) * amp
    }

    private func wave(_ gc: inout GraphicsContext, _ center: CGPoint, _ r: CGFloat, _ surfaceY: CGFloat, _ amp: CGFloat, _ tA: Double, _ tB: Double, _ shading: GraphicsContext.Shading) {
        var p = Path()
        let left = center.x - r * 1.15, right = center.x + r * 1.15
        p.move(to: CGPoint(x: left, y: surfaceY + waveAt(left, center.x, r, amp, tA, tB)))
        for i in 1...64 {
            let x = left + (right - left) * CGFloat(i) / 64
            p.addLine(to: CGPoint(x: x, y: surfaceY + waveAt(x, center.x, r, amp, tA, tB)))
        }
        p.addLine(to: CGPoint(x: right, y: center.y + r * 1.3))
        p.addLine(to: CGPoint(x: left, y: center.y + r * 1.3))
        p.closeSubpath()
        gc.fill(p, with: shading)
    }

    private func bubbles(_ gc: inout GraphicsContext, _ center: CGPoint, _ r: CGFloat, _ surfaceY: CGFloat, _ t: Double) {
        let count = Int(6 + turb * 14)
        for i in 0..<count {
            let seed = Double(i) * 137.5
            let x = center.x + CGFloat(cos(seed)) * r * 0.72 * CGFloat(seed.truncatingRemainder(dividingBy: 97) / 97)
            let span = (center.y + r * 0.95) - surfaceY
            if span <= 0 { continue }
            let cycle = (t * (1 + Double(i % 5) * 0.35) + Double(i) * 0.618).truncatingRemainder(dividingBy: 1)
            let y = center.y + r * 0.95 - CGFloat(cycle) * span
            let wobble = CGFloat(sin(cycle * 6.28 + Double(i))) * r * 0.02
            let br = (1.5 + CGFloat(i % 4)) * (size / 520)
            gc.fill(Path(ellipseIn: CGRect(x: x + wobble - br, y: y - br, width: br * 2, height: br * 2)),
                    with: .color(Aurora.phosphor.opacity((0.35 + 0.3 * turb) * sin(cycle * .pi))))
        }
    }
}
