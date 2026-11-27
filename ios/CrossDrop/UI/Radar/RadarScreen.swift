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

extension Platform {
    /// This device's own glyph (the radar's centre node, the transfer endpoints, Settings).
    static var deviceIcon: CdIcon {
        #if targetEnvironment(macCatalyst)
        return CdIcons.laptop
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? CdIcons.tablet : CdIcons.phone
        #endif
    }
}

/// Home tab: brand + network chip, the sonar with every nearby device, then "Send to anyone" and any
/// wireless drives on the network. Tapping a device opens the send sheet (photo picker or files); a
/// drive opens its own sheet.
struct RadarScreen: View {
    @EnvironmentObject var container: AppContainer
    @EnvironmentObject var coordinator: TransferCoordinator
    @EnvironmentObject var sheets: SheetHost
    @Environment(\.navInset) private var navInset
    var active = true
    /// Something the shell shows sits over the radar (transfer, offer, sheet, guide…).
    var covered = false
    var onShowConnectGuide: () -> Void = {}
    /// "Scan a code" (iPhone/iPad): join another phone's private link. Nil where the device can't (Mac).
    var onScanCode: (() -> Void)? = nil
    /// "Join a CrossDrop Wi-Fi" (one tap onto an Android phone's network); nil where this device can't join from the app.
    var onJoinWifi: (() -> Void)? = nil
    @StateObject private var connectHint = ConnectHintModel()
    @State private var pickingLanguage = I18n.devFlag("dev_language_sheet")

    @State private var targetPeers: [Peer] = []
    /// "to anyone": the picker's result feeds the QR share instead of a peer send.
    @State private var targetAnyone = false
    @State private var showFiles = false
    @State private var showPhotos = false
    @State private var photoSelection: [PhotosPickerItem] = []
    @State private var renaming = false
    @State private var renameText = ""
    @State private var loadingMedia = false
    /// The animated "Shake to connect" card is up (see `shakeHintDue`).
    @State private var shakeHintVisible = false

