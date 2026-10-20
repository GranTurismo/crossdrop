import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
@preconcurrency import CrossDropCore

/// A photo/video exported by the system picker into a private temp file.
struct PickedMedia: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .item) { received in
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let dest = dir.appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMedia(url: dest)
        }
    }
}

struct RadarScreen: View {
    @EnvironmentObject var container: AppContainer
    @EnvironmentObject var coordinator: TransferCoordinator

    @State private var chooserPeer: Peer?
    @State private var vaultPeer: Peer?
    @State private var targetPeer: Peer?
    /// "to anyone": the chooser's result feeds the QR share instead of a peer send.
    @State private var chooserAnyone = false
    @State private var targetAnyone = false
    @State private var showFiles = false
    @State private var showPhotos = false
    @State private var photoSelection: [PhotosPickerItem] = []
    @State private var renaming = false
    @State private var loadingMedia = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if let notice = container.networkNotice { noticeBanner(notice) }
            if !container.pendingShare.isEmpty { pendingBanner }
            ZStack(alignment: .bottom) {
                RadarCanvas(selfName: container.deviceName, peers: container.peers) { peer in
                    guard peer.isReachable else { return }
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    startSend(peer)
                }
                Text(container.peers.isEmpty ? "Listening for nearby devices…"
                     : "\(container.peers.count) device\(container.peers.count == 1 ? "" : "s") nearby — tap to send")
                    .font(.cd(13)).foregroundColor(container.peers.isEmpty ? Aurora.textDim : Aurora.phosphor)
                    .padding(.bottom, 24)
                if loadingMedia {
                    ProgressView("Preparing…").tint(Aurora.teal).foregroundColor(Aurora.textDim)
                        .padding(14).background(Aurora.deepSpaceSoft, in: RoundedRectangle(cornerRadius: 14))
                        .padding(.bottom, 70)
                }
            }
            peerStrip
        }
        .background(Aurora.deepSpace)
        .sheet(item: $chooserPeer) { peer in
            SendChooserSheet(title: "Send to \(peer.name)", subtitle: "pick what to share",
                             onPickMedia: { present(peer) { showPhotos = true } },
                             onPickFiles: { present(peer) { showFiles = true } })
                .presentationDetents([.height(320)])
        }
        #if DEBUG
        // `-dev_vault_sheet YES`: raise the mount sheet for the first vault beacon (no tap tool in the simulator).
        .onChange(of: container.peers) { peers in
            if UserDefaults.standard.bool(forKey: "dev_vault_sheet"), vaultPeer == nil, let v = peers.first(where: { $0.vault != nil }) { vaultPeer = v }
        }
        #endif
        .sheet(item: $vaultPeer) { peer in
            VaultMountSheet(peer: peer) { vaultPeer = nil; container.vaultExplorer.mount(peer) }
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $chooserAnyone) {
            SendChooserSheet(title: "Send to anyone", subtitle: "they'll scan a QR — no app needed",
                             onPickMedia: { presentAnyone { showPhotos = true } },
                             onPickFiles: { presentAnyone { showFiles = true } })
                .presentationDetents([.height(320)])
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            let anyone = targetAnyone, peer = targetPeer
            targetAnyone = false; targetPeer = nil                 // a cancelled picker must not leave a stale target
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            if anyone { container.qrShare.start(urls: urls) }
            else if let peer { coordinator.sendTo(peer, urls: urls) }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photoSelection, maxSelectionCount: nil, matching: .any(of: [.images, .videos]))
        .onChange(of: photoSelection) { items in
            guard !items.isEmpty, targetAnyone || targetPeer != nil else { return }
            let peer = targetPeer
            let anyone = targetAnyone
            targetAnyone = false; targetPeer = nil
            photoSelection = []
            loadingMedia = true
            Task {
                var urls: [URL] = []
                for item in items {
                    if let m = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(m.url) }
                }
                loadingMedia = false
                guard !urls.isEmpty else {
                    container.networkNotice = "Couldn't read the selected photos or videos."
                    return
                }
                let temp = urls.map { $0.deletingLastPathComponent() }
                if anyone { container.qrShare.start(urls: urls, temporary: temp) }
                else if let peer { coordinator.sendTo(peer, urls: urls, temporary: temp) }
            }
        }
        .sheet(isPresented: $renaming) {
            DeviceNameSheet(current: container.deviceName) { new in
                renaming = false
                if let new { container.setDeviceName(new) }
            }.presentationDetents([.height(260)])
        }
        .onChange(of: container.sendRequest) { _ in
            // ⌘O: first reachable device, or a QR share when nobody is around.
            if let peer = container.peers.first(where: { $0.isReachable && $0.vault == nil }) { startSend(peer) }
            else { chooserAnyone = true }
        }
        .alert("Can't share", isPresented: Binding(get: { container.qrShare.failure != nil }, set: { if !$0 { container.qrShare.failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(container.qrShare.failure ?? "") }
    }

    /// Dismiss the chooser first, then raise the system picker (two modal presentations can't overlap).
    private func present(_ peer: Peer, _ action: @escaping () -> Void) {
        targetPeer = peer
        targetAnyone = false
        chooserPeer = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: action)
    }

    private func presentAnyone(_ action: @escaping () -> Void) {
        targetAnyone = true
        chooserAnyone = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: action)
    }

    /// Files handed to us from another app ("Open in CrossDrop") wait for a tap on a device.
    private func startSend(_ peer: Peer) {
        if peer.vault != nil { vaultPeer = peer; return }      // beacon → mount sheet, not the send chooser
        if !container.pendingShare.isEmpty {
            let taken = container.takePendingShare()
            coordinator.sendTo(peer, urls: taken.urls, temporary: taken.temporary)
        } else {
            chooserPeer = peer
        }
    }

    private var pendingBanner: some View {
        let n = container.pendingShare.count
        return HStack(spacing: 10) {
            Image(systemName: "paperplane.fill").foregroundColor(Aurora.teal)
            VStack(alignment: .leading, spacing: 1) {
                Text("\(n) file\(n == 1 ? "" : "s") ready").font(.cd(13, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                Text("tap a device to send").font(.cd(12)).foregroundColor(Aurora.textDim)
            }
            Spacer()
            Button("to anyone") {
                let taken = container.takePendingShare()
                container.qrShare.start(urls: taken.urls, temporary: taken.temporary)
            }.font(.cd(13, weight: .medium)).foregroundColor(Aurora.teal).pointerHover()
            Button { container.clearPendingShare() } label: {
                Image(systemName: "xmark").font(.cd(12, weight: .semibold)).foregroundColor(Aurora.danger)
                    .frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel("Discard waiting files")
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Aurora.deepSpaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Aurora.teal.opacity(0.5), lineWidth: 1))
        .padding(.horizontal, 16).padding(.bottom, 4)
    }

    private var topBar: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text("CrossDrop").font(.cd(24, weight: .heavy)).tracking(0.5)
                    .foregroundStyle(Aurora.gradient)
                Button { renaming = true } label: {
                    HStack(spacing: 6) {
                        Text("●").font(.cd(7)).foregroundColor(Aurora.teal)
                        Text(container.deviceName + (container.listenerPort > 0 ? "  :\(container.listenerPort)" : ""))
                            .font(.cd(11)).foregroundColor(Aurora.textDim)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Aurora.deepSpaceSoft, in: Capsule())
                    .overlay(Capsule().stroke(Aurora.glassEdge, lineWidth: 1))
                }
                .buttonStyle(.plain).pointerHover()
            }
            Spacer()
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                chooserAnyone = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "qrcode").font(.cd(16, weight: .medium)).foregroundColor(Aurora.teal)
                    Text("to anyone").font(.cd(13, weight: .medium)).foregroundColor(Aurora.textPrimary)
                }
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Aurora.deepSpaceSoft, in: Capsule())
                .overlay(Capsule().stroke(Aurora.teal.opacity(0.4), lineWidth: 1))
            }
            .buttonStyle(.plain).pointerHover()
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    private func noticeBanner(_ text: String) -> some View {
        HStack {
            Text(text).font(.cd(12)).foregroundColor(Aurora.textDim)
            Spacer()
            Button("Settings") { openSystemSettings() }
                .font(.cd(13, weight: .medium)).foregroundColor(Aurora.teal)
            Button { container.networkNotice = nil } label: {
                Image(systemName: "xmark").font(.cd(12, weight: .semibold)).foregroundColor(Aurora.textDim)
                    .frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Aurora.deepSpaceSoft, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16).padding(.bottom, 4)
    }

    @ViewBuilder private var peerStrip: some View {
        if container.peers.isEmpty {
            Text("Tap a device to send files").font(.cd(13)).foregroundColor(Aurora.textDim).padding(.vertical, 18)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(container.peers.prefix(8)) { peer in
                        Button { if peer.isReachable { startSend(peer) } } label: {
                            HStack(spacing: 8) {
                                Text("●").font(.cd(8)).foregroundColor(peer.isReachable ? Aurora.teal : Aurora.textDim)
                                Text(peer.name).font(.cd(13, weight: .medium)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                                if peer.isReachable {
                                    Text(peer.vault != nil ? "vault" : "send").font(.cd(12))
                                        .foregroundColor(peer.vault != nil ? Aurora.violet : Aurora.teal)
                                }
                            }
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .background(Aurora.deepSpaceRaised, in: Capsule())
                            .overlay(Capsule().stroke(peer.isReachable ? Aurora.teal.opacity(0.5) : Aurora.glassEdge, lineWidth: 1))
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 14)
        }
    }
}

struct SendChooserSheet: View {
    let title: String
    let subtitle: String
    let onPickMedia: () -> Void
    let onPickFiles: () -> Void

    var body: some View {
        ZStack {
            Aurora.deepSpaceSoft.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.cd(18, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                Text(subtitle).font(.cd(12)).foregroundColor(Aurora.textDim).padding(.bottom, 6)
                PickerOption(icon: "photo.on.rectangle", tint: Aurora.teal, title: "Photos & videos",
                             subtitle: "your photo library", action: onPickMedia)
                PickerOption(icon: "doc", tint: Aurora.cyan, title: "Files",
                             subtitle: "documents, archives, everything else", action: onPickFiles)
                Spacer(minLength: 0)
            }
            .padding(24)
        }
    }
}

struct PickerOption: View {
    let icon: String
    let tint: Color
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            GlassCard(radius: 16) {
                HStack(spacing: 14) {
                    Image(systemName: icon).font(.cd(22)).foregroundColor(tint).frame(width: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.cd(15, weight: .medium)).foregroundColor(Aurora.textPrimary)
                        Text(subtitle).font(.cd(12)).foregroundColor(Aurora.textDim)
                    }
                    Spacer()
                    Text("›").font(.cd(18)).foregroundColor(Aurora.textDim)
                }
                .padding(14)
            }
        }
        .buttonStyle(.plain).pointerHover()
    }
}

struct DeviceNameSheet: View {
    let current: String
    let onDone: (String?) -> Void
    @State private var text: String

    init(current: String, onDone: @escaping (String?) -> Void) {
        self.current = current; self.onDone = onDone
        _text = State(initialValue: current)
    }

    var body: some View {
        ZStack {
            Aurora.deepSpaceSoft.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 12) {
                Text("Device name").font(.cd(18, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                Text("how other devices see this one").font(.cd(12)).foregroundColor(Aurora.textDim)
                TextField("Name", text: $text)
                    .textFieldStyle(.plain).padding(12).foregroundColor(Aurora.textPrimary)
                    .background(Aurora.deepSpaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Aurora.glassEdge, lineWidth: 1))
                    .submitLabel(.done).onSubmit { onDone(text) }
                HStack(spacing: 12) {
                    Button("Cancel") { onDone(nil) }.buttonStyle(OutlineButtonStyle(color: Aurora.textDim))
                    Button("Save") { onDone(text) }.buttonStyle(PrimaryButtonStyle())
                }
                Spacer(minLength: 0)
            }
            .padding(24)
        }
    }
}
