import SwiftUI
@preconcurrency import CrossDropCore

/// Full-screen transfer view built around `LiquidOrb`. Reads the accelerometer while visible so the
/// fluid reacts to the device physically moving.
struct TransferOverlay: View {
    let session: SessionState
    let onCancel: () -> Void
    let onClose: () -> Void

    @StateObject private var tiltSource = TiltSource()

    private var turbulence: Double {
        min(1, max(0, Double(session.bytesPerSec) / 60e6 * 0.8 + tiltSource.tilt.energy * 0.4))
    }
    private var completed: Bool { session.status == .completed }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                AuroraBackdrop()
                // Scrolls instead of clipping in tiny windows (Split View, narrow Mac window, large text).
                ScrollView {
                    VStack {
                        header
                        Spacer(minLength: 12)
                        LiquidOrb(progress: completed ? 1 : session.fraction, turbulence: turbulence,
                                  tilt: tiltSource.tilt, completed: completed, size: orbSize(geo.size))
                            .accessibilityElement()
                            .accessibilityLabel("Transfer progress")
                            .accessibilityValue("\(Int(session.fraction * 100)) percent")
                        Spacer(minLength: 12)
                        footer
                    }
                    .padding(24)
                    .frame(minHeight: geo.size.height)
                    .readableWidth(560)
                }
            }
        }
        .onAppear { tiltSource.start() }
        .onDisappear { tiltSource.stop() }
        .onChange(of: session.status) { s in
            if s == .completed { UINotificationFeedbackGenerator().notificationOccurred(.success) }
            else if s == .failed { UINotificationFeedbackGenerator().notificationOccurred(.error) }
        }
    }

    private func orbSize(_ size: CGSize) -> CGFloat {
        max(150, min(size.width - 64, size.height * 0.36, 420))
    }

    private var header: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: session.direction == .sending ? "arrow.up" : "arrow.down")
                    .font(.cd(12, weight: .bold))
                Text(session.direction == .sending ? "S E N D I N G" : "R E C E I V I N G")
                    .font(.cd(12, weight: .medium)).tracking(1)
            }
            .foregroundColor(Aurora.textDim)
            Text(session.peerName).font(.cd(24, weight: .semibold)).foregroundColor(Aurora.textPrimary)
            if let sas = session.sas {
                HStack(spacing: 4) {
                    Text("◈").font(.cd(11))
                    Text("verify  \(sas)").font(.cd(13, weight: .medium)).tracking(2)
                }
                .foregroundColor(Aurora.phosphor)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Aurora.deepSpaceSoft, in: Capsule())
                .overlay(Capsule().stroke(Aurora.phosphor.opacity(0.35), lineWidth: 1))
                .padding(.top, 4)
            }
        }
    }

    private var footer: some View {
        VStack(spacing: 14) {
            Text(statusLine).font(.cd(15, weight: .medium)).foregroundColor(statusColor)
                .multilineTextAlignment(.center)
            GlassCard(radius: 18) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(session.currentFileName ?? "preparing…")
                            .font(.cd(13, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                        Text("\(Fmt.bytes(session.bytesDone)) / \(Fmt.bytes(session.bytesTotal))")
                            .font(.cd(11)).foregroundColor(Aurora.textDim)
                        if session.bytesSkipped > 0 {
                            Text("Δ \(Fmt.bytes(session.bytesSkipped)) already local")
                                .font(.cd(11)).foregroundColor(Aurora.cyan)
                        }
                    }
                    Spacer(minLength: 16)
                    if session.bytesPerSec > 0 {
                        Text(Fmt.rate(session.bytesPerSec)).font(.cd(14, weight: .semibold)).foregroundColor(Aurora.cyan)
                    }
                }
                .padding(.horizontal, 18).padding(.vertical, 12)
            }
            if session.status.isTerminal {
                Button("Done", action: onClose).buttonStyle(PrimaryButtonStyle()).frame(maxWidth: 220)
            } else {
                Button("Cancel transfer", action: onCancel)
                    .font(.cd(15, weight: .medium)).foregroundColor(Aurora.danger)
                    .padding(.vertical, 12)
            }
        }
    }

    private var statusLine: String {
        switch session.status {
        case .connecting: return "Establishing secure channel…"
        case .awaitingAccept: return "Waiting for \(session.peerName) to accept…"
        case .transferring: return "\(Int(session.fraction * 100))%"
        case .completed: return "Transfer complete"
        case .failed: return "Failed — \(session.error ?? "unknown error")"
        case .declined: return "\(session.peerName) declined"
        case .cancelled: return "Cancelled"
        }
    }

    private var statusColor: Color {
        switch session.status {
        case .completed: return Aurora.phosphor
        case .failed, .declined: return Aurora.danger
        case .transferring: return Aurora.cyan
        default: return Aurora.textDim
        }
    }
}
