import SwiftUI
import QuickLook

private enum InboxFilter: String, CaseIterable {
    case all = "All", photos = "Photos", videos = "Videos", docs = "Docs", other = "Other"
    var kinds: Set<FileKind>? {
        switch self {
        case .all: return nil
        case .photos: return [.photo]
        case .videos: return [.video]
        case .docs: return [.doc]
        case .other: return [.audio, .archive, .app, .other]
        }
    }
}

/// Received files in the app's `Documents/Received`: a storage breakdown, type filters and the list.
/// Open in Quick Look (which can share/export), or shred (zero-fill + unlink).
struct InboxScreen: View {
    @ObservedObject var inbox: InboxStore
    @Environment(\.navInset) private var navInset
    @State private var preview: URL?
    @State private var filter: InboxFilter = .all
    @State private var confirmWipe = false
    @State private var confirmDelete: InboxStore.ReceivedFile?

    var body: some View {
        let files = inbox.files
        let shown = files.filter { f in filter.kinds?.contains(FileKind.of(f.name)) ?? true }
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: CD.violet, alpha: 0.18, centerX: 0.15, centerY: 100, radius: 220)
            ScrollView {
                VStack(spacing: 14) {
                    HStack {
                        ScreenTitle("Inbox")
                        Spacer()
                        if !files.isEmpty {
                            ChipButton(text: "Wipe all", icon: CdIcons.trash, fg: CD.dangerText, bg: CD.danger.opacity(0.1),
                                       border: CD.danger.opacity(0.3), height: 40) { confirmWipe = true }
                        }
                    }
                    if files.isEmpty {
                        EmptyInbox()
                    } else {
                        StorageCard(files: files)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(InboxFilter.allCases, id: \.self) { f in
                                    let n = f.kinds.map { k in files.filter { k.contains(FileKind.of($0.name)) }.count } ?? files.count
                                    if f == .all || n > 0 {
                                        FilterPill(text: f.rawValue, selected: filter == f) { filter = f }
                                    }
                                }
                            }
                        }
                        if shown.isEmpty {
                            Text("Nothing here in \(filter.rawValue.lowercased()).").body(13, CD.textDim)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(8)
                        } else {
                            GroupCard {
                                ForEach(Array(shown.enumerated()), id: \.element.id) { i, f in
                                    if i > 0 { RowSeparator() }
                                    FileRow(file: f, onOpen: { preview = f.url }, onDelete: { confirmDelete = f })
                                }
                            }
                        }
                    }
                    FootNote("Kept in CrossDrop's own folder on this \(Platform.deviceNoun) (also reachable from the Files app). "
                             + "Wiping overwrites each file with zeros first.", icon: CdIcons.lock)
                }
                .padding(.horizontal, 16).padding(.top, 28).padding(.bottom, navInset + 8)
                .readableWidth()
            }
        }
        .quickLookPreview($preview)
        .onAppear { inbox.refresh() }
        .confirmDialog("Wipe the inbox?", isPresented: $confirmWipe,
                       message: "All \(files.count) received files are overwritten with zeros and deleted. This can't be undone.",
                       confirmLabel: "Wipe all") { inbox.shredAll() }
        .confirmDialog("Delete \(confirmDelete?.name ?? "file")?",
                       isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
                       message: "The file is overwritten with zeros and removed from this device.",
                       confirmLabel: "Delete") {
            if let f = confirmDelete { inbox.shred(f) }
            confirmDelete = nil
        }
    }
}

private struct StorageCard: View {
    let files: [InboxStore.ReceivedFile]
    private static let mainKinds: Set<FileKind> = [.video, .photo, .doc]

    var body: some View {
        let total = files.reduce(Int64(0)) { $0 + $1.size }
        // Category totals, largest first; small categories fold into "Other".
        let byKind: [(FileKind, Int64)] = Dictionary(grouping: files) { f -> FileKind in
            let k = FileKind.of(f.name)
            return Self.mainKinds.contains(k) ? k : .other
        }
        .map { ($0.key, $0.value.reduce(Int64(0)) { $0 + $1.size }) }
        .filter { $0.1 > 0 }
        .sorted { $0.1 > $1.1 }

        GlassCard(radius: 26, padding: .all(18), spacing: 14) {
            HStack(alignment: .lastTextBaseline) {
                Text(Fmt.bytes(total)).displayStyle(26)
                Spacer()
                Text("\(files.count) file\(files.count == 1 ? "" : "s")").body(13, CD.textDim)
            }
            GeometryReader { g in
                let gaps = CGFloat(max(0, byKind.count - 1)) * 4
                let avail = max(0, g.size.width - gaps)
                let weights = byKind.map { max(0.02, Double($0.1) / Double(max(total, 1))) }
                let sum = weights.reduce(0, +)
                HStack(spacing: 4) {
                    ForEach(Array(byKind.enumerated()), id: \.offset) { i, item in
                        Capsule().fill(item.0.swatch).frame(width: avail * weights[i] / max(sum, 0.0001))
                    }
                }
            }
            .frame(height: 10)
            .accessibilityElement()
            .accessibilityLabel(byKind.map { "\($0.0.label) \(Fmt.bytes($0.1))" }.joined(separator: ", "))
            HStack(spacing: 14) {
                ForEach(byKind, id: \.0) { k, _ in
                    HStack(spacing: 6) {
                        Circle().fill(k.swatch).frame(width: 8, height: 8)
                        Text(k.label).body(12, CD.textSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .accessibilityHidden(true)
        }
    }
}

private struct FileRow: View {
    let file: InboxStore.ReceivedFile
    let onOpen: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    FileTypeTile(fileName: file.name, size: 44)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(file.name).body(14, weight: .semibold).lineLimit(1).truncationMode(.middle)
                        Text("\(Fmt.bytes(file.size)) · \(Self.whenLabel(file.modified))").body(12, CD.textDim)
                    }
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint("Opens the file")
            ChipButton(text: "Open", label: "Open \(file.name)", action: onOpen)
            CircleButton(icon: CdIcons.trash, label: "Delete \(file.name)", size: 40, iconSize: 17, fill: .clear, tint: CD.textDim, action: onDelete)
        }
        .padding(.leading, 10).padding(.trailing, 4).padding(.vertical, 9)
        .contextMenu {
            Button(action: onOpen) { Label("Open", systemImage: "eye") }
            ShareLink(item: file.url) { Label("Share…", systemImage: "square.and.arrow.up") }
            Button(role: .destructive, action: onDelete) { Label("Delete", systemImage: "trash") }
        }
    }

    static func whenLabel(_ d: Date) -> String {
        let cal = Calendar.current
        let start = cal.startOfDay(for: Date())
        if d >= start { return d.formatted(date: .omitted, time: .shortened) }
        if cal.isDateInYesterday(d) { return "Yesterday" }
        if let weekAgo = cal.date(byAdding: .day, value: -6, to: start), d >= weekAgo { return d.formatted(.dateTime.weekday(.abbreviated)) }
        return d.formatted(.dateTime.month(.abbreviated).day())
    }
}

private struct EmptyInbox: View {
    var body: some View {
        GlassCard(padding: .hv(20, 36), alignment: .center) {
            CdIconView(CdIcons.inbox, color: CD.textSecondary, size: 26)
                .frame(width: 56, height: 56).background(Circle().fill(CD.glassHigh))
            Text("Nothing received yet").displayStyle(17).padding(.top, 14)
            Text("Files you accept land here.").body(13, CD.textDim).padding(.top, 4)
        }
    }
}
