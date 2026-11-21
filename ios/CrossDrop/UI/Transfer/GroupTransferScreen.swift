import SwiftUI
@preconcurrency import CrossDropCore

/// One send to several devices: an overall ring plus a row per device with its own progress, outcome
/// and 6-digit code. Devices that fail or decline can be retried without resending to the others.
struct GroupTransferScreen: View {
    let group: TransferGroup
    let onCancelAll: () -> Void
    let onRetry: () -> Void
    let onClose: () -> Void
    var onHide: (() -> Void)?

    var body: some View {
        let done = group.isTerminal
        let allOk = done && group.completed == group.sessions.count
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: done && group.completed == 0 ? CD.dangerText : CD.accent, alpha: 0.14, centerY: 260, radius: 230)
            VStack(spacing: 0) {
                HStack {
                    if let onHide, !done { CircleButton(icon: CdIcons.chevronLeft, label: tr("Back to transfers"), action: onHide) }
                    else { Color.clear.frame(width: 44, height: 44) }
                    Spacer()
                    Text(!done ? tr("Sending to {0} devices", group.sessions.count)
                         : allOk ? tr("Delivered to all {0}", group.sessions.count) : tr("{0} of {1} delivered", group.completed, group.sessions.count))
                        .body(13, done && !allOk ? CD.textSecondary : CD.accent, weight: .semibold)
                    Spacer()
                    Color.clear.frame(width: 44, height: 44)
                }
                ScrollView {
                    VStack(spacing: 12) {
                        ring(done: done, allOk: allOk)
                        Text([("\(Fmt.bytes(group.bytesDone)) / \(Fmt.bytes(group.bytesTotal))"),
                              group.bytesPerSec > 0 ? tr("{0}/s total", Fmt.bytes(group.bytesPerSec)) : nil]
                                .compactMap { $0 }.joined(separator: " · "))
                            .monoStyle(13, color: CD.textDim)
                        ForEach(group.sessions) { s in DeviceRow(s: s) }
                        Text(tr("Each device gets its own encrypted connection. Compare the code on a device to be sure it's the one you meant."))
                            .body(12, CD.textDim).padding(.horizontal, 6)
                    }
                    .padding(.top, 16)
                }
                HStack(spacing: 10) {
                    if done && group.problems > 0 && group.direction == .sending {
                        PillButton(tr("Done"), style: .glass, action: onClose)
                        PillButton(tr("Retry {0}", group.problems), icon: CdIcons.arrowUp, action: onRetry)
                    } else if done {
                        PillButton(tr("Done"), style: allOk ? .accent : .glass, action: onClose)
                    } else {
                        PillButton(tr("Cancel all"), style: .danger, action: onCancelAll)
                        if let onHide { PillButton(tr("Hide"), style: .glass, action: onHide) }
                    }
                }
                .padding(.top, 16)
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 22)
        }
    }

    private func ring(done: Bool, allOk: Bool) -> some View {
        let f = done && group.problems == 0 ? 1 : group.fraction
        return ZStack {
            Circle().inset(by: 7).stroke(Color.white.opacity(0.07), lineWidth: 10)
            Circle().inset(by: 7).trim(from: 0, to: f)
                .stroke(CD.accent, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.4), value: f)
            if done && group.problems == 0 {
                CdIconView(CdIcons.check, color: CD.onAccent, size: 28)
                    .frame(width: 56, height: 56).background(Circle().fill(CD.accent))
            } else {
                (Text("\(Int(f * 100))").font(.display(46)) + Text("%").font(.display(22)).foregroundColor(CD.textDim))
                    .foregroundColor(CD.textPrimary)
            }
        }
        .frame(width: 176, height: 176)
        .accessibilityElement()
        .accessibilityLabel(tr("Overall progress"))
        .accessibilityValue(tr("{0} percent", Int(f * 100)))
    }
}

private struct DeviceRow: View {
    let s: SessionState
    var body: some View {
        let (label, color) = sessionLabel(s)
        VStack(spacing: 9) {
            HStack(spacing: 12) {
                CdIconView(s.status == .completed ? CdIcons.check : deviceIconFor(s.peerName),
                           color: s.status == .completed ? CD.onAccent : CD.textPrimary, size: 18)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(s.status == .completed ? CD.accent : CD.glassHigh))
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.peerName).body(14, weight: .semibold).lineLimit(1)
                    Text(label).body(12, color).lineLimit(2)
                }
                Spacer(minLength: 4)
                if let sas = s.sas { Text(String(sas.prefix(3)) + " " + String(sas.suffix(3))).monoStyle(13, color: CD.textSecondary) }
            }
            if let note = s.note { Text(note).body(12, CD.textDim).frame(maxWidth: .infinity, alignment: .leading) }
            if !s.status.isTerminal { ProgressBar(fraction: s.fraction, height: 4) }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(CD.glass))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CD.hairline, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private func sessionLabel(_ s: SessionState) -> (String, Color) {
        switch s.status {
        case .connecting: return (tr("Connecting…"), CD.textDim)
        case .awaitingAccept: return (tr("Waiting for them to accept"), CD.textDim)
        case .transferring: return ("\(Int(s.fraction * 100))%" + (s.bytesPerSec > 0 ? " · \(Fmt.rate(s.bytesPerSec))" : ""), CD.accent)
        case .completed: return (tr("Delivered"), CD.accent)
        case .declined: return (tr("Declined"), CD.dangerText)
        case .cancelled: return (tr("Cancelled"), CD.textSecondary)
        case .failed: return (s.error ?? tr("Failed"), CD.dangerText)
        }
    }
}