    var body: some View {
        let devices = container.peers.filter { $0.vault == nil }
        let drives = container.peers.filter { $0.vault != nil && $0.isReachable }
        let reachableCount = devices.filter(\.isReachable).count
        ZStack {
            VStack(spacing: 0) {
                header(devices.count).padding(.horizontal, 18).padding(.top, 22)
                if let notice = container.networkNotice { noticeCard(notice).padding(.horizontal, 16).padding(.top, 14) }
                if !container.pendingShare.isEmpty { pendingCard.padding(.horizontal, 16).padding(.top, 10) }

                ZStack(alignment: .bottom) {
                    RadarCanvas(selfName: container.deviceName, selfIcon: Platform.deviceIcon, peers: devices, active: active,
                                onPeerTap: { startSendFlow($0) }, onSelfTap: { renameText = container.deviceName; renaming = true })
                    if coordinator.shakeWaiting {
                        Text(tr("Now shake the other phone at the same time")).body(13, CD.accent, weight: .semibold)
                            .multilineTextAlignment(.center).padding(.bottom, 6).padding(.horizontal, 16)
                            .transition(.opacity)
                    } else if devices.isEmpty && connectHint.hint == .none {
                        Text(tr("Open CrossDrop on another device on this Wi-Fi")).body(12, CD.textDim)
                            .multilineTextAlignment(.center).padding(.bottom, 6).padding(.horizontal, 16)
                    } else if container.shakeEnabled && ShakeSensor.supported && !shakeHintVisible && reachableCount >= 2 {
                        // The quiet hint; the animated card says it while it is up.
                        Text(tr("Several devices? Shake both phones to find each other.")).body(12, CD.textDim)
                            .multilineTextAlignment(.center).padding(.bottom, 6).padding(.horizontal, 16)
                            .transition(.opacity)
                    }
                }
                .animation(.easeInOut(duration: 0.25), value: coordinator.shakeWaiting)
                .frame(maxWidth: .infinity, minHeight: connectHint.hint == .none ? 220 : 120, maxHeight: .infinity)
                .padding(.top, 8)

                VStack(spacing: 10) {
                    if shakeHintVisible && reachableCount >= 2 {
                        ShakeHintCard { withAnimation(.easeInOut(duration: 0.3)) { shakeHintVisible = false } }
                            .transition(.asymmetric(insertion: .offset(y: 36).combined(with: .opacity), removal: .opacity))
                    }
                    ConnectHelpGate(join: container.wifiJoin, hint: connectHint.hint, onGuide: onShowConnectGuide,
                                    onScan: onScanCode, onJoinWifi: onJoinWifi)
                    CrossDropWifiStatusRow(join: container.wifiJoin, onScan: { onScanCode?() })
                    JoinedLinkRow(join: container.wifiJoin)
                    #if !targetEnvironment(macCatalyst)
                    ClipboardShareCard(share: container.pasteboard, pairings: container.pairings)
                    #endif
                    SendToAnyoneCard { startAnyoneFlow() }
                    ForEach(drives.prefix(2)) { d in DriveRow(peer: d) { startSendFlow(d) } }
                }
                .animation(.easeInOut(duration: 0.3), value: shakeHintVisible && reachableCount >= 2)
                .frame(maxWidth: 560)
                .padding(.horizontal, 16).padding(.top, 4)
                Spacer().frame(height: navInset)
            }
            if loadingMedia {
                HStack(spacing: 10) {
                    ProgressView().tint(CD.accent)
                    Text(tr("Preparing…")).body(14, CD.textSecondary)
                }
                .padding(.horizontal, 18).padding(.vertical, 14)
                .background(Capsule().fill(CD.inkSheet))
                .overlay(Capsule().strokeBorder(CD.hairline, lineWidth: 1))
            }
        }
        .background(CD.ink)
        .background(ShakeWatch(visible: active && !covered && !showFiles && !showPhotos && !pickingLanguage && !renaming && !loadingMedia,
                               qrShare: container.qrShare, explorer: container.vaultExplorer, importer: container.switchImporter,
                               onChange: container.setRadarUncovered))
        // Shook phones with someone: their send sheet, once they're on the radar (a SHAKE can land a moment before the
        // device does: it may only now be reachable, or still being checked).
        .onChange(of: coordinator.shakeMatch) { _ in openShakeMatch() }
        .onChange(of: container.peers) { _ in openShakeMatch() }
        // Teach shaking where it helps: once per launch, when several reachable devices show up, until it has worked
        // once. A tap, 12 s or a shake hides it.
        .task(id: shakeHintDue(reachableCount)) {
            guard shakeHintDue(reachableCount), !container.shakeHintShown else { return }
            try? await Task.sleep(nanoseconds: 800_000_000)        // let the new nodes land on the radar first
            guard !Task.isCancelled else { return }
            container.shakeHintShown = true
            withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { shakeHintVisible = true }
        }
        .task(id: shakeHintVisible) {
            guard shakeHintVisible else { return }
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.3)) { shakeHintVisible = false }
        }
        .onChange(of: coordinator.shakeWaiting) { waiting in
            if waiting { withAnimation(.easeInOut(duration: 0.3)) { shakeHintVisible = false } }
        }
        .sheet(isPresented: $pickingLanguage) { LanguageSheet() }
        .onAppear { connectHint.update(hasNetwork: container.hasNetwork, deviceCount: devices.count) }
        .onChange(of: devices.count) { n in connectHint.update(hasNetwork: container.hasNetwork, deviceCount: n) }
        .onChange(of: container.hasNetwork) { has in connectHint.update(hasNetwork: has, deviceCount: devices.count) }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            let anyone = targetAnyone, peers = targetPeers
            targetAnyone = false; targetPeers = []                 // a cancelled picker must not leave a stale target
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            if anyone { container.qrShare.start(urls: urls) }
            else if !peers.isEmpty { coordinator.sendTo(peers, urls: urls) }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photoSelection, maxSelectionCount: nil, matching: .any(of: [.images, .videos]))
        .onChange(of: photoSelection) { items in
            guard !items.isEmpty, targetAnyone || !targetPeers.isEmpty else { return }
            let peers = targetPeers
            let anyone = targetAnyone
            targetAnyone = false; targetPeers = []
            photoSelection = []
            loadingMedia = true
            Task {
                var urls: [URL] = []
                for item in items {
                    if let m = try? await item.loadTransferable(type: PickedMedia.self) { urls.append(m.url) }
                }
                loadingMedia = false
                guard !urls.isEmpty else {
                    container.networkNotice = tr("Couldn't read the selected photos or videos.")
                    return
                }
                let temp = urls.map { $0.deletingLastPathComponent() }
                if anyone { container.qrShare.start(urls: urls, temporary: temp) }
                else if !peers.isEmpty { coordinator.sendTo(peers, urls: urls, temporary: temp) }
            }
        }
        .renameDialog(isPresented: $renaming, text: $renameText) { container.setDeviceName($0) }
        .onChange(of: container.sendRequest) { _ in
            // ⌘O: first reachable device, or a QR share when nobody is around.
            if let peer = container.peers.first(where: { $0.isReachable && $0.vault == nil }) { startSendFlow(peer) }
            else { startAnyoneFlow() }
        }
        #if DEBUG
        // `-dev_vault_sheet YES`: raise the drive sheet for the first vault beacon (no tap tool in the simulator).
        .onChange(of: container.peers) { peers in
            if UserDefaults.standard.bool(forKey: "dev_vault_sheet"), sheets.item == nil,
               let v = peers.first(where: { $0.vault != nil }) {
                UserDefaults.standard.set(false, forKey: "dev_vault_sheet")
                startSendFlow(v)
            }
        }
        // `-dev_sheet peer|anyone`: raise the send sheet for screenshots.
        .onAppear {
            guard let which = UserDefaults.standard.string(forKey: "dev_sheet") else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                if which == "anyone" { startAnyoneFlow() }
                else if let p = container.peers.first(where: { $0.vault == nil && $0.isReachable }) { startSendFlow(p) }
            }
        }
        #endif
        .alert(tr("Can't share"), isPresented: Binding(get: { container.qrShare.failure != nil }, set: { if !$0 { container.qrShare.failure = nil } })) {
            Button(tr("OK"), role: .cancel) {}
        } message: { Text(container.qrShare.failure ?? "") }
    }

    // MARK: flows

    /// We shook phones with a device: the same sheet as a tap, introduced as the shake. Only while the radar is what the
    /// person is looking at; a match whose device isn't on the radar waits here until it appears (or goes stale).
    private func openShakeMatch() {
        guard let m = coordinator.shakeMatch else { return }
        guard container.radarUncovered else { coordinator.shakeHandled(); return }
        guard let peer = container.peers.first(where: { $0.instanceId == m.instanceId && $0.vault == nil && $0.isReachable }) else { return }
        coordinator.shakeHandled()
        container.markShakeLearned()
        shakeHintVisible = false
        startSendFlow(peer, shook: true)
    }

    /// Several reachable devices, shaking on (and possible here), and it has never found anyone on this device. Only on
    /// the radar tab: this view stays mounted (hidden) behind the others, and the card gets one showing per launch.
    private func shakeHintDue(_ reachableCount: Int) -> Bool {
        active && reachableCount >= 2 && container.shakeEnabled && ShakeSensor.supported && !container.shakeLearned
    }

    /// Device tap (or a shake) → send sheet (or straight away for files handed over by another app); a drive → its sheet.
    private func startSendFlow(_ peer: Peer, shook: Bool = false) {
        guard peer.isReachable else { return }
        if shook { UINotificationFeedbackGenerator().notificationOccurred(.success) }
        else { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
        if peer.vault != nil {
            sheets.present {
                VaultMountSheet(peer: peer,
                                onMount: { sheets.dismiss(notify: false); container.vaultExplorer.mount(peer) },
                                onCancel: { sheets.dismiss() })
            }
            return
        }
        if !container.pendingShare.isEmpty {
            let taken = container.takePendingShare()
            coordinator.sendTo(peer, urls: taken.urls, temporary: taken.temporary)
            return
        }
        let others = container.peers.filter { $0.vault == nil && $0.isReachable && $0.id != peer.id }
        sheets.present {
            SendSheet(kicker: shook ? tr("You shook phones with") : tr("Send to"), title: peer.name, icon: deviceIconFor(peer.name),
                      meta: MetaLine(lead: nil, rest: peer.sources.contains(.manual) ? tr("Added by address · Wi-Fi") : tr("Wi-Fi · this network")),
                      note: tr("Straight to this device, end-to-end encrypted, never through a server. You'll both see a 6-digit code to compare."),
                      primary: peer, others: others,
                      onPickMedia: { t in pick(peers: t) { showPhotos = true } },
                      onPickFiles: { t in pick(peers: t) { showFiles = true } },
                      onCamera: { t in sheets.dismiss(notify: false); container.cameraTargets = t },
                      onText: { t in
                          sheets.dismiss(notify: false)
                          DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                              sheets.present {
                                  TextComposeSheet(target: t.count == 1 ? t[0].name : trPlural(t.count, one: "1 device", other: "{n} devices"),
                                                   initial: UIPasteboard.general.hasStrings ? (UIPasteboard.general.string ?? "") : "") { text in
                                      sheets.dismiss()
                                      if let text, !text.isEmpty { coordinator.sendText(t, text: text) }
                                  }
                              }
                          }
                      },
                      onContacts: { t in
                          sheets.dismiss(notify: false)
                          // Let the sheet go first; the system picker presents over the window.
                          DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                              ContactPickerPresenter.present { picked in
                                  guard !picked.isEmpty else { return }
                                  let vcf = picked.map { ContactsBridge.vcard($0) }.joined()
                                  let names = ContactPreview.parse(ContactCards.preview(vcf)).map(\.name)
                                  coordinator.sendContacts(t, vcf: vcf, names: names.isEmpty ? [tr("Contact")] : names)
                              }
                          }
                      },
                      onSwitch: { sheets.dismiss(notify: false); container.switchSender.close(); container.switchTarget = peer },
                      onCancel: { sheets.dismiss() })
        }
    }

    /// "Send to anyone" → same sheet, but the result feeds the QR share.
    private func startAnyoneFlow() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        if !container.pendingShare.isEmpty {
            let taken = container.takePendingShare()
            container.qrShare.start(urls: taken.urls, temporary: taken.temporary)
            return
        }
        sheets.present {
            SendSheet(kicker: tr("Share with"), title: tr("Anyone nearby"), icon: CdIcons.qr,
                      meta: MetaLine(lead: nil, rest: tr("No app needed — they scan a QR code")),
                      note: tr("They need to be on this Wi-Fi. The link is secret, and it stops working the moment you close the share screen."),
                      onPickMedia: { _ in pick(peers: []) { showPhotos = true } },
                      onPickFiles: { _ in pick(peers: []) { showFiles = true } },
                      onCancel: { sheets.dismiss() })
        }
    }

    private func pick(peers: [Peer], _ open: @escaping () -> Void) {
        targetPeers = peers
        targetAnyone = peers.isEmpty
        sheets.dismiss(notify: false)
        // Let the sheet's exit animation start before the system picker slides in.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: open)
    }

    // MARK: header

    private func header(_ count: Int) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 8) {
                BrandMark()
                Text("CrossDrop").displayStyle(16, tracking: -0.01)
                Spacer(minLength: 8)
                GlassChip(text: chipText, dot: container.discoveryEnabled ? CD.accent : CD.textDim)
                LanguageButton { pickingLanguage = true }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(tr("Radar")).displayStyle(36, tracking: -0.04).accessibilityAddTraits(.isHeader)
                Text(count == 0 ? tr("Looking for devices…") : trPlural(count, one: "1 device nearby", other: "{n} devices nearby"))
                    .body(14, CD.textDim)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var chipText: String {
        var t = container.discoveryEnabled ? tr("Wi-Fi · local only") : tr("Wi-Fi discovery off")
        #if DEBUG
        if container.listenerPort > 0 { t += " · :\(container.listenerPort)" }
        #endif
        return t
    }

    private func noticeCard(_ text: String) -> some View {
        HStack(spacing: 10) {
            CdIconView(CdIcons.wifi, color: CD.amber, size: 18)
            Text(text).body(12, CD.textSecondary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            ChipButton(text: tr("Settings"), fg: CD.amber, bg: CD.amber.opacity(0.1), border: CD.amber.opacity(0.35), height: 32, fontSize: 12) {
                openSystemSettings()
            }
            CircleButton(icon: CdIcons.close, label: tr("Dismiss"), size: 32, iconSize: 14, fill: .clear, tint: CD.textDim) {
                container.networkNotice = nil
            }
        }
        .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(CD.amber.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CD.amber.opacity(0.25), lineWidth: 1))
        .frame(maxWidth: 560)
    }

    /// Files handed to us by another app ("Open in CrossDrop", drag & drop) wait for a device tap.
    private var pendingCard: some View {
        let n = container.pendingShare.count
        return HStack(spacing: 12) {
            IconTile(icon: CdIcons.arrowUp, tint: CD.accent, size: 40, radius: 14, iconSize: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(trPlural(n, one: "1 file ready", other: "{n} files ready")).body(14, weight: .semibold)
                Text(tr("Tap a device to send")).body(12, CD.textDim)
            }
            Spacer(minLength: 4)
            ChipButton(text: tr("To anyone"), icon: CdIcons.qr, fg: CD.onAccent, bg: CD.accent, height: 36) {
                let taken = container.takePendingShare()
                container.qrShare.start(urls: taken.urls, temporary: taken.temporary)
            }
            CircleButton(icon: CdIcons.close, label: tr("Discard waiting files"), size: 36, iconSize: 14, fill: CD.glassHigh, tint: CD.textSecondary) {
                container.clearPendingShare()
            }
        }
        .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(CD.glassMid))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(CD.accent.opacity(0.4), lineWidth: 1))
        .frame(maxWidth: 560)
    }
}

