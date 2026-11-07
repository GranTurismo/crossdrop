import SwiftUI

struct TransfersScreen: View {
    let session: SessionState?
    @ObservedObject var history: HistoryStore

    var body: some View {
        let entries = history.entries
        let active = session.flatMap { $0.status.isTerminal ? nil : $0 }
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                ScreenTitle(title: "Transfers", subtitle: active != nil ? "1 in progress" : "\(entries.count) finished")
                Spacer()
                if !entries.isEmpty {
                    Button("Clear") { history.clear() }.font(.cd(13)).foregroundColor(Aurora.danger)
                }
            }
            .padding(.top, 20).padding(.bottom, 8)

            if let active { activeCard(active).padding(.bottom, 10) }

            if entries.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "arrow.up.arrow.down").font(.cd(36)).foregroundColor(Aurora.textDim.opacity(0.5))
                    Text("No transfers yet.").font(.cd(13)).foregroundColor(Aurora.textDim)
                    Text("Tap a device on the radar to send something.")
                        .font(.cd(12)).foregroundColor(Aurora.textDim.opacity(0.7))
                }
                .frame(maxWidth: .infinity).padding(.vertical, 48)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) { ForEach(entries) { historyRow($0) } }.padding(.bottom, 16)
                }
            }
        }
        .padding(.horizontal, 20)
        .readableWidth(720)
        .background(Aurora.deepSpace)
    }

    private func activeCard(_ s: SessionState) -> some View {
        GlassCard(radius: 16, stroke: Aurora.teal.opacity(0.5)) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: s.direction == .sending ? "arrow.up" : "arrow.down").foregroundColor(Aurora.teal)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(s.peerName).font(.cd(14, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                        Text(s.currentFileName ?? "establishing channel…").font(.cd(12)).foregroundColor(Aurora.textDim).lineLimit(1)
                    }
                    Spacer()
                    Text("\(Int(s.fraction * 100))%").font(.cd(15, weight: .semibold)).foregroundColor(Aurora.cyan)
                }
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Aurora.deepSpaceSoft)
                        Capsule().fill(Aurora.teal).frame(width: g.size.width * s.fraction)
                    }
                }.frame(height: 6)
                Text("\(Fmt.bytes(s.bytesDone)) / \(Fmt.bytes(s.bytesTotal))" + (s.bytesPerSec > 0 ? "  ·  \(Fmt.rate(s.bytesPerSec))" : ""))
                    .font(.cd(11)).foregroundColor(Aurora.textDim)
            }
            .padding(14)
        }
    }

    private func historyRow(_ e: HistoryEntry) -> some View {
        let tint: Color = {
            switch e.status {
            case "COMPLETED": return e.isSend ? Aurora.teal : Aurora.cyan
            case "FAILED": return Aurora.danger
            case "DECLINED": return Aurora.phosphor
            default: return Aurora.textDim
            }
        }()
        return GlassCard(radius: 18) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Aurora.tint(tint)).frame(width: 38, height: 38)
                    .overlay(Image(systemName: e.isSend ? "arrow.up" : "arrow.down").font(.cd(15, weight: .semibold)).foregroundColor(tint))
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.summary).font(.cd(14, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                    Text("\(e.isSend ? "to" : "from") \(e.peerName)" + (e.files > 1 ? " · \(e.files) files" : ""))
                        .font(.cd(12)).foregroundColor(Aurora.textDim).lineLimit(1)
                }
                Spacer(minLength: 10)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(Self.label(e.status)).font(.cd(12, weight: .medium)).foregroundColor(tint)
                    Text("\(Fmt.bytes(e.bytes)) · \(Self.time(e.date))").font(.cd(11)).foregroundColor(Aurora.textDim)
                }
            }
            .padding(12)
        }
    }

    private static func label(_ s: String) -> String {
        switch s { case "COMPLETED": return "done"; default: return s.lowercased() }
    }

    private static func time(_ d: Date) -> String {
        Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened)
            : d.formatted(.dateTime.month(.abbreviated).day().hour().minute())
    }
}
