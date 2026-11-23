import Foundation
import UIKit
@preconcurrency import CrossDropCore

/// A device asking to become one of "My devices", waiting for the human here (both screens show `sas`).
final class PairPrompt: Identifiable, @unchecked Sendable {     // the answer is behind `lock`
    let id = UUID()
    let peerName: String
    let sas: String
    private let sem = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var answer: Bool?

    init(peerName: String, sas: String) { self.peerName = peerName; self.sas = sas }

    func resolve(_ accept: Bool) {
        lock.lock()
        if answer == nil { answer = accept; sem.signal() }
        lock.unlock()
    }

    /// Blocks (engine thread) until answered or `timeout` passes (= no).
    func await(timeout: TimeInterval) -> Bool {
        _ = sem.wait(timeout: .now() + timeout)
        lock.lock(); defer { lock.unlock() }
        if answer == nil { answer = false }
        return answer!
    }
}

/// The listener's answers to the exchanges that carry no files: announcements and shakes (as before), and now pairing
/// ("Add a device"), recognising a pairing, and clipboard copies from "My devices". Engine threads.
final class DevicesControl: ControlHandler, @unchecked Sendable {
    private let relay: AnnounceRelay
    private let store: PairingStore
    private let sync: ClipboardSync
    /// Shows the request to the human and blocks until they answer (or the request times out).
    private let ask: (_ name: String, _ sas: String) -> Bool

    init(relay: AnnounceRelay, pairings: PairingStore, clipboard: ClipboardSync, ask: @escaping (String, String) -> Bool) {
        self.relay = relay; self.store = pairings; self.sync = clipboard; self.ask = ask
    }

    func announced(name: String, instanceId: String, host: String, port: Int) {
        relay.announced(name: name, instanceId: instanceId, host: host, port: port)
    }

    func shaken(name: String, instanceId: String, host: String, port: Int) {
        relay.shaken(name: name, instanceId: instanceId, host: host, port: port)
    }

    func pairRequested(name: String, sas: String, purpose: String?) -> Bool {
        // The old, backup-only request is for a computer that keeps backups. An iPhone/iPad/Mac app keeps none, so it refuses
        // without a word: agreeing would make the asking phone treat this device as its "backup computer".
        guard purpose == PairPurpose.devices else { return false }
        return ask(name, sas)
    }

    func paired(pairId: String, name: String, key: [UInt8], purpose: String?) {
        guard purpose == PairPurpose.devices else { return }
        store.add(id: pairId, name: name, key: key, accept: true)
    }

    func pairings() -> [PairSecret] { store.secrets() }

    func clipboard(pairId: String, clipId: String, text: String) -> Bool {
        sync.received(pairId: pairId, clipId: clipId, text: text)
    }
}

/// This device's clipboard and "Share clipboard".
///
/// - **iPhone/iPad**: iOS asks the person every time an app reads the clipboard in code, so CrossDrop never does. When the
///   app comes to the front with something new copied (it only looks at the change counter and whether it holds text, which
///   doesn't ask), the radar offers the system Paste button; tapping it hands the text over without any prompt and shares it.
/// - **Mac**: like the desktop app, it watches the change counter while some device has sharing on and shares each new
///   copy by itself, except copies that password managers mark as secret.
/// - Copies from the other devices are put on the clipboard straight away (writing never asks).
@MainActor
final class PasteboardShare: ObservableObject {
    /// iPhone/iPad: something new was copied since the last look; the radar offers to share it.
    @Published private(set) var waiting = false
    /// How the last explicit share went ("Clipboard sent to Mac").
    @Published private(set) var note: String?

    private let sync: ClipboardSync
    private let pairings: PairingStore
    private var lastCount: Int
    private var timer: Timer?
    private var noteClear: DispatchWorkItem?
    private static let seenKey = "clipboard_seen_count"

    init(sync: ClipboardSync, pairings: PairingStore) {
        self.sync = sync; self.pairings = pairings
        #if targetEnvironment(macCatalyst)
        lastCount = UIPasteboard.general.changeCount     // what was there before CrossDrop started is not a copy to share
        #else
        lastCount = UserDefaults.standard.integer(forKey: Self.seenKey)
        #endif
    }