/// Tells the container whether the radar is what the person is looking at: the app in front, the radar tab with nothing
/// over it — including the full-screen hosts that observe their own models (QR share, mounted drive, switch-phones
/// receive), which nothing above them observes. Shake to connect listens only then.
private struct ShakeWatch: View {
    let visible: Bool
    @ObservedObject var qrShare: QrShareManager
    @ObservedObject var explorer: VaultExplorerManager
    @ObservedObject var importer: SwitchImporter
    let onChange: (Bool) -> Void
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let uncovered = visible && scenePhase == .active && qrShare.state == nil && explorer.state == nil && importer.state == nil
        Color.clear
            .accessibilityHidden(true)
            .onAppear { onChange(uncovered) }
            .onChange(of: uncovered) { onChange($0) }
            .onDisappear { onChange(false) }
    }
}

private struct BrandMark: View {
    var body: some View {
        ZStack {
            Circle().strokeBorder(CD.accent, lineWidth: 2).frame(width: 24, height: 24)
            Circle().fill(CD.accent).frame(width: 8, height: 8)
        }
        .accessibilityHidden(true)
    }
}

private struct SendToAnyoneCard: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                IconTile(icon: CdIcons.qr, tint: CD.accent, bg: CD.accent.opacity(0.14), size: 46, radius: 16, iconSize: 24)
                VStack(alignment: .leading, spacing: 3) {
                    Text(tr("Send to anyone")).body(15, weight: .semibold)
                    Text(tr("No app needed. They scan a QR code.")).body(12, CD.textDim).lineLimit(1).minimumScaleFactor(0.85)
                }
                Spacer(minLength: 4)
                CdIconView(CdIcons.arrowRight, color: CD.onAccent, size: 18)
                    .frame(width: 40, height: 40).background(Circle().fill(CD.accent))
            }
            .padding(.leading, 16).padding(.trailing, 14).padding(.vertical, 14)
            .frame(minHeight: 74)
            .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(CD.glassMid))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Color.white.opacity(0.1), lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
        .buttonStyle(PressableStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Send to anyone. No app needed, they scan a QR code."))
        .accessibilityAddTraits(.isButton)
        .pointerHover()
    }
}

