import SwiftUI
@preconcurrency import CrossDropCore

/// Full-screen live transfer: sender → receiver with flowing packets, a ring of progress, speed and
/// delta-savings tiles, and the 6-digit verification code both people compare. `onHide` minimises it to
/// the Transfers tab.
struct TransferScreen: View {
    let session: SessionState
    var selfName = "This device"
    let onCancel: () -> Void
    let onClose: () -> Void
    var onHide: (() -> Void)?
    var onOpenInbox: (() -> Void)?

    private var status: SessionStatus { session.status }
    private var completed: Bool { status == .completed }
    private var failed: Bool { status == .failed || status == .declined || status == .cancelled }
    private var sending: Bool { session.direction == .sending }

    var body: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: failed ? CD.dangerText : CD.accent, alpha: 0.14, centerY: 315, radius: 235)
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(spacing: 16) {
                        endpoints
                        ProgressRing(session: session, completed: completed, failed: failed)
                        stats
                        if let sas = session.sas { VerificationCode(sas: sas, peerName: session.peerName) }
                        if status == .failed, let err = session.error {
                            Text(err).body(13, CD.dangerText).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.top, 16)
                }
                actions.padding(.top, 16)
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 22)
        }
        .onChange(of: status) { s in
            if s == .completed { UINotificationFeedbackGenerator().notificationOccurred(.success) }
            else if s == .failed { UINotificationFeedbackGenerator().notificationOccurred(.error) }
        }
    }

    private var header: some View {
        HStack {
            if let onHide, !status.isTerminal {
                CircleButton(icon: CdIcons.chevronLeft, label: "Back to transfers", action: onHide)
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
            Spacer()
            StatusChip(session: session)
            Spacer()
            Color.clear.frame(width: 44, height: 44)
        }
    }

    private var endpoints: some View {
        let selfIcon = Platform.deviceIcon
        let peerIcon = deviceIconFor(session.peerName)
        return HStack(spacing: 0) {
            Endpoint(name: sending ? selfName : session.peerName, icon: sending ? selfIcon : peerIcon, highlighted: sending)
            PacketFlow(active: status == .transferring).padding(.bottom, 22)
            Endpoint(name: sending ? session.peerName : selfName, icon: sending ? peerIcon : selfIcon, highlighted: !sending)
        }
        .padding(.horizontal, 6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(sending ? "From \(selfName) to \(session.peerName)" : "From \(session.peerName) to \(selfName)")
    }

    private var stats: some View {
        HStack(spacing: 10) {
            let fileCount = session.offer?.files.count
            if session.bytesPerSec > 0 || fileCount == nil {
                StatTile(label: "Speed", value: session.bytesPerSec > 0 ? Fmt.rate(session.bytesPerSec) : "—")
            } else {
                StatTile(label: "Files", value: "\(fileCount ?? 0)")
            }
            if session.bytesSkipped > 0 {
                StatTile(label: "Already local", value: "Δ \(Fmt.bytes(session.bytesSkipped))", valueColor: CD.accent)
            } else {
                // Before the manifest arrives there is no total to show (bytesTotal is a 1-byte placeholder).
                StatTile(label: sending ? "Sent" : "Received",
                         value: session.offer == nil && session.bytesDone == 0 ? "—"
                             : "\(Fmt.bytes(session.bytesDone)) / \(Fmt.bytes(session.bytesTotal))")
            }
        }
    }

    @ViewBuilder private var actions: some View {
        HStack(spacing: 10) {
            if status.isTerminal && completed && !sending, let onOpenInbox {
                PillButton("Done", style: .glass, action: onClose)
                PillButton("Open inbox", icon: CdIcons.inbox, action: onOpenInbox)
            } else if status.isTerminal {
                PillButton("Done", style: completed ? .accent : .glass, action: onClose)
            } else {
                PillButton("Cancel transfer", style: .danger, action: onCancel)
                if let onHide { PillButton("Hide", style: .glass, action: onHide) }
            }
        }
    }
}

private struct StatusChip: View {
    let session: SessionState
    var body: some View {
        let (label, color): (String, Color) = {
            switch session.status {
            case .connecting: return ("Connecting", CD.accent)
            case .awaitingAccept: return ("Waiting for \(session.peerName)", CD.accent)
            case .transferring: return (session.direction == .sending ? "Sending" : "Receiving", CD.accent)
            case .completed: return ("Complete", CD.accent)
            case .failed: return ("Failed", CD.dangerText)
            case .declined: return ("Declined", CD.dangerText)
            case .cancelled: return ("Cancelled", CD.textSecondary)
            }
        }()
        HStack(spacing: 7) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).font(.cd(12, weight: .semibold)).foregroundColor(color).lineLimit(1)
        }
        .padding(.horizontal, 12).frame(height: 32)
        .background(Capsule().fill(color.opacity(0.14)))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

private struct Endpoint: View {
    let name: String
    let icon: CdIcon
    let highlighted: Bool
    var body: some View {
        VStack(spacing: 6) {
            CdIconView(icon, color: highlighted ? CD.onAccent : CD.textPrimary, size: 22)
                .frame(width: 48, height: 48)
                .background(Circle().fill(highlighted ? CD.accent : CD.glassRaised))
                .overlay { if !highlighted { Circle().strokeBorder(Color.white.opacity(0.14), lineWidth: 1) } }
            Text(name).body(12, CD.textSecondary).lineLimit(1).truncationMode(.tail)
        }
        .frame(width: 84)
    }
}

/// Dashed link with three glowing packets travelling sender → receiver.
private struct PacketFlow: View {
    let active: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !active || reduceMotion)) { tl in
            let t = (tl.date.timeIntervalSinceReferenceDate / 1.8).truncatingRemainder(dividingBy: 1)
            Canvas { gc, size in
                let y = size.height / 2
                var line = Path()
                line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                gc.stroke(line, with: .color(.white.opacity(0.18)), style: StrokeStyle(lineWidth: 2, dash: [4, 5]))
                guard active else { return }
                for k in 0..<3 {
                    let p = (t + Double(k) / 3).truncatingRemainder(dividingBy: 1)
                    let a = p < 0.15 ? p / 0.15 : p > 0.85 ? (1 - p) / 0.15 : 1
                    let c = CGPoint(x: CGFloat(p) * size.width, y: y)
                    gc.fill(Path(ellipseIn: CGRect(x: c.x - 8, y: c.y - 8, width: 16, height: 16)), with: .color(CD.accent.opacity(0.35 * a)))
                    gc.fill(Path(ellipseIn: CGRect(x: c.x - 4, y: c.y - 4, width: 8, height: 8)), with: .color(CD.accent.opacity(a)))
                }
            }
        }
        .frame(height: 16)
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }
}

