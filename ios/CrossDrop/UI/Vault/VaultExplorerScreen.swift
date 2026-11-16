import SwiftUI
import PhotosUI
import QuickLook
@preconcurrency import CrossDropCore

/// Observes the explorer directly (nested ObservableObjects don't propagate through AppContainer)
/// and slides the explorer over the app while a drive is mounted.
struct VaultExplorerHost: View {
    @ObservedObject var explorer: VaultExplorerManager

    var body: some View {
        ZStack {
            if let s = explorer.state {
                VaultExplorerScreen(state: s, explorer: explorer)
                    .background(CD.ink.ignoresSafeArea())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: explorer.state != nil)
    }
}

/// Native explorer for a mounted LAN drive: breadcrumb chips, folders as tiles, files in one grouped
/// list with per-file download, batch select, uploads into editable drives and Quick Look previews (the
/// file is pulled to a temp copy first). The back button walks up a folder, then closes.
struct VaultExplorerScreen: View {
    let state: VaultExplorerManager.Ui
    let explorer: VaultExplorerManager

    @State private var pin = ""
    @State private var selecting = false
    @State private var uploadMenu = false
    @State private var showFiles = false
    @State private var showPhotos = false
    @State private var photoSelection: [PhotosPickerItem] = []
    @FocusState private var pinFocused: Bool

    private var atRoot: Bool { state.path.isEmpty || state.pinRequired }