private struct DriveRow: View {
    let peer: Peer
    let action: () -> Void
    var body: some View {
        let vault = peer.vault
        Button(action: action) {
            HStack(spacing: 12) {
                CdIconView(CdIcons.drive, color: CD.textPrimary, size: 22)
                Text(vault?.vaultName ?? peer.name).body(14, weight: .semibold).lineLimit(1)
                Spacer(minLength: 4)
                if vault?.needsPin == true {
                    HStack(spacing: 5) {
                        CdIconView(CdIcons.lock, color: CD.amber, size: 12)
                        Text(tr("PIN protected")).body(12, CD.amber, weight: .semibold)
                    }
                } else if vault?.writable == true {
                    Text(tr("Editable")).body(12, CD.accent, weight: .semibold)
                } else {
                    Text(tr("Read-only")).body(12, CD.textDim, weight: .semibold)
                }
            }
            .padding(.leading, 16).padding(.trailing, 14).padding(.vertical, 10)
            .frame(minHeight: 58)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(CD.glassLow))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CD.groupBorder, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(PressableStyle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(tr("Opens the drive"))
        .pointerHover()
    }
}

// MARK: - Sheets

/// "≈ 2 m · Wi-Fi" — an optional accent lead, then the rest.
struct MetaLine: View {
    let lead: String?
    let rest: String
    var body: some View {
        (Text(lead.map { $0 + (rest.isEmpty ? "" : " · ") } ?? "").foregroundColor(CD.accent).fontWeight(.semibold)
            + Text(rest).foregroundColor(CD.textSecondary))
            .font(.cd(12))
    }
}

private struct SheetHeader: View {
    let kicker: String
    let title: String
    let icon: CdIcon
    var iconTint: Color = CD.textPrimary
    let meta: MetaLine