private struct ProgressRing: View {
    let session: SessionState
    let completed: Bool
    let failed: Bool

    var body: some View {
        let shown = completed ? 1 : session.fraction
        let color = failed ? CD.dangerText : CD.accent
        GeometryReader { g in
            let d = min(216, g.size.width * 0.62)
            ZStack {
                ZStack {
                    // Glow: the same arc, blurred, behind the crisp one.
                    Circle().inset(by: 8).trim(from: 0, to: shown)
                        .stroke(color, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .blur(radius: 12)
                        .opacity(0.55)
                    Circle().inset(by: 8).stroke(Color.white.opacity(0.07), lineWidth: 12)
                    Circle().inset(by: 8).trim(from: 0, to: shown)
                        .stroke(color, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .animation(.easeOut(duration: 0.4), value: shown)
                center(d: d)
            }
            .frame(width: d, height: d)
            .frame(maxWidth: .infinity)
        }
        .frame(height: 216)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Transfer progress")
        .accessibilityValue(completed ? "Delivered" : "\(Int(shown * 100)) percent")
    }

    @ViewBuilder private func center(d: CGFloat) -> some View {
        if completed {
            VStack(spacing: 8) {
                CdIconView(CdIcons.check, color: CD.onAccent, size: 32)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(CD.accent).shadow(color: CD.accent.opacity(0.45), radius: 15))
                Text("Delivered").displayStyle(17)
            }
        } else {
            VStack(spacing: 4) {
                (Text("\(Int(session.fraction * 100))").displayStyle(60, tracking: -0.05)
                    + Text("%").font(.display(28)).foregroundColor(CD.textDim))
                Text(session.currentFileName ?? {
                    switch session.status {
                    case .connecting: return "Securing the channel…"
                    case .awaitingAccept: return "Waiting for them to accept"
                    default: return ""
                    }
                }())
                .body(13, CD.textSecondary).lineLimit(1).truncationMode(.middle)
                .frame(maxWidth: d - 48)
                if let label = session.fileProgressLabel { Text(label).body(12, CD.textDim) }
            }
        }
    }
}

private struct StatTile: View {
    let label: String
    let value: String
    var valueColor: Color = CD.textPrimary
    var body: some View {
        GlassCard(radius: 20, fill: CD.glass, border: CD.hairline, padding: .hv(14, 12), spacing: 4) {
            Text(label).body(11, CD.textDim)
            Text(value).monoStyle(17, color: valueColor).lineLimit(1).minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct VerificationCode: View {
    let sas: String
    let peerName: String
    var body: some View {
        GlassCard(radius: 26, fill: CD.glass, border: CD.hairline, spacing: 12) {
            HStack(spacing: 8) {
                CdIconView(CdIcons.shield, color: CD.accent, size: 18)
                Text("Verification code").body(13, weight: .semibold).layoutPriority(1)
                Spacer(minLength: 8)
                Text("match it on \(peerName)").body(12, CD.textDim).lineLimit(1).truncationMode(.tail)
            }
            HStack(spacing: 6) {
                ForEach(Array(sas.enumerated()), id: \.offset) { _, ch in
                    Text(String(ch)).monoStyle(26)
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(CD.glassMid))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Code " + sas.map(String.init).joined(separator: " "))
        }
    }
}
