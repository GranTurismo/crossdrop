import SwiftUI

extension SessionState {
    /// "file 2 of 3" from the offer manifest, when the session has more than one file.
    var fileProgressLabel: String? {
        guard let files = offer?.files, files.count >= 2 else { return nil }
        if let i = files.firstIndex(where: { $0.name == currentFileName }) { return "file \(i + 1) of \(files.count)" }
        return "\(files.count) files"
    }
}

/// Live session (if any) + the persisted log of finished transfers, grouped into Today / Earlier.
/// Tapping the live card reopens the transfer view.
struct TransfersScreen: View {
    let groups: [TransferGroup]
    @ObservedObject var history: HistoryStore
    var onOpenActive: ((UUID) -> Void)?
    @Environment(\.navInset) private var navInset
    @State private var confirmClear = false

    var body: some View {
        let entries = history.entries
        let active = groups.filter { !$0.isTerminal }
        let todayStart = Calendar.current.startOfDay(for: Date())
        let today = entries.filter { $0.date >= todayStart }
        let earlier = entries.filter { $0.date < todayStart }
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.13, centerX: 0.85, centerY: 50, radius: 210)
            ScrollView {
                VStack(spacing: 14) {
                    HStack {
                        ScreenTitle("Transfers")
                        Spacer()
                        if !entries.isEmpty {
                            ChipButton(text: "Clear", bg: CD.glassMid, border: CD.hairlineStrong, height: 40, label: "Clear history") { confirmClear = true }
                        }
                    }
                    ForEach(active) { g in ActiveCard(g: g, onOpen: onOpenActive.map { open in { open(g.id) } }) }
                    if entries.isEmpty && active.isEmpty { EmptyState() }
                    if !today.isEmpty {
                        SectionLabel("Today").padding(.top, 6)
                        HistoryGroup(entries: today)
                    }
                    if !earlier.isEmpty {
                        SectionLabel("Earlier").padding(.top, 6)
                        HistoryGroup(entries: earlier)
                    }
                    if !entries.isEmpty {
                        FootNote("Stored on this device only. Keeps your last \(HistoryStore.maxEntries) sessions.")
                    }
                }
                .padding(.horizontal, 16).padding(.top, 28).padding(.bottom, navInset + 8)
                .readableWidth()
            }
        }
        .confirmDialog("Clear transfer history?", isPresented: $confirmClear,
                       message: "This forgets the log of past transfers. Received files stay in your inbox.",
                       confirmLabel: "Clear") { history.clear() }
    }
}

private struct ActiveCard: View {
    let g: TransferGroup
    let onOpen: (() -> Void)?
    var body: some View {
        let s = g.primary
        GlassCard(radius: 26, fill: CD.glassMid, border: CD.accent.opacity(0.4), glow: CD.accent.opacity(0.13), glowRadius: 40,
                  padding: .hv(18, 16), spacing: 12, action: onOpen, actionLabel: "Opens the transfer") {
            HStack(spacing: 8) {
                Dot(color: CD.accent, size: 8)
                Text(g.multi ? "Sending to \(g.sessions.count) devices"
                     : (s.direction == .sending ? "Sending to " : "Receiving from ") + s.peerName)
                    .body(14, weight: .semibold).lineLimit(1)
                Spacer(minLength: 8)
                Text("\(Int(g.fraction * 100))%").displayStyle(20, color: CD.accent)
            }
            ProgressBar(fraction: g.fraction, height: 6, glow: true)
            Text((g.multi ? ["\(g.completed) of \(g.sessions.count) delivered", g.bytesPerSec > 0 ? Fmt.rate(g.bytesPerSec) : nil]
                  : [s.currentFileName ?? "Establishing a secure channel…", s.fileProgressLabel,
                     s.bytesPerSec > 0 ? Fmt.rate(s.bytesPerSec) : nil]).compactMap { $0 }.joined(separator: " · "))
                .body(12, CD.textDim).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct HistoryGroup: View {
    let entries: [HistoryEntry]
    var body: some View {
        GroupCard {
            ForEach(Array(entries.enumerated()), id: \.element.id) { i, e in
                if i > 0 { RowSeparator() }
                HistoryRow(e: e)
            }
        }
    }
}

private struct HistoryRow: View {
    let e: HistoryEntry
    var body: some View {
        let browser = e.peerName == "browser share"
        let received = !e.isSend
        let (pillFg, pillBg): (Color, Color) = {
            switch e.status {
            case "COMPLETED": return (CD.accentText, CD.accent.opacity(0.14))
            case "FAILED": return (CD.dangerText, Color(hex: 0xFF6B6B, alpha: 0.14))
            default: return (CD.textSecondary, CD.glassHigh)
            }
        }()
        let when = Self.formatWhen(e.date)
        HStack(spacing: 12) {
            CdIconView(browser ? CdIcons.qr : received ? CdIcons.arrowDown : CdIcons.arrowUp,
                       color: received ? CD.accent : CD.textPrimary, size: 18)
                .frame(width: 40, height: 40)
                .background(Circle().fill(received ? CD.accent.opacity(0.14) : CD.glassHigh))
            VStack(alignment: .leading, spacing: 3) {
                Text(browser ? "Browser share" : e.peerName).body(14, weight: .semibold).lineLimit(1)
                Text((e.files > 1 ? "\(e.files) files" : e.summary) + " · " + Fmt.bytes(e.bytes))
                    .body(12, CD.textDim).lineLimit(1).truncationMode(.middle)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 5) {
                if when.mono { Text(when.text).monoStyle(11, color: CD.textDim) }
                else { Text(when.text).body(11, CD.textDim) }
                StatusPill(text: Self.statusLabel(e.status), fg: pillFg, bg: pillBg)
            }
        }
        .padding(10)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(browser ? "Browser share" : received ? "Received from \(e.peerName)" : "Sent to \(e.peerName)"), "
                            + "\(e.files > 1 ? "\(e.files) files" : e.summary), \(Fmt.bytes(e.bytes)), \(when.text), \(Self.statusLabel(e.status))")
    }

    static func statusLabel(_ s: String) -> String {
        switch s {
        case "COMPLETED": return "Completed"
        case "FAILED": return "Failed"
        case "DECLINED": return "Declined"
        case "CANCELLED": return "Cancelled"
        default: return s.lowercased().capitalized
        }
    }

    /// Clock time today (monospaced), else "Yesterday" / weekday / date.
    static func formatWhen(_ d: Date) -> (text: String, mono: Bool) {
        let cal = Calendar.current
        let start = cal.startOfDay(for: Date())
        if d >= start { return (d.formatted(date: .omitted, time: .shortened), true) }
        if cal.isDateInYesterday(d) { return ("Yesterday", false) }
        if let weekAgo = cal.date(byAdding: .day, value: -6, to: start), d >= weekAgo {
            return (d.formatted(.dateTime.weekday(.abbreviated)), false)
        }
        return (d.formatted(.dateTime.month(.abbreviated).day()), false)
    }
}

private struct EmptyState: View {
    var body: some View {
        GlassCard(fill: CD.glassLow, padding: .hv(20, 36), alignment: .center) {
            CdIconView(CdIcons.transfers, color: CD.textSecondary, size: 26)
                .frame(width: 56, height: 56).background(Circle().fill(CD.glassHigh))
            Text("No transfers yet").displayStyle(17).padding(.top, 14)
            Text("Tap a device on the radar to send something.").body(13, CD.textDim)
                .multilineTextAlignment(.center).padding(.top, 4)
        }
    }
}