    var body: some View {
        HStack(spacing: 14) {
            CdIconView(icon, color: iconTint, size: 26)
                .frame(width: 62, height: 62)
                .background(
                    ZStack {
                        Circle().fill(CD.inkSheet).shadow(color: CD.accent.opacity(0.22), radius: 9)
                        Circle().fill(CD.glassRaised)
                    }
                )
                .overlay(Circle().strokeBorder(CD.accent, lineWidth: 1.5))
            VStack(alignment: .leading, spacing: 5) {
                Text(kicker).body(13, CD.textDim)
                Text(title).displayStyle(26).lineLimit(2).accessibilityAddTraits(.isHeader)
                meta
            }
            Spacer(minLength: 0)
        }
    }
}

private struct BigTile: View {
    let title: String
    let subtitle: String
    let icon: CdIcon
    let primary: Bool
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                CdIconView(icon, color: primary ? CD.onAccent : CD.textPrimary, size: 32)
                Spacer(minLength: 12)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).displayStyle(19, tracking: -0.02, color: primary ? CD.onAccent : CD.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(subtitle).body(12, primary ? CD.onAccentMuted : CD.textDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 178, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .background(
                ZStack {
                    if primary { shape.fill(CD.accent).shadow(color: CD.accent.opacity(0.18), radius: 20, x: 0, y: 12) }
                    shape.fill(primary ? CD.accent : CD.glassMid)
                }
            )
            .overlay { if !primary { shape.strokeBorder(Color.white.opacity(0.1), lineWidth: 1) } }
            .contentShape(shape)
        }
        .buttonStyle(PressableStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("{0}. {1}", title, subtitle))
        .accessibilityAddTraits(.isButton)
        .pointerHover()
    }
}

