import SwiftUI
@preconcurrency import CrossDropCore

/// "Switch phones" on the old device: pick what moves, then watch it go to the device chosen on the radar.
struct SwitchSendScreen: View {
    let peer: Peer
    @ObservedObject var sender: SwitchSender
    let onClose: () -> Void
    @State private var plan = SwitchSender.Plan()
    @State private var photos = true
    @State private var videos = true
    @State private var contacts = true
    @State private var loaded = false

    var body: some View {
        if let s = sender.state { progress(s) } else { setup }
    }

    private var setup: some View {
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.14, centerY: 140, radius: 240)
            VStack(spacing: 0) {
                BackHeader(tr("Switch phones"), onBack: onClose)
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        switchAccentText(tr("Move everything to {0}", peer.name), accenting: peer.name)
                            .font(.display(28)).foregroundColor(CD.textPrimary)
                        Text(tr("Straight over Wi-Fi, encrypted, nothing uploaded. Works between iPhone and Android in both directions."))
                            .body(14, CD.textSecondary)
                        GlassCard(radius: 26, fill: CD.glass, border: CD.hairline, padding: .all(6)) {
                            row(CdIcons.photo, tr("Photos"), PhotosBridge.readAuthorized() ? "\(plan.photos)" : tr("Needs photo access"),
                                $photos, PhotosBridge.readAuthorized())
                            RowSeparator()
                            row(CdIcons.video, tr("Videos"), PhotosBridge.readAuthorized() ? "\(plan.videos)" : tr("Needs photo access"),
                                $videos, PhotosBridge.readAuthorized())
                            RowSeparator()
                            row(CdIcons.contacts, tr("Contacts"), ContactsBridge.authorized() ? "\(plan.contacts)" : tr("Needs contacts access"),
                                $contacts, ContactsBridge.authorized())
                        }
                        if !PhotosBridge.readAuthorized() || !ContactsBridge.authorized() {
                            PillButton(tr("Allow access"), style: .glass, icon: CdIcons.lock) { Task { await ask() } }
                        }
                        if plan.limited {
                            FootNote(tr("Only the photos you selected are visible to CrossDrop. Allow full access to move your whole library."), icon: CdIcons.help)
                        }
                        FootNote(tr("Messages, apps and app data can't be moved by any app. Keep both screens on and on the same Wi-Fi until it's done. If the connection drops, start again — it continues where it stopped."), icon: CdIcons.shield)
                    }
                    .padding(.top, 8)
                }
                let any = (photos && plan.photos > 0) || (videos && plan.videos > 0) || (contacts && plan.contacts > 0)
                PillButton(tr("Start moving"), icon: CdIcons.swap, glow: true) {
                    sender.start(peer, photos: photos && PhotosBridge.readAuthorized(), videos: videos && PhotosBridge.readAuthorized(),
                                 contacts: contacts && ContactsBridge.authorized())
                }
                .disabled(!any || !loaded)
                .padding(.vertical, 18)
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 16)
        }
        .task { await ask() }
    }

    private func ask() async {
        _ = await PhotosBridge.requestRead()
        _ = await ContactsBridge.requestAccess()
        plan = await Task.detached { await MainActor.run { sender.plan() } }.value
        loaded = true
    }

    private func row(_ icon: CdIcon, _ title: String, _ sub: String, _ on: Binding<Bool>, _ enabled: Bool) -> some View {
        HStack(spacing: 12) {
            IconTile(icon: icon, tint: CD.accent, size: 40, radius: 20, iconSize: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).body(15, weight: .semibold)
                Text(sub).body(12, enabled ? CD.textDim : CD.amber)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { on.wrappedValue && enabled }, set: { on.wrappedValue = $0 }))
                .toggleStyle(CdToggleStyle()).labelsHidden().disabled(!enabled)
        }
        .padding(.horizontal, 12).padding(.vertical, 12)
    }

    private func progress(_ s: SwitchSender.State) -> some View {
        let failed = [.failed, .declined, .cancelled].contains(s.phase)
        let title: String = {
            switch s.phase {
            case .preparing: return tr("Getting your library ready…")
            case .waiting: return tr("Waiting for {0} to accept", s.peerName)
            case .moving: return tr("Moving to {0}", s.peerName)
            case .done: return tr("All moved to {0}", s.peerName)
            case .failed: return tr("Paused — connection lost")
            case .declined: return tr("{0} declined", s.peerName)
            case .cancelled: return tr("Stopped")
            }
        }()
        return ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: failed ? CD.dangerText : CD.accent, alpha: 0.14, centerY: 260, radius: 240)
            VStack(spacing: 16) {
                Text(title).displayStyle(22).multilineTextAlignment(.center).padding(.top, 24)
                SwitchRing(fraction: s.phase == .done ? 1 : s.fraction, done: s.phase == .done, failed: failed)
                Text(tr("{0} of {1} items", s.itemsDone, s.itemsTotal) + (s.bytesPerSec > 0 ? " · \(Fmt.rate(s.bytesPerSec))" : ""))
                    .monoStyle(13, color: CD.textDim)
                if s.batches > 1 && !s.finished { Text(tr("Batch {0} of {1}", s.batch + 1, s.batches)).body(12, CD.textDim) }
                if let e = s.error { Text(e).body(13, CD.dangerText).multilineTextAlignment(.center) }
                Spacer()
                HStack(spacing: 10) {
                    switch s.phase {
                    case .done, .declined, .cancelled:
                        PillButton(tr("Done"), style: s.phase == .done ? .accent : .glass) { sender.close(); onClose() }
                    case .failed:
                        PillButton(tr("Close"), style: .glass) { sender.close(); onClose() }
                        PillButton(tr("Resume")) { sender.resume() }
                    default:
                        PillButton(tr("Stop"), style: .danger) { sender.cancel() }
                    }
                }
            }
            .frame(maxWidth: 560)
            .padding(16)
        }
    }
}