    /// A copy from one of "My devices": onto this clipboard. Called on an engine thread.
    nonisolated func write(_ text: String) {
        DispatchQueue.main.async {
            UIPasteboard.general.string = text
            self.markSeen()
            #if DEBUG
            NSLog("CrossDrop clipboard: put %d chars from a device on the clipboard", text.count)
            #endif
        }
    }

    /// The app came to the front, or a sharing switch changed.
    func refresh() {
        #if targetEnvironment(macCatalyst)
        if sync.active {
            if timer == nil {
                lastCount = UIPasteboard.general.changeCount   // watching starts now: what is there already isn't a new copy
                timer = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in
                    Task { @MainActor in self?.poll() }
                }
            }
        } else {
            timer?.invalidate(); timer = nil
        }
        #else
        let pb = UIPasteboard.general
        waiting = sync.active && pb.changeCount != lastCount && pb.hasStrings && !Self.marked(pb)
        #if DEBUG
        NSLog("CrossDrop clipboard card %@", waiting ? "shown" : "hidden")
        if waiting, let t = UserDefaults.standard.string(forKey: "dev_paste_text") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if self.waiting { self.share([t]) } }
        }
        #endif
        #endif
    }

    /// The Paste button handed over what is on the clipboard (no prompt: the person tapped the system's own button).
    func share(_ strings: [String]) {
        markSeen()
        guard let text = strings.first(where: { !$0.isEmpty }) else { show(tr("There's nothing on the clipboard")); return }
        guard pairings.devices.contains(where: \.clipboard) else {
            show(tr("Turn on Share clipboard for one of your devices in Settings → My devices")); return
        }
        show(tr("Sharing your clipboard…"), clearAfter: nil)
        let pairings = self.pairings
        sync.shareNow(text) { [weak self] r in
            let name = { (id: String) in pairings.get(id)?.name ?? "?" }
            let sent = r.results.filter { $0.value == .sent }.keys.map(name).sorted()
            let msg: String
            switch r.outcome {
            case .tooLong: msg = tr("That's too long to share")
            case .empty: msg = tr("There's nothing on the clipboard")
            default:
                if !sent.isEmpty { msg = tr("Clipboard sent to {0}", sent.joined(separator: ", ")) }
                else if let off = r.results.first(where: { $0.value == .offThere }) { msg = tr("Clipboard sharing is off on {0}", name(off.key)) }
                else if let old = r.results.first(where: { $0.value == .oldVersion }) { msg = tr("Update CrossDrop on {0} first", name(old.key)) }
                else { msg = tr("Couldn't find {0}. Open CrossDrop there, on the same Wi-Fi.", r.results.keys.map(name).sorted().joined(separator: ", ")) }
            }
            self?.show(msg)
        }
    }

    /// "Not now": this copy isn't offered again.
    func dismiss() { markSeen() }

    private func markSeen() {
        lastCount = UIPasteboard.general.changeCount
        waiting = false
        #if !targetEnvironment(macCatalyst)
        UserDefaults.standard.set(lastCount, forKey: Self.seenKey)
        #endif
    }

    private func show(_ text: String, clearAfter: TimeInterval? = 4) {
        note = text
        #if DEBUG
        NSLog("CrossDrop clipboard note: %@", text)
        #endif
        noteClear?.cancel()
        guard let clearAfter else { return }
        let w = DispatchWorkItem { [weak self] in self?.note = nil }
        noteClear = w
        DispatchQueue.main.asyncAfter(deadline: .now() + clearAfter, execute: w)
    }

    #if targetEnvironment(macCatalyst)
    /// Mac: a new copy (the change counter moved) is shared unless a password manager marked it.
    private func poll() {
        let pb = UIPasteboard.general
        let c = pb.changeCount
        guard c != lastCount else { return }
        lastCount = c
        guard pb.hasStrings, !Self.marked(pb), let text = pb.string else { return }
        sync.copied(text)
    }
    #endif

    /// Copies that password managers (and other apps) ask clipboard tools to leave alone (nspasteboard.org markers).
    private static func marked(_ pb: UIPasteboard) -> Bool {
        let secret: Set<String> = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType", "org.nspasteboard.AutoGeneratedType"]
        return pb.types.contains(where: secret.contains)
    }
}