private struct NoteCard: View {
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            CdIconView(CdIcons.shield, color: CD.accent, size: 20).padding(.top, 1)
            Text(text).body(13, CD.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(CD.glassLow))
    }
}

/// Peer (or "anyone") tapped: photo library or any files — to this device and any others ticked —
/// plus the live camera, a note or link, and "Switch phones".
private struct SendSheet: View {
    let kicker: String
    let title: String
    let icon: CdIcon
    let meta: MetaLine
    let note: String
    var primary: Peer?
    var others: [Peer] = []
    let onPickMedia: ([Peer]) -> Void
    let onPickFiles: ([Peer]) -> Void
    var onCamera: (([Peer]) -> Void)?
    var onText: (([Peer]) -> Void)?
    var onContacts: (([Peer]) -> Void)?
    var onSwitch: (() -> Void)?
    let onCancel: () -> Void
    @State private var also: Set<String> = []

    private var targets: [Peer] { (primary.map { [$0] } ?? []) + others.filter { also.contains($0.id) } }

    var body: some View {
        VStack(spacing: 20) {
            SheetHeader(kicker: kicker, title: title, icon: icon, meta: meta)
            HStack(spacing: 10) {
                BigTile(title: tr("Photos & videos"), subtitle: tr("Library, albums, iCloud Photos"), icon: CdIcons.photo, primary: true) { onPickMedia(targets) }
                BigTile(title: tr("Files"), subtitle: tr("Any type, pick several"), icon: CdIcons.doc, primary: false) { onPickFiles(targets) }
            }
            if primary != nil {
                if !others.isEmpty { alsoSendTo }
                VStack(spacing: 10) {
                    HStack(spacing: 10) {
                        if let onCamera { SmallTile(title: tr("Camera"), subtitle: tr("Live to them"), icon: CdIcons.camera) { onCamera(targets) } }
                        if let onText { SmallTile(title: tr("Text"), subtitle: tr("Note or link"), icon: CdIcons.note) { onText(targets) } }
                    }
                    HStack(spacing: 10) {
                        if let onContacts { SmallTile(title: tr("Contact"), subtitle: tr("Share a card"), icon: CdIcons.contacts) { onContacts(targets) } }
                        if let onSwitch { SmallTile(title: tr("Switch"), subtitle: tr("Move phone"), icon: CdIcons.swap, action: onSwitch) }
                    }
                }
            }
            NoteCard(text: note)
            PillButton(tr("Cancel"), style: .glass, action: onCancel)
        }
    }

