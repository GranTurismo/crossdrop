import SwiftUI

/// The ambient sonar: concentric ripples, a rotating sweep and orbiting peer nodes. A peer's angle
/// is a stable hash of its instance id so nodes never teleport between redraws.
struct RadarCanvas: View {
    let selfName: String
    let peers: [Peer]
    let onPeerTap: (Peer) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            // 30 fps is plenty for slow ambient motion and halves the battery cost; frozen for Reduce Motion.
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate
                Canvas { gc, size in
                    draw(&gc, size: size, t: t)
                }
            }
            .contentShape(Rectangle())
            .gesture(SpatialTapGesture().onEnded { tap in
                let t = Date().timeIntervalSinceReferenceDate
                let pos = positions(size: geo.size, t: t)
                let hit = pos.min(by: { dist($0.value, tap.location) < dist($1.value, tap.location) })
                if let hit, dist(hit.value, tap.location) < 56, let peer = peers.first(where: { $0.instanceId == hit.key }) {
                    onPeerTap(peer)
                }
            })
        }
        // The canvas is a picture to VoiceOver — expose each device as a real button instead.
        .accessibilityRepresentation {
            VStack {
                ForEach(peers) { peer in
                    Button(peer.vault != nil ? "\(peer.name), shared drive" : "Send to \(peer.name)") { onPeerTap(peer) }
                        .disabled(!peer.isReachable)
                }
            }
        }
    }

    // MARK: time bases

    private struct Phases {
        let ripple: Double   // 0…1
        let sweepDeg: Double
        let bob: Double      // 0…2π
        let ambient: Double  // 0…2π
        init(_ t: Double) {
            func frac(_ period: Double) -> Double { (t / period).truncatingRemainder(dividingBy: 1) }
            ripple = frac(4.2); sweepDeg = frac(6) * 360
            bob = frac(2.6) * 2 * .pi; ambient = frac(9) * 2 * .pi
        }
    }

    private func dist(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }

    private func positions(size: CGSize, t: Double) -> [String: CGPoint] {
        let ph = Phases(t)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let maxR = min(size.width, size.height) / 2 * 0.86
        var out: [String: CGPoint] = [:]
        for (id, slot) in layout() {
            let bob = reduceMotion ? 0 : sin(ph.bob + Double(abs(Self.hash(id)) % 7)) * 6
            let r = maxR * slot.ring + bob
            out[id] = CGPoint(x: center.x + r * cos(slot.angle), y: center.y + r * sin(slot.angle))
        }
        return out
    }

    /// Stable hash-based angles, nudged apart so neighbours don't sit on top of each other; with many
    /// peers they alternate between two rings. Peers joining or leaving only shift their neighbours slightly.
    private func layout() -> [String: (angle: Double, ring: CGFloat)] {
        let n = peers.count
        guard n > 0 else { return [:] }
        let sorted = peers.sorted {
            let a = Self.angle(for: $0.instanceId), b = Self.angle(for: $1.instanceId)
            return a == b ? $0.instanceId < $1.instanceId : a < b
        }
        var angles = sorted.map { Self.angle(for: $0.instanceId) }
        if n > 1 {
            let gap = min(0.85, 2 * Double.pi / Double(n))
            for _ in 0..<12 {
                for i in 0..<n {
                    let j = (i + 1) % n
                    var d = angles[j] - angles[i]
                    if j == 0 { d += 2 * .pi }
                    if d < gap { let push = (gap - d) / 2; angles[i] -= push; angles[j] += push }
                }
            }
        }
        var out: [String: (angle: Double, ring: CGFloat)] = [:]
        for (i, p) in sorted.enumerated() {
            let ring: CGFloat = n <= 5 ? 0.55 : (i % 2 == 0 ? 0.43 : 0.7)
            out[p.instanceId] = (angles[i], ring)
        }
        return out
    }

    // MARK: drawing

    private func draw(_ gc: inout GraphicsContext, size: CGSize, t: Double) {
        let ph = Phases(t)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let maxR = min(size.width, size.height) / 2 * 0.86

        // background
        gc.fill(Path(CGRect(origin: .zero, size: size)),
                with: .radialGradient(Gradient(colors: [Color(hex: 0x0C0628), Aurora.deepSpace]),
                                      center: center, startRadius: 0, endRadius: max(size.width, size.height) / 1.6))
        drawAmbient(&gc, size: size, center: center, maxR: maxR, tA: ph.ambient)
        drawGrid(&gc, center: center, maxR: maxR)
        drawSweep(&gc, center: center, maxR: maxR, deg: ph.sweepDeg)
        drawRipples(&gc, center: center, maxR: maxR, t: ph.ripple)

        let pos = positions(size: size, t: t)
        for peer in peers {
            guard let p = pos[peer.instanceId] else { continue }
            if peer.vault != nil { drawVaultBeacon(&gc, at: p, peer: peer, t: ph.bob, orbit: ph.ambient) }
            else { drawPeerNode(&gc, at: p, peer: peer, t: ph.bob) }
        }
        drawSelfNode(&gc, center: center, name: selfName, t: ph.ripple)
    }

    private func drawAmbient(_ gc: inout GraphicsContext, size: CGSize, center: CGPoint, maxR: CGFloat, tA: Double) {
        let blobs: [(Color, CGPoint, CGFloat)] = [
            (Aurora.violet, CGPoint(x: -0.9, y: -0.5), 1.1), (Aurora.cyan, CGPoint(x: 0.8, y: -0.9), 0.9),
            (Aurora.teal, CGPoint(x: 0.4, y: 0.95), 1.0), (Aurora.pink, CGPoint(x: -0.75, y: 0.6), 0.7),
        ]
        for (i, b) in blobs.enumerated() {
            let dx = sin(tA * 0.4 + Double(i) * 2.1) * 0.08, dy = cos(tA * 0.3 + Double(i)) * 0.06
            let pos = CGPoint(x: center.x + (b.1.x + dx) * maxR, y: center.y + (b.1.y + dy) * maxR)
            let r = maxR * b.2
            gc.fill(Path(ellipseIn: CGRect(x: pos.x - r, y: pos.y - r, width: r * 2, height: r * 2)),
                    with: .radialGradient(Gradient(colors: [b.0.opacity(0.075), .clear]), center: pos, startRadius: 0, endRadius: r))
        }
        for i in 0..<90 {
            let h = (UInt64(i) &* 2654435761) ^ 0x9E3779B9
            let px = Double((h >> 33) % 1000) / 1000, py = Double((h >> 11) % 1023) / 1023
            let tw = 0.5 + 0.5 * sin(tA * (0.8 + Double(i % 7) * 0.22) + Double(i) * 1.7)
            let r = 0.8 + Double(i % 3) * 0.5
            gc.fill(Path(ellipseIn: CGRect(x: px * size.width - r, y: py * size.height - r, width: r * 2, height: r * 2)),
                    with: .color(.white.opacity(0.05 + 0.10 * tw)))
        }
    }

    private func drawGrid(_ gc: inout GraphicsContext, center: CGPoint, maxR: CGFloat) {
        for i in 1...4 {
            let r = maxR * CGFloat(i) / 4
            gc.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                      with: .color(Aurora.teal.opacity(0.10)), lineWidth: 1.2)
        }
        var cross = Path()
        cross.move(to: CGPoint(x: center.x - maxR, y: center.y)); cross.addLine(to: CGPoint(x: center.x + maxR, y: center.y))
        cross.move(to: CGPoint(x: center.x, y: center.y - maxR)); cross.addLine(to: CGPoint(x: center.x, y: center.y + maxR))
        gc.stroke(cross, with: .color(Aurora.teal.opacity(0.05)), lineWidth: 1)
    }

    private func drawSweep(_ gc: inout GraphicsContext, center: CGPoint, maxR: CGFloat, deg: Double) {
        gc.drawLayer { layer in
            layer.translateBy(x: center.x, y: center.y)
            layer.rotate(by: .degrees(deg))
            var wedge = Path()
            wedge.move(to: .zero)
            wedge.addArc(center: .zero, radius: maxR, startAngle: .degrees(0), endAngle: .degrees(60), clockwise: false)
            wedge.closeSubpath()
            layer.clip(to: wedge)
            let g = Gradient(stops: [
                .init(color: Aurora.teal.opacity(0.30), location: 0), .init(color: Aurora.cyan.opacity(0.16), location: 0.07),
                .init(color: Aurora.violet.opacity(0.07), location: 0.13), .init(color: .clear, location: 0.18),
                .init(color: .clear, location: 1),
            ])
            layer.fill(Path(ellipseIn: CGRect(x: -maxR, y: -maxR, width: maxR * 2, height: maxR * 2)),
                       with: .conicGradient(g, center: .zero, angle: .zero))
        }
    }

    private func drawRipples(_ gc: inout GraphicsContext, center: CGPoint, maxR: CGFloat, t: Double) {
        for i in 0..<4 {
            let phase = (t + Double(i) / 4).truncatingRemainder(dividingBy: 1)
            let r = CGFloat(phase) * maxR
            gc.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                      with: .color(Aurora.teal.opacity((1 - phase) * 0.35)), lineWidth: max(0.6, 3 - phase * 2))
        }
    }

    private func pill(_ gc: inout GraphicsContext, text: String, color: Color, at topCenter: CGPoint, edge: Color, fontSize: CGFloat = 12, weight: Font.Weight = .medium) {
        let resolved = gc.resolve(Text(text).font(.system(size: fontSize, weight: weight)).foregroundColor(color))
        let m = resolved.measure(in: CGSize(width: 120, height: 40))
        let padH: CGFloat = 10, padV: CGFloat = 4
        let rect = CGRect(x: topCenter.x - (m.width + padH * 2) / 2, y: topCenter.y, width: m.width + padH * 2, height: m.height + padV * 2)
        let rr = Path(roundedRect: rect, cornerRadius: rect.height / 2)
        gc.fill(rr, with: .color(Aurora.deepSpaceSoft.opacity(0.85)))
        gc.stroke(rr, with: .color(edge), lineWidth: 1)
        gc.draw(resolved, at: CGPoint(x: rect.minX + padH, y: rect.minY + padV), anchor: .topLeading)
    }

    private func drawPeerNode(_ gc: inout GraphicsContext, at pos: CGPoint, peer: Peer, t: Double) {
        let reachable = peer.isReachable
        let alpha = reachable ? 1.0 : 0.45
        let nodeR: CGFloat = 30
        let pulse = 1 + 0.08 * sin(t + Double(abs(Self.hash(peer.instanceId)) % 11))
        let haloR = nodeR * 2 * pulse
        gc.fill(Path(ellipseIn: CGRect(x: pos.x - haloR, y: pos.y - haloR, width: haloR * 2, height: haloR * 2)),
                with: .radialGradient(Gradient(colors: [(reachable ? Aurora.teal : Aurora.cyan).opacity(0.40 * alpha), .clear]),
                                      center: pos, startRadius: 0, endRadius: haloR))
        let body = nodeR * 0.62
        gc.fill(Path(ellipseIn: CGRect(x: pos.x - body, y: pos.y - body, width: body * 2, height: body * 2)),
                with: .radialGradient(Gradient(colors: [Aurora.phosphor.opacity(alpha), Aurora.teal.opacity(0.85 * alpha)]),
                                      center: CGPoint(x: pos.x, y: pos.y - nodeR * 0.3), startRadius: 0, endRadius: nodeR))
        if reachable {
            let ring = nodeR * 0.8
            gc.stroke(Path(ellipseIn: CGRect(x: pos.x - ring, y: pos.y - ring, width: ring * 2, height: ring * 2)),
                      with: .color(Aurora.teal.opacity(0.6)), lineWidth: 1.4)
        }
        pill(&gc, text: peer.name, color: Aurora.textPrimary.opacity(alpha), at: CGPoint(x: pos.x, y: pos.y + nodeR * 0.85), edge: Aurora.glassEdge)
    }

    private func drawVaultBeacon(_ gc: inout GraphicsContext, at pos: CGPoint, peer: Peer, t: Double, orbit: Double) {
        guard let vault = peer.vault else { return }
        let nodeR: CGFloat = 30, tint = Aurora.violet
        let pulse = 1 + 0.10 * sin(t + Double(abs(Self.hash(peer.instanceId)) % 11))
        let haloR = nodeR * 2.2 * pulse
        gc.fill(Path(ellipseIn: CGRect(x: pos.x - haloR, y: pos.y - haloR, width: haloR * 2, height: haloR * 2)),
                with: .radialGradient(Gradient(colors: [tint.opacity(0.45), .clear]), center: pos, startRadius: 0, endRadius: haloR))
        let core = nodeR * 0.58
        gc.fill(Path(ellipseIn: CGRect(x: pos.x - core, y: pos.y - core, width: core * 2, height: core * 2)),
                with: .radialGradient(Gradient(colors: [Aurora.phosphor, tint.opacity(0.85)]),
                                      center: CGPoint(x: pos.x, y: pos.y - nodeR * 0.3), startRadius: 0, endRadius: nodeR))
        let ringR = nodeR * 1.02
        gc.drawLayer { layer in
            layer.translateBy(x: pos.x, y: pos.y)
            layer.rotate(by: .radians(orbit))
            for i in 0..<12 {
                let c = i % 3 == 0 ? Aurora.pink : tint
                var arc = Path()
                arc.addArc(center: .zero, radius: ringR, startAngle: .degrees(Double(i) * 30), endAngle: .degrees(Double(i) * 30 + 16), clockwise: false)
                layer.stroke(arc, with: .color(c.opacity(i % 2 == 0 ? 0.9 : 0.42)), lineWidth: 2)
            }
        }
        let badge = vault.writable ? "RW" : "RO"
        let resolved = gc.resolve(Text(badge).font(.system(size: 9, weight: .bold)).foregroundColor(vault.writable ? Aurora.phosphor : tint))
        let m = resolved.measure(in: CGSize(width: 60, height: 20))
        let rect = CGRect(x: pos.x - (m.width + 12) / 2, y: pos.y - nodeR * 1.35 - (m.height + 4), width: m.width + 12, height: m.height + 4)
        let rr = Path(roundedRect: rect, cornerRadius: rect.height / 2)
        gc.fill(rr, with: .color(Aurora.deepSpaceSoft.opacity(0.9)))
        gc.stroke(rr, with: .color(tint.opacity(0.7)), lineWidth: 1)
        gc.draw(resolved, at: CGPoint(x: rect.minX + 6, y: rect.minY + 2), anchor: .topLeading)
        pill(&gc, text: vault.vaultName, color: Aurora.textPrimary, at: CGPoint(x: pos.x, y: pos.y + nodeR * 0.85), edge: tint.opacity(0.6))
    }

    private func drawSelfNode(_ gc: inout GraphicsContext, center: CGPoint, name: String, t: Double) {
        let phase = t.truncatingRemainder(dividingBy: 1)
        let hb = 14 + CGFloat(phase) * 34
        gc.stroke(Path(ellipseIn: CGRect(x: center.x - hb, y: center.y - hb, width: hb * 2, height: hb * 2)),
                  with: .color(Aurora.teal.opacity((1 - phase) * 0.30)), lineWidth: 1.2 + (1 - phase) * 1.4)
        gc.fill(Path(ellipseIn: CGRect(x: center.x - 44, y: center.y - 44, width: 88, height: 88)),
                with: .radialGradient(Gradient(colors: [Aurora.teal.opacity(0.5), .clear]), center: center, startRadius: 0, endRadius: 44))
        gc.fill(Path(ellipseIn: CGRect(x: center.x - 9, y: center.y - 9, width: 18, height: 18)), with: .color(Aurora.phosphor))
        gc.stroke(Path(ellipseIn: CGRect(x: center.x - 14, y: center.y - 14, width: 28, height: 28)), with: .color(Aurora.teal), lineWidth: 1.6)
        let label = gc.resolve(Text(name).font(.system(size: 11)).foregroundColor(Aurora.textDim))
        gc.draw(label, at: CGPoint(x: center.x, y: center.y + 18), anchor: .top)
    }

    // MARK: math

    static func hash(_ s: String) -> Int {
        var h = 7
        for u in s.unicodeScalars { h = h &* 31 &+ Int(u.value) }
        return h & 0x7fffffff
    }

    static func angle(for id: String) -> Double { Double(hash(id) % 360) * .pi / 180 }
}
