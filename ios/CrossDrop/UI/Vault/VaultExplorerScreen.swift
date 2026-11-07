import SwiftUI
import PhotosUI
import QuickLook
@preconcurrency import CrossDropCore

/// Observes the explorer directly (nested ObservableObjects don't propagate through AppContainer)
/// and slides the explorer over the app while a vault is mounted.
struct VaultExplorerHost: View {
    @ObservedObject var explorer: VaultExplorerManager

    var body: some View {
        ZStack {
            if let s = explorer.state {
                VaultExplorerScreen(state: s, explorer: explorer)
                    .background(AuroraBackdrop())
                    .transition(.move(edge: .bottom))
                    .zIndex(2)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: explorer.state != nil)
    }
}

/// Native vault explorer: breadcrumbs, listing, batch download into the inbox, uploads into `rw`
/// vaults and quick-look previews (the file is pulled to a temp copy first).
struct VaultExplorerScreen: View {
    let state: VaultExplorerManager.Ui
    let explorer: VaultExplorerManager

    @State private var pin = ""
    @State private var uploadMenu = false
    @State private var showFiles = false
    @State private var showPhotos = false
    @State private var photoSelection: [PhotosPickerItem] = []

    var body: some View {
        VStack(spacing: 0) {
            header
            if !state.pinRequired { breadcrumb }
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
            jobsStrip
            selectionBar
        }
        .padding(.horizontal, 20).padding(.top, 8)
        .readableWidth(720)
        .quickLookPreview(Binding(get: { state.previewURL }, set: { if $0 == nil { explorer.dismissPreview() } }))
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result, !urls.isEmpty { explorer.upload(urls) }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photoSelection, maxSelectionCount: nil, matching: .any(of: [.images, .videos]))
        .onChange(of: photoSelection) { items in
            guard !items.isEmpty else { return }
            photoSelection = []
            Task {
                var urls: [URL] = []
                for item in items { if let m = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(m.url) } }
                if !urls.isEmpty { explorer.upload(urls, temporary: urls.map { $0.deletingLastPathComponent() }) }
            }
        }
        .confirmationDialog("Upload to this folder", isPresented: $uploadMenu, titleVisibility: .visible) {
            Button("Photos & videos") { showPhotos = true }
            Button("Files") { showFiles = true }
        }
    }

    // MARK: header

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(state.peerName).font(.cd(20, weight: .bold)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                HStack(spacing: 6) {
                    badge(state.writable ? "RW" : "RO", state.writable ? Aurora.phosphor : Aurora.violet)
                    Text("encrypted vault session").font(.cd(11)).foregroundColor(Aurora.textDim)
                }
            }
            Spacer()
            if state.writable && !state.pinRequired {
                Button { uploadMenu = true } label: {
                    Text("↑ upload").font(.cd(12)).foregroundColor(Aurora.teal)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(Aurora.deepSpaceSoft, in: Capsule())
                        .overlay(Capsule().stroke(Aurora.teal.opacity(0.4), lineWidth: 1))
                }
            }
            Button { explorer.close() } label: {
                Image(systemName: "xmark").font(.cd(16, weight: .semibold)).foregroundColor(Aurora.textDim).padding(8)
            }
            .accessibilityLabel("Close drive").pointerHover()
        }
        .padding(.vertical, 8)
    }

    private func badge(_ text: String, _ color: Color) -> some View {
        Text(text).font(.cd(9, weight: .bold)).foregroundColor(color)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Aurora.deepSpace, in: Capsule())
            .overlay(Capsule().stroke(color.opacity(0.7), lineWidth: 1))
    }

    private var breadcrumb: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                crumb("⌂") { explorer.browse("") }
                let parts = state.path.split(separator: "/").map(String.init)
                ForEach(Array(parts.enumerated()), id: \.offset) { i, seg in
                    Text(" › ").font(.cd(13)).foregroundColor(Aurora.glassEdge)
                    if i == parts.count - 1 {
                        Text(seg).font(.cd(13, weight: .medium)).foregroundColor(Aurora.textPrimary)
                    } else {
                        crumb(seg) { explorer.browse(parts[0...i].joined(separator: "/")) }
                    }
                }
            }
            .padding(.vertical, 10)
        }
    }

    private func crumb(_ text: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(text).font(.cd(13, weight: .medium)).foregroundColor(Aurora.teal).padding(2) }
    }

    // MARK: content

    @ViewBuilder private var content: some View {
        if state.pinRequired {
            pinGate
        } else if state.loading {
            VStack(spacing: 10) {
                ProgressView().tint(Aurora.teal)
                Text("reading drive…").font(.cd(12)).foregroundColor(Aurora.textDim)
            }
        } else if let err = state.error {
            VStack(spacing: 10) {
                Text(err).font(.cd(13)).foregroundColor(Aurora.danger).multilineTextAlignment(.center)
                Button("retry") { explorer.browse(state.path) }.foregroundColor(Aurora.teal)
            }
        } else if state.entries.isEmpty {
            Text("Empty folder").font(.cd(13)).foregroundColor(Aurora.textDim)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(state.entries, id: \.name) { e in row(e) }
                }
            }
        }
    }

    private func key(_ e: VaultDirEntry) -> String { state.path.isEmpty ? e.name : "\(state.path)/\(e.name)" }

    private func row(_ e: VaultDirEntry) -> some View {
        let selected = state.selected.contains(key(e))
        return HStack(spacing: 12) {
            Button {
                if e.isDir { explorer.browse(key(e)) }
                else if VaultExplorerManager.isPreviewable(e) { explorer.preview(key(e)) }
                else { explorer.download([key(e)]) }
            } label: {
                HStack(spacing: 12) {
                    if e.isDir {
                        RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Aurora.tint(Aurora.violet))
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Aurora.violet.opacity(0.35), lineWidth: 1))
                            .overlay(Image(systemName: "folder.fill").font(.cd(15)).foregroundColor(Aurora.violet))
                            .frame(width: 36, height: 36)
                    } else {
                        FileTypeTile(fileName: e.name, size: 36)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.name).font(.cd(14, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                        Text(e.isDir ? "folder" : "\(Fmt.bytes(e.size)) · \(Date(timeIntervalSince1970: TimeInterval(e.mtime)).formatted(.dateTime.month(.abbreviated).day().hour().minute()))")
                            .font(.cd(11)).foregroundColor(Aurora.textDim)
                    }
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button { explorer.toggleSelect(e) } label: {
                Circle().fill(selected ? Aurora.teal : Aurora.deepSpaceSoft)
                    .overlay(Circle().stroke(selected ? Aurora.teal : Aurora.glassEdge, lineWidth: 1))
                    .overlay(selected ? Image(systemName: "checkmark").font(.cd(11, weight: .bold)).foregroundColor(Aurora.deepSpace) : nil)
                    .frame(width: 24, height: 24)
                    .padding(8)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 6)
    }

    private var pinGate: some View {
        VStack(spacing: 8) {
            Text("🔒").font(.cd(30))
            Text("This drive is PIN-protected").font(.cd(16, weight: .semibold)).foregroundColor(Aurora.textPrimary)
            Text("the host shows a 4-digit code").font(.cd(12)).foregroundColor(Aurora.textDim)
            if state.pinFailed {
                Text("wrong PIN — try again").font(.cd(12)).foregroundColor(Aurora.danger).padding(.top, 4)
            }
            TextField("PIN", text: Binding(get: { pin }, set: { pin = String($0.filter(\.isNumber).prefix(4)) }))
                .keyboardType(.numberPad).multilineTextAlignment(.center)
                .font(.cd(24, weight: .semibold, design: .monospaced)).foregroundColor(Aurora.textPrimary)
                .frame(width: 160).padding(10)
                .background(Aurora.deepSpaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Aurora.glassEdge, lineWidth: 1))
                .padding(.top, 10)
            Button("Unlock") { explorer.unlock(pin); pin = "" }
                .buttonStyle(PrimaryButtonStyle()).frame(width: 160)
                .disabled(pin.count != 4).opacity(pin.count == 4 ? 1 : 0.4)
                .padding(.top, 6)
        }
    }

    // MARK: queue + selection

    @ViewBuilder private var jobsStrip: some View {
        let jobs = state.jobs.filter { !$0.done || $0.failed != nil }
        if !jobs.isEmpty {
            GlassCard(radius: 14) {
                VStack(spacing: 6) {
                    ForEach(jobs.prefix(3)) { j in
                        HStack(spacing: 8) {
                            Image(systemName: j.uploading ? "arrow.up" : "arrow.down").font(.cd(11, weight: .bold))
                                .foregroundColor(j.failed != nil ? Aurora.danger : Aurora.cyan)
                            Text(j.name).font(.cd(12)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                            Spacer()
                            Text(j.failed ?? "\(Fmt.bytes(j.received)) / \(Fmt.bytes(j.total))")
                                .font(.cd(11)).foregroundColor(j.failed != nil ? Aurora.danger : Aurora.textDim).lineLimit(1)
                        }
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
            .padding(.top, 10)
        }
    }

    @ViewBuilder private var selectionBar: some View {
        if !state.selected.isEmpty {
            HStack {
                Text("\(state.selected.count) selected").font(.cd(13)).foregroundColor(Aurora.textDim)
                Spacer()
                Button("Clear") { explorer.clearSelection() }.font(.cd(13)).foregroundColor(Aurora.textDim).padding(.trailing, 8)
                Button("Download") { explorer.download(Array(state.selected)); explorer.clearSelection() }
                    .buttonStyle(PrimaryButtonStyle()).fixedSize()
            }
            .padding(.top, 10)
        }
    }
}