    /// Other nearby devices as toggle chips: the same send goes to every one that's on.
    private var alsoSendTo: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr("Also send to")).body(12, CD.textDim, weight: .semibold)
            FlowChips(items: others.map { ($0.id, $0.name) }, selected: also) { id in
                if also.contains(id) { also.remove(id) } else { also.insert(id) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Compact action tile under the two big ones, two to a row.
private struct SmallTile: View {
    let title: String
    let subtitle: String
    let icon: CdIcon
    let action: () -> Void
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        Button(action: action) {
            HStack(spacing: 12) {
                CdIconView(icon, color: CD.accent, size: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).body(14, weight: .semibold).lineLimit(1)
                    Text(subtitle).body(11, CD.textDim).lineLimit(1)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(shape.fill(CD.glassMid))
            .overlay(shape.strokeBorder(Color.white.opacity(0.1), lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(PressableStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("{0}. {1}", title, subtitle))
        .accessibilityAddTraits(.isButton)
        .pointerHover()
    }
}

/// Wrapping row of toggle chips.
private struct FlowChips: View {
    let items: [(String, String)]
    let selected: Set<String>
    let onToggle: (String) -> Void
    var body: some View {
        // A plain wrapping layout: rows of chips that fit the width.
        ChipFlow(spacing: 8) {
            ForEach(items, id: \.0) { id, name in
                let on = selected.contains(id)
                Button { onToggle(id) } label: {
                    HStack(spacing: 6) {
                        CdIconView(on ? CdIcons.check : deviceIconFor(name), color: on ? CD.onAccent : CD.textPrimary, size: 15)
                        Text(name).body(13, on ? CD.onAccent : CD.textPrimary, weight: .semibold).lineLimit(1)
                    }
                    .padding(.horizontal, 12).frame(height: 38)
                    .background(Capsule().fill(on ? CD.accent : CD.glassMid))
                    .overlay(Capsule().strokeBorder(on ? CD.accent : Color.white.opacity(0.1), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
    }
}

/// Minimal flow layout (iOS 16 `Layout`).
private struct ChipFlow: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, widest: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > maxW { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing; rowH = max(rowH, s.height); widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, maxW), height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
    }
}

/// Type or paste a note or link to send (starts with whatever is on the clipboard).
struct TextComposeSheet: View {
    let target: String
    let initial: String
    let onDone: (String?) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(tr("Send text to {0}", target)).displayStyle(22).lineLimit(2)
            TextEditor(text: $text)
                .font(.cd(15)).foregroundColor(CD.textPrimary)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 110, maxHeight: 200)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(CD.glassMid))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(CD.hairlineStrong, lineWidth: 1))
                .focused($focused)
            Text(tr("They can copy it or open the link with one tap.")).body(12, CD.textDim)
            HStack(spacing: 10) {
                PillButton(tr("Cancel"), style: .glass) { onDone(nil) }
                PillButton(tr("Send"), icon: CdIcons.arrowUp) { onDone(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .onAppear { text = initial; focused = true }
    }
}

/// Drive beacon sheet: open it here (encrypted session, native explorer) or hand the browser address to
/// anyone nearby — copied, with a QR to scan.
struct VaultMountSheet: View {
    let peer: Peer
    let onMount: () -> Void
    let onCancel: () -> Void
    @State private var showQr = false

    private var url: String { peer.host.map { "http://\(Self.hostLiteral($0)):\(peer.port)/" } ?? "" }

    var body: some View {
        let vault = peer.vault
        VStack(spacing: 20) {
            SheetHeader(kicker: tr("Wireless drive"), title: vault?.vaultName ?? peer.name, icon: CdIcons.drive, iconTint: CD.amber,
                        meta: MetaLine(lead: vault?.needsPin == true ? tr("PIN protected") : nil,
                                       rest: vault?.writable == true ? tr("you can add & edit") : tr("view & download")))
            HStack(spacing: 10) {
                BigTile(title: tr("Open here"), subtitle: tr("Browse, preview and save — encrypted"), icon: CdIcons.drive, primary: true, action: onMount)
                BigTile(title: tr("Open in browser"), subtitle: showQr ? tr("Link copied — or scan below") : tr("Copy the link, show a QR"),
                        icon: CdIcons.globe, primary: false) {
                    if !url.isEmpty { UIPasteboard.general.string = url }
                    withAnimation(.easeInOut(duration: 0.25)) { showQr.toggle() }
                }
            }
            if showQr, !url.isEmpty {
                VStack(spacing: 18) {
                    QrCard(text: url, size: 200)
                    Text(url).monoStyle(13, color: CD.textSecondary).textSelection(.enabled)
                }
                .padding(.vertical, 8)
                .transition(.opacity)
            }
            NoteCard(text: tr("Files come straight from this drive over your Wi-Fi. Nothing is uploaded anywhere."))
            PillButton(tr("Cancel"), style: .glass, action: onCancel)
        }
    }

    /// IPv6 literals need brackets in a URL.
    static func hostLiteral(_ h: String) -> String { h.contains(":") ? "[\(h)]" : h }
}
