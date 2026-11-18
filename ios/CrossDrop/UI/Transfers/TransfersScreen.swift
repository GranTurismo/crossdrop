import SwiftUI

extension SessionState {
    /// "file 2 of 3" from the offer manifest, when the session has more than one file.
    var fileProgressLabel: String? {
        guard let files = offer?.files, files.count >= 2 else { return nil }
        let count = files.count
        if let i = files.firstIndex(where: { $0.name == currentFileName }) { return tr("file {0} of {1}", i + 1, count) }
        return trPlural(count, one: "1 file", other: "{n} files")
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
                        ScreenTitle(tr("Transfers"))
                        Spacer()
                        if !entries.isEmpty {
                            ChipButton(text: tr("Clear"), bg: CD.glassMid, border: CD.hairlineStrong, height: 40, label: tr("Clear history")) { confirmClear = true }
                        }
                    }
                    ForEach(active) { g in ActiveCard(g: g, onOpen: onOpenActive.map { open in { open(g.id) } }) }
                    if entries.isEmpty && active.isEmpty { EmptyState() }
                    if !today.isEmpty {
                        SectionLabel(tr("Today")).padding(.top, 6)
                        HistoryGroup(entries: today)
                    }
                    if !earlier.isEmpty {
                        SectionLabel(tr("Earlier")).padding(.top, 6)
                        HistoryGroup(entries: earlier)
                    }
                    if !entries.isEmpty {
                        FootNote(tr("Stored on this device only. Keeps your last {0} sessions.", HistoryStore.maxEntries))
                    }
                }
                .padding(.horizontal, 16).padding(.top, 28).padding(.bottom, navInset + 8)
                .readableWidth()
            }
        }
        .confirmDialog(tr("Clear transfer history?"), isPresented: $confirmClear,
                       message: tr("This forgets the log of past transfers. Received files stay in your inbox."),
                       confirmLabel: tr("Clear")) { history.clear() }
    }
}

private struct ActiveCard: View {
    let g: TransferGroup
    let onOpen: (() -> Void)?
    var body: some View {
        let s = g.primary
        GlassCard(radius: 26, fill: CD.glassMid, border: CD.accent.opacity(0.4), glow: CD.accent.opacity(0.13), glowRadius: 40,
                  padding: .hv(18, 16), spacing: 12, action: onOpen, actionLabel: tr("Opens the transfer")) {
            HStack(spacing: 8) {
                Dot(color: CD.accent, size: 8)
                Text(g.multi ? tr("Sending to {0} devices", g.sessions.count)
                     : s.direction == .sending ? tr("Sending to {0}", s.peerName) : tr("Receiving from {0}", s.peerName))
                    .body(14, weight: .semibold).lineLimit(1)
                Spacer(minLength: 8)
                Text("\(Int(g.fraction * 100))%").displayStyle(20, color: CD.accent)
            }
            ProgressBar(fraction: g.fraction, height: 6, glow: true)
            Text((g.multi ? [tr("{0} of {1} delivered", g.completed, g.sessions.count), g.bytesPerSec > 0 ? Fmt.rate(g.bytesPerSec) : nil]
                  : [s.currentFileName ?? tr("Establishing a secure channel…"), s.fileProgressLabel,
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
        let summary = e.summary == "Switch phones" ? tr("Switch phones") : e.summary
        let contents = e.files > 1 ? trPlural(e.files, one: "1 file", other: "{n} files") : summary
        let who = browser ? tr("Browser share") : received ? tr("Received from {0}", e.peerName) : tr("Sent to {0}", e.peerName)
        HStack(spacing: 12) {
            CdIconView(browser ? CdIcons.qr : received ? CdIcons.arrowDown : CdIcons.arrowUp,
                       color: received ? CD.accent : CD.textPrimary, size: 18)
                .frame(width: 40, height: 40)
                .background(Circle().fill(received ? CD.accent.opacity(0.14) : CD.glassHigh))
            VStack(alignment: .leading, spacing: 3) {
                Text(browser ? tr("Browser share") : e.peerName).body(14, weight: .semibold).lineLimit(1)
                Text(contents + " · " + Fmt.bytes(e.bytes))
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
        .accessibilityLabel([who, contents, Fmt.bytes(e.bytes), when.text, Self.statusLabel(e.status)].joined(separator: ", "))
    }

    static func statusLabel(_ s: String) -> String {
        switch s {
        case "COMPLETED": return tr("Completed")
        case "FAILED": return tr("Failed")
        case "DECLINED": return tr("Declined")
        case "CANCELLED": return tr("Cancelled")
        default: return s.lowercased().capitalized
        }
    }

    /// Clock time today (monospaced), else "Yesterday" / weekday / date.
    static func formatWhen(_ d: Date) -> (text: String, mono: Bool) {
        let cal = Calendar.current
        let start = cal.startOfDay(for: Date())
        if d >= start { return (d.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(I18n.locale)), true) }
        if cal.isDateInYesterday(d) { return (tr("Yesterday"), false) }
        if let weekAgo = cal.date(byAdding: .day, value: -6, to: start), d >= weekAgo {
            return (d.formatted(.dateTime.weekday(.abbreviated).locale(I18n.locale)), false)
        }
        return (d.formatted(.dateTime.month(.abbreviated).day().locale(I18n.locale)), false)
    }
}

private struct EmptyState: View {
    var body: some View {
        GlassCard(fill: CD.glassLow, padding: .hv(20, 36), alignment: .center) {
            CdIconView(CdIcons.transfers, color: CD.textSecondary, size: 26)
                .frame(width: 56, height: 56).background(Circle().fill(CD.glassHigh))
            Text(tr("No transfers yet")).displayStyle(17).padding(.top, 14)
            Text(tr("Tap a device on the radar to send something.")).body(13, CD.textDim)
                .multilineTextAlignment(.center).padding(.top, 4)
        }
    }
}