/// "Switch phones" on the new device: what's arriving and where it went.
struct SwitchReceiveScreen: View {
    @ObservedObject var importer: SwitchImporter
    let state: SwitchImporter.State
    let receiving: SessionState?
    let onHide: () -> Void

    var body: some View {
        let done = state.phase == .done, failed = state.phase == .failed
        let total = max(state.photosTotal + state.videosTotal, 1)
        let f = done ? 1 : min(1, Double(state.photos + state.videos) / Double(total))
        let title: String = {
            if done { return tr("Welcome to your new {0}", Platform.deviceNoun) }
            if failed { return tr("Move paused") }
            if state.phase == .importing { return tr("Adding to your library…") }
            return tr("Moving from {0}", state.peerName)
        }()
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(color: failed ? CD.dangerText : CD.accent, alpha: 0.14, centerY: 240, radius: 240)
            ScrollView {
                VStack(spacing: 16) {
                    Text(title)
                        .displayStyle(22).multilineTextAlignment(.center).padding(.top, 24)
                    SwitchRing(fraction: f, done: done, failed: failed)
                    if let r = receiving, !done {
                        Text(tr("Batch {0} of {1}", state.batch + 1, state.batches) + " · \(Int(r.fraction * 100))%" + (r.bytesPerSec > 0 ? " · \(Fmt.rate(r.bytesPerSec))" : ""))
                            .monoStyle(13, color: CD.textDim)
                    }
                    GlassCard(radius: 26, fill: CD.glass, border: CD.hairline, padding: .all(6)) {
                        if state.photosTotal + state.photos > 0 { count(CdIcons.photo, tr("Photos"), "\(state.photos) / \(state.photosTotal)") }
                        if state.videosTotal + state.videos > 0 {
                            if state.photosTotal + state.photos > 0 { RowSeparator() }
                            count(CdIcons.video, tr("Videos"), "\(state.videos) / \(state.videosTotal)")
                        }
                        if state.contactsTotal > 0 {
                            RowSeparator()
                            count(CdIcons.contacts, tr("Contacts"), tr("{0} added", state.contactsAdded) + (state.contactsSkipped > 0 ? " · " + tr("{0} already here", state.contactsSkipped) : ""))
                        }
                        if state.files > 0 { RowSeparator(); count(CdIcons.doc, tr("Files"), tr("{0} in inbox", state.files)) }
                    }
                    if let e = state.error { Text(e).body(13, CD.dangerText).multilineTextAlignment(.center) }
                    if state.failedItems > 0 {
                        Text(tr("{0} item(s) couldn't be added to Photos and were put in your inbox.", state.failedItems)).body(12, CD.amber)
                            .multilineTextAlignment(.center)
                    }
                    FootNote(tr("Photos and videos keep their original dates."), icon: CdIcons.shield)
                    HStack(spacing: 10) {
                        if done || failed { PillButton(tr("Done")) { importer.close(); onHide() } }
                        else {
                            PillButton(tr("Stop"), style: .danger) { importer.stop(); onHide() }
                            PillButton(tr("Hide"), style: .glass, action: onHide)
                        }
                    }
                    .padding(.top, 8)
                }
                .frame(maxWidth: 560)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func count(_ icon: CdIcon, _ label: String, _ value: String) -> some View {
        HStack(spacing: 12) {
            CdIconView(icon, color: CD.accent, size: 20)
            Text(label).body(15, weight: .semibold)
            Spacer()
            Text(value).monoStyle(13, color: CD.textSecondary)
        }
        .padding(12)
    }
}

/// One translated sentence with `part` (a device name) picked out in the accent colour.
private func switchAccentText(_ sentence: String, accenting part: String) -> Text {
    var attr = AttributedString(sentence)
    if let r = attr.range(of: part) { attr[r].foregroundColor = CD.accent }
    return Text(attr)
}

private struct SwitchRing: View {
    let fraction: Double
    let done: Bool
    let failed: Bool
    var body: some View {
        ZStack {
            Circle().inset(by: 8).stroke(Color.white.opacity(0.07), lineWidth: 12)
            Circle().inset(by: 8).trim(from: 0, to: fraction)
                .stroke(failed ? CD.dangerText : CD.accent, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.4), value: fraction)
            if done {
                CdIconView(CdIcons.check, color: CD.onAccent, size: 32).frame(width: 64, height: 64).background(Circle().fill(CD.accent))
            } else {
                (Text("\(Int(fraction * 100))").font(.display(52)) + Text("%").font(.display(24)).foregroundColor(CD.textDim))
                    .foregroundColor(CD.textPrimary)
            }
        }
        .frame(width: 190, height: 190)
    }
}
