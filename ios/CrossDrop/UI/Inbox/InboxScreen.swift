import SwiftUI
import QuickLook

struct InboxScreen: View {
    @EnvironmentObject var container: AppContainer
    @ObservedObject var inbox: InboxStore
    @State private var preview: URL?

    var body: some View {
        let files = inbox.files
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                ScreenTitle(title: "Inbox", subtitle: files.isEmpty ? "files you accept land here"
                            : "\(files.count) file\(files.count == 1 ? "" : "s") · \(Fmt.bytes(files.reduce(0) { $0 + $1.size }))")
                Spacer()
                if !files.isEmpty {
                    Button("Wipe all") { inbox.shredAll() }.font(.cd(13)).foregroundColor(Aurora.danger)
                }
            }
            .padding(.top, 20).padding(.bottom, 12)

            if files.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "tray").font(.cd(38)).foregroundColor(Aurora.textDim.opacity(0.5))
                    Text("Nothing received yet.").font(.cd(13)).foregroundColor(Aurora.textDim)
                    Text("Files stay in the app until you open, export or delete them.")
                        .font(.cd(12)).foregroundColor(Aurora.textDim.opacity(0.7)).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 48)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(files) { f in row(f) }
                    }
                    .padding(.bottom, 16)
                }
            }
        }
        .padding(.horizontal, 20)
        .readableWidth(720)
        .background(Aurora.deepSpace)
        .quickLookPreview($preview)
        .onAppear { inbox.refresh() }
    }

    private func row(_ f: InboxStore.ReceivedFile) -> some View {
        GlassCard(radius: 18) {
            HStack(spacing: 12) {
                FileTypeTile(fileName: f.name)
                VStack(alignment: .leading, spacing: 2) {
                    Text(f.name).font(.cd(14, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                    Text("\(Fmt.bytes(f.size)) · \(f.modified.formatted(date: .omitted, time: .shortened))")
                        .font(.cd(12)).foregroundColor(Aurora.textDim)
                }
                Spacer(minLength: 4)
                Button("Open") { preview = f.url }.font(.cd(13)).foregroundColor(Aurora.teal)
                ShareLink(item: f.url) {
                    Image(systemName: "square.and.arrow.up").font(.cd(14)).foregroundColor(Aurora.cyan)
                        .frame(width: 32, height: 32).contentShape(Rectangle())
                }
                .accessibilityLabel("Share \(f.name)").pointerHover()
                Button { inbox.shred(f) } label: {
                    Image(systemName: "xmark").font(.cd(13, weight: .semibold)).foregroundColor(Aurora.danger)
                        .frame(width: 32, height: 32).contentShape(Rectangle())
                }
                .accessibilityLabel("Delete \(f.name)").pointerHover()
            }
            .padding(10)
        }
        .contextMenu {
            Button { preview = f.url } label: { Label("Open", systemImage: "eye") }
            Button(role: .destructive) { inbox.shred(f) } label: { Label("Delete", systemImage: "trash") }
        }
    }
}