    var body: some View {
        let folders = state.entries.filter(\.isDir)
        let files = state.entries.filter { !$0.isDir }
        ZStack(alignment: .bottom) {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: CD.amber, alpha: 0.14, centerX: 0.9, centerY: 70, radius: 210)
            VStack(alignment: .leading, spacing: 0) {
                BackHeader(title: state.peerName, backLabel: atRoot ? "Close drive" : "Up one folder", titleSize: 20, onBack: back) {
                    HStack(spacing: 5) {
                        CdIconView(CdIcons.lock, color: CD.accent, size: 12)
                        Text("Encrypted · " + (state.pinRequired ? "PIN needed" : state.writable ? "you can add & edit" : "view & download"))
                            .body(12, CD.accent)
                    }
                    .accessibilityElement(children: .combine)
                } trailing: { EmptyView() }

                if state.pinRequired {
                    pinGate
                } else {
                    Crumbs(root: state.peerName, path: state.path) { explorer.browse($0) }.padding(.top, 14)
                    HStack(spacing: 8) {
                        if state.writable {
                            PillButton("Upload", height: 44, icon: CdIcons.arrowUp, fontSize: 14, fullWidth: false) { uploadMenu = true }
                        }
                        PillButton(selecting ? "Done" : "Select", style: .outline, height: 44, fontSize: 14, fullWidth: false) {
                            if selecting { explorer.clearSelection() }
                            selecting.toggle()
                        }
                    }
                    .padding(.top, 14)
                    content(folders: folders, files: files)
                        .padding(.top, 14)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .padding(.horizontal, 16).padding(.top, 16)
            .readableWidth(640)

            bottomPanel
        }
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
        .onChange(of: state.selected.isEmpty) { empty in if !empty { selecting = true } }
        .accessibilityAction(.escape) { back() }
    }

    /// Back walks up a folder before closing the drive.
    private func back() {
        if selecting { explorer.clearSelection(); selecting = false }
        else if !atRoot { explorer.up() }
        else { explorer.close() }
    }

    private func key(_ e: VaultDirEntry) -> String { state.path.isEmpty ? e.name : "\(state.path)/\(e.name)" }

    private func open(_ e: VaultDirEntry) {
        if e.isDir { explorer.browse(key(e)) }
        else if selecting { explorer.toggleSelect(e) }
        else if VaultExplorerManager.isPreviewable(e) { explorer.preview(key(e)) }
        else { explorer.download([key(e)]) }
    }

    // MARK: listing

    @ViewBuilder private func content(folders: [VaultDirEntry], files: [VaultDirEntry]) -> some View {
        if state.loading {
            VStack(spacing: 12) {
                ProgressView().tint(CD.accent).scaleEffect(1.2)
                Text("Reading the drive…").body(13, CD.textDim)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let err = state.error {
            VStack(spacing: 12) {
                Text(err).body(13, CD.dangerText).multilineTextAlignment(.center)
                PillButton("Try again", style: .glass, height: 44, fullWidth: false) { explorer.browse(state.path) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if state.entries.isEmpty {
            VStack(spacing: 4) {
                IconTile(icon: CdIcons.folderIcon, tint: CD.amber, size: 56, radius: 28, iconSize: 26)
                Text("This folder is empty").displayStyle(16).padding(.top, 8)
                if state.writable { Text("Upload something to fill it.").body(13, CD.textDim) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(Array(stride(from: 0, to: folders.count, by: 2)), id: \.self) { i in
                        HStack(spacing: 10) {
                            FolderTile(entry: folders[i]) { open(folders[i]) }
                            if i + 1 < folders.count { FolderTile(entry: folders[i + 1]) { open(folders[i + 1]) } }
                            else { Color.clear.frame(maxWidth: .infinity, maxHeight: 1) }
                        }
                    }
                    if !files.isEmpty {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(files.enumerated()), id: \.element.name) { i, e in
                                if i > 0 { RowSeparator() }
                                FileRow(entry: e, preview: VaultExplorerManager.isPreviewable(e), selecting: selecting,
                                        selected: state.selected.contains(key(e)),
                                        onOpen: { open(e) }, onDownload: { explorer.download([key(e)]) })
                            }
                        }
                        .padding(.horizontal, 6).padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(CD.glassLow))
                    }
                }
                .padding(.bottom, 160)
            }
        }
    }

    // MARK: bottom panel — batch download, then the transfer queue

    @ViewBuilder private var bottomPanel: some View {
        let jobs = state.jobs.filter { !$0.done || $0.failed != nil }
        if !state.selected.isEmpty || !jobs.isEmpty {
            GlassCard(radius: 30, fill: Color(hex: 0x181D24, alpha: 0.9), border: Color.white.opacity(0.1),
                      glow: Color.black.opacity(0.5), glowRadius: 50, glowOffsetY: -10, padding: .all(14), spacing: 12) {
                ForEach(jobs.prefix(3)) { j in JobRow(job: j) }
                if !jobs.filter({ $0.failed != nil }).isEmpty && state.selected.isEmpty {
                    ChipButton(text: "Clear finished", height: 32, fontSize: 12) { explorer.clearFinishedJobs() }
                }
                if !state.selected.isEmpty {
                    HStack {
                        Text("\(state.selected.count) selected").body(14, CD.textSecondary, weight: .semibold).padding(.leading, 4)
                        Spacer()
                        PillButton("Download", height: 48, icon: CdIcons.download, glow: true, fullWidth: false) {
                            explorer.download(Array(state.selected))
                            explorer.clearSelection()
                            selecting = false
                        }
                    }
                }
            }
            .frame(maxWidth: 640)
            .padding(.horizontal, 12).padding(.bottom, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: PIN

    private var pinGate: some View {
        VStack(spacing: 0) {
            CdIconView(CdIcons.lock, color: CD.amber, size: 30)
                .frame(width: 72, height: 72)
                .background(
                    ZStack {
                        Circle().fill(CD.ink).shadow(color: CD.amber.opacity(0.18), radius: 20)
                        Circle().fill(CD.amber.opacity(0.14))
                    }
                )
            Text("This drive needs a passcode").displayStyle(22).multilineTextAlignment(.center).padding(.top, 18)
                .accessibilityAddTraits(.isHeader)
            Text("It's the 4 digits shown on the device sharing it.").body(13, CD.textDim).multilineTextAlignment(.center).padding(.top, 6)
            if state.pinFailed {
                Text("That passcode didn't work — try again").body(13, CD.dangerText).padding(.top, 8)
            }
            TextField("", text: Binding(get: { pin }, set: { pin = String($0.filter(\.isNumber).prefix(4)) }))
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .multilineTextAlignment(.center)
                .font(.mono(26)).tracking(CDFont.scaled(26) * 0.4)
                .foregroundColor(CD.textPrimary)
                .tint(CD.accent)
                .focused($pinFocused)
                .frame(width: 200, height: 60)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(CD.glassLow))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(pinFocused ? CD.accent : CD.hairlineStrong, lineWidth: pinFocused ? 1.5 : 1))
                .accessibilityLabel("Passcode")
                .onSubmit(unlock)
                .padding(.top, 22)
            PillButton("Unlock", glow: true, fullWidth: false, action: unlock)
                .frame(width: 200)
                .disabled(pin.count != 4)
                .padding(.top, 18)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, 60)
        .onAppear { pinFocused = true }
    }

    private func unlock() {
        guard pin.count == 4 else { return }
        explorer.unlock(pin)
        pin = ""
    }
}

private struct Crumbs: View {
    let root: String
    let path: String
    let onBrowse: (String) -> Void

    var body: some View {
        let parts = path.split(separator: "/").map(String.init)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Crumb(label: root, current: parts.isEmpty) { onBrowse("") }
                ForEach(Array(parts.enumerated()), id: \.offset) { i, seg in
                    CdIconView(CdIcons.chevronRight, color: CD.textFaint, size: 14)
                    Crumb(label: seg, current: i == parts.count - 1) { onBrowse(parts[0...i].joined(separator: "/")) }
                }
            }
        }
    }
}

private struct Crumb: View {
    let label: String
    let current: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label).font(.cd(13, weight: current ? .semibold : .regular))
                .foregroundColor(current ? CD.ink : CD.textSecondary).lineLimit(1)
                .padding(.horizontal, 12).frame(height: 32)
                .background(Capsule().fill(current ? CD.textPrimary : CD.glassMid))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .disabled(current)
        .accessibilityAddTraits(current ? [.isSelected] : [])
    }
}

private struct FolderTile: View {
    let entry: VaultDirEntry
    let action: () -> Void
    var body: some View {
        GlassCard(radius: 22, fill: CD.glass, padding: .all(14), spacing: 12, action: action, actionLabel: "Opens the folder") {
            CdIconView(CdIcons.folderIcon, color: CD.amber, size: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).body(14, weight: .semibold).lineLimit(1)
                Text("Folder").body(12, CD.textDim)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct FileRow: View {
    let entry: VaultDirEntry
    let preview: Bool
    let selecting: Bool
    let selected: Bool
    let onOpen: () -> Void
    let onDownload: () -> Void

    var body: some View {
        let kind = FileKind.of(entry.name)
        HStack(spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    if preview && kind == .video {
                        IconTile(icon: CdIcons.play, tint: CD.violetText, bg: CD.violet.opacity(0.16), size: 44)
                    } else {
                        FileTypeTile(fileName: entry.name, size: 44)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.name).body(14, weight: .semibold).lineLimit(1).truncationMode(.middle)
                        Text(Fmt.bytes(entry.size) + " · " + (preview ? "tap to preview"
                             : Date(timeIntervalSince1970: TimeInterval(entry.mtime)).formatted(.dateTime.month(.abbreviated).day().hour().minute())))
                            .body(12, CD.textDim).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                }
                .padding(.vertical, 9).padding(.leading, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint(selecting ? "Selects the file" : preview ? "Previews the file" : "Downloads to your inbox")
            .accessibilityAddTraits(selecting && selected ? [.isSelected] : [])

            if selecting {
                Button(action: onOpen) {
                    ZStack {
                        Circle().fill(selected ? CD.accent : .clear)
                        Circle().strokeBorder(selected ? CD.accent : CD.hairlineStrong, lineWidth: 1.5)
                        if selected { CdIconView(CdIcons.check, color: CD.onAccent, size: 14) }
                    }
                    .frame(width: 24, height: 24)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(selected ? "Selected" : "Not selected")
            } else {
                CircleButton(icon: CdIcons.download, label: "Download \(entry.name)", fill: .clear, tint: CD.textSecondary, action: onDownload)
            }
        }
    }
}

private struct JobRow: View {
    let job: VaultExplorerManager.Job
    var body: some View {
        let frac = job.total > 0 ? min(1, max(0, Double(job.received) / Double(job.total))) : 0
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                CdIconView(job.uploading ? CdIcons.arrowUp : CdIcons.arrowDown, color: job.failed != nil ? CD.dangerText : CD.accent, size: 18)
                    .frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(hex: 0x1F2A2F)))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(CD.accent.opacity(0.35), lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text(job.name).body(14, weight: .semibold).lineLimit(1).truncationMode(.middle)
                    Text(job.failed ?? (job.uploading ? "Uploading" : "Saving to inbox"))
                        .body(12, job.failed != nil ? CD.dangerText : CD.textDim).lineLimit(1)
                }
                Spacer(minLength: 4)
                if job.failed == nil {
                    Text("\(Fmt.bytes(job.received)) / \(Fmt.bytes(job.total))").monoStyle(11, color: CD.textDim)
                }
            }
            if job.failed == nil { ProgressBar(fraction: frac, height: 4, track: Color.white.opacity(0.12)) }
        }
        .accessibilityElement(children: .combine)
    }
}
