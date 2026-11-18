import SwiftUI

/// The sonar: three quiet rings, a rotating sweep and two accent pulses behind real, focusable device
/// nodes (buttons with VoiceOver labels, not canvas hit-testing). iOS has no BLE layer, so every peer
/// sits on the outer "Wi-Fi · no distance" ring with a dashed outline. A node's angle is a stable hash of
/// its instance id, nudged apart so neighbours never overlap.
struct RadarCanvas: View {
    let selfName: String
    let selfIcon: CdIcon
    let peers: [Peer]
    var active = true
    let onPeerTap: (Peer) -> Void
    var onSelfTap: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let nodeW: CGFloat = 112
    static let nodeD: CGFloat = 56
    static let nodeH: CGFloat = 98
    static let selfW: CGFloat = 140
    static let selfD: CGFloat = 76

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let maxR = max(70, min(w - 20, h - 8) / 2)
            let cx = w / 2, cy = h / 2
            let layout = Self.layout(peers)
            ZStack(alignment: .topLeading) {
                // Ambient glow centred on the radar — a view, not canvas paint, so it bleeds softly past the box.
                let gr = min(w, h) * 0.69
                RadialGradient(colors: [CD.accent.opacity(0.18), CD.accent.opacity(0)], center: .center, startRadius: 0, endRadius: gr)
                    .frame(width: gr * 2, height: gr * 2)
                    .offset(x: cx - gr, y: cy - gr)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                SonarBackdrop(maxR: maxR, paused: !active || reduceMotion)
                    .frame(width: w, height: h)

                ForEach(peers) { peer in
                    let slot = layout[peer.instanceId] ?? (angle: 0, ring: 0.8)
                    let r = maxR * slot.ring
                    let px = cx + r * cos(slot.angle), py = cy + r * sin(slot.angle)
                    // Keep the whole node (circle + two label lines) inside the box.
                    let left = min(max(0, px - Self.nodeW / 2), max(0, w - Self.nodeW))
                    let top = min(max(0, py - Self.nodeD / 2), max(0, h - Self.nodeH))
                    PeerNode(peer: peer) { onPeerTap(peer) }
                        .modifier(FloatBob(phase: Double(Self.hash(peer.instanceId) % 7), enabled: !reduceMotion))
                        .offset(x: left, y: top)
                }

                SelfNode(name: selfName, icon: selfIcon, onTap: onSelfTap)
                    .offset(x: cx - Self.selfW / 2, y: cy - Self.selfD / 2)
            }
        }
    }

    // MARK: layout

    static func hash(_ s: String) -> Int {
        var h = 7
        for u in s.utf16 { h = h &* 31 &+ Int(u) }
        return h & 0x7fffffff
    }

    static func angle(for id: String) -> Double { Double(hash(id) % 360) * .pi / 180 }

    /// Stable hash-based angles, nudged apart so neighbours don't sit on top of each other; with many
    /// peers they alternate between two rings. Peers joining or leaving only shift their neighbours slightly.
    static func layout(_ peers: [Peer]) -> [String: (angle: Double, ring: CGFloat)] {
        let n = peers.count
        guard n > 0 else { return [:] }
        let sorted = peers.sorted {
            let a = angle(for: $0.instanceId), b = angle(for: $1.instanceId)
            return a == b ? $0.instanceId < $1.instanceId : a < b
        }
        var angles = sorted.map { angle(for: $0.instanceId) }
        if n > 1 {
            let gap = min(0.95, 2 * Double.pi / Double(n))
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
            // No distance on iOS (no BLE): the outer ring, split in two when crowded.
            let ring: CGFloat = n <= 5 ? 0.80 : (i % 2 == 0 ? 0.62 : 0.90)
            out[p.instanceId] = (angles[i], ring)
        }
        return out
    }
}

/// Gentle 4 pt float, like the design's `cd-float`.
private struct FloatBob: ViewModifier {
    let phase: Double
    let enabled: Bool
    @State private var up = false
    func body(content: Content) -> some View {
        content
            .offset(y: up ? -4 : 0)
            .onAppear {
                guard enabled else { return }
                withAnimation(.easeInOut(duration: 2 + phase * 0.1).repeatForever(autoreverses: true).delay(phase * 0.25)) { up = true }
            }
    }
}

// MARK: - Backdrop

private struct SonarBackdrop: View {
    let maxR: CGFloat
    let paused: Bool

    var body: some View {
        // 30 fps is plenty for slow ambient motion; frozen for Reduce Motion or when the tab is hidden.
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            Canvas { gc, size in draw(&gc, size: size, t: paused ? 0.9 : t) }
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func draw(_ gc: inout GraphicsContext, size: CGSize, t: Double) {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let r = maxR
        func circle(_ rad: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: rad * 2, height: rad * 2)) }

        // sweep — a soft wedge with a crisp leading edge, rotating clockwise once every 5 s
        let sweepDeg = (t / 5).truncatingRemainder(dividingBy: 1) * 360
        let g = Gradient(stops: [
            .init(color: CD.accent.opacity(0), location: 0),
            .init(color: CD.accent.opacity(0.25), location: 70.0 / 360),
            .init(color: CD.accent.opacity(0), location: 70.5 / 360),
            .init(color: CD.accent.opacity(0), location: 1),
        ])
        gc.fill(circle(r), with: .conicGradient(g, center: c, angle: .degrees(sweepDeg)))

        // two expanding pulses, half a cycle apart, eased out
        for k in 0..<2 {
            let p = ((t / 3.2) + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
            let e = 1 - pow(1 - p, 2.4)
            gc.stroke(circle(r * CGFloat(0.35 + 0.65 * e)), with: .color(CD.accent.opacity(0.7 * (1 - p))), lineWidth: 1.5)
        }

        // static rings
        gc.stroke(circle(r * 0.35), with: .color(.white.opacity(0.12)), lineWidth: 1)
        gc.stroke(circle(r * 0.676), with: .color(.white.opacity(0.08)), lineWidth: 1)
        gc.stroke(circle(r), with: .color(.white.opacity(0.05)), lineWidth: 1)
    }
}

// MARK: - Nodes

private struct SelfNode: View {
    let name: String
    let icon: CdIcon
    let onTap: (() -> Void)?

    var body: some View {
        Button { onTap?() } label: {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(CD.accent.opacity(0.15)).frame(width: RadarCanvas.selfD + 16, height: RadarCanvas.selfD + 16)
                    Circle().fill(CD.accent).frame(width: RadarCanvas.selfD, height: RadarCanvas.selfD)
                        .shadow(color: CD.accent.opacity(0.5), radius: 24)
                    CdIconView(icon, color: CD.onAccent, size: 28)
                }
                .frame(width: RadarCanvas.selfD, height: RadarCanvas.selfD)
                Text(name).displayStyle(14, tracking: 0).lineLimit(1).truncationMode(.tail)
                    .padding(.top, 12)
                Text(onTap != nil ? tr("you · tap to rename") : tr("you")).body(11, CD.textDim)
                    .padding(.top, 4)
            }
            .frame(width: RadarCanvas.selfW)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .disabled(onTap == nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Your device, {0}", name))
        .accessibilityHint(onTap != nil ? tr("Double-tap to rename") : "")
        .accessibilityAddTraits(onTap != nil ? .isButton : [])
    }
}

private struct PeerNode: View {
    let peer: Peer
    let onTap: () -> Void

    var body: some View {
        let reachable = peer.isReachable
        let name = peer.vault?.vaultName ?? peer.name
        let icon = deviceIconFor(peer.name)
        Button(action: onTap) {
            VStack(spacing: 6) {
                CdIconView(icon, color: icon == CdIcons.wifi ? CD.textDim : CD.textPrimary, size: 24)
                    .frame(width: RadarCanvas.nodeD, height: RadarCanvas.nodeD)
                    .background(Circle().fill(Color.white.opacity(0.04)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.25), style: StrokeStyle(lineWidth: 1, dash: [3, 3])))
                Text(name).font(.cd(13, weight: .semibold)).foregroundColor(CD.textPrimary)
                    .lineLimit(1).truncationMode(.tail)
                Text(reachable ? "Wi-Fi" : tr("Connecting…")).font(.cd(11, weight: .semibold)).foregroundColor(CD.textDim)
                    .lineLimit(1)
            }
            .frame(width: RadarCanvas.nodeW)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .disabled(!reachable)
        .opacity(reachable ? 1 : 0.6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(reachable ? tr("Send to {0}, seen over Wi-Fi", name) : tr("Send to {0}, not reachable yet", name))
        .accessibilityAddTraits(.isButton)
        .pointerHover()
    }
}

/// Best-guess device glyph from the advertised name.
func deviceIconFor(_ name: String) -> CdIcon {
    let n = name.lowercased()
    func has(_ s: String) -> Bool { n.contains(s) }
    if n == Peer.unnamed.lowercased() || n.trimmingCharacters(in: .whitespaces).isEmpty { return CdIcons.wifi }
    if has("ipad") || has("tablet") || n.hasPrefix("galaxy tab") || " \(n) ".contains(" tab ") { return CdIcons.tablet }
    if has("macbook") || has("laptop") || has("notebook") || has("thinkpad") || has("chromebook") { return CdIcons.laptop }
    if has("imac") || has("desktop") || has("windows") || has("mac mini") || has("mac studio") || n == "mac"
        || n.range(of: "\\bpc\\b", options: .regularExpression) != nil { return CdIcons.monitor }
    return CdIcons.phone
}
