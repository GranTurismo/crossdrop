import Foundation
import UIKit

/// One place that owns "stay awake while CrossDrop is working": idle-timer lock, the iOS background
/// grace period (a single shared task, so transfers/shares/drive don't end each other's) and, on Mac
/// Catalyst, an App Nap / idle-sleep assertion. Holders are keyed so each feature releases only its own.
@MainActor
final class KeepAwake {
    static let shared = KeepAwake()

    private var holders: [String: () -> Void] = [:]      // key → "background time is up" callback
    private var bgTask: UIBackgroundTaskIdentifier = .invalid
    private var activity: NSObjectProtocol?

    var isHeld: Bool { !holders.isEmpty }

    /// `onExpire` fires if iOS is about to suspend us while this holder is still active — the holder
    /// should stop or fail gracefully instead of leaving a half-dead socket behind.
    func acquire(_ key: String, onExpire: @escaping () -> Void = {}) {
        holders[key] = onExpire
        apply()
    }

    func release(_ key: String) {
        guard holders.removeValue(forKey: key) != nil else { return }
        apply()
    }

    /// Foregrounded again: re-arm the background task if something is still running.
    func refresh() { apply() }

    private func apply() {
        let held = !holders.isEmpty
        UIApplication.shared.isIdleTimerDisabled = held
        if held {
            if bgTask == .invalid {
                bgTask = UIApplication.shared.beginBackgroundTask(withName: "crossdrop-active") { [weak self] in
                    // Runs on the main thread. Give the task back before iOS kills us, then tell holders.
                    guard let self else { return }
                    let callbacks = Array(self.holders.values)
                    self.endBackgroundTask()
                    callbacks.forEach { $0() }
                }
            }
            #if targetEnvironment(macCatalyst)
            if activity == nil {
                activity = ProcessInfo.processInfo.beginActivity(
                    options: [.userInitiated, .idleSystemSleepDisabled],
                    reason: "CrossDrop is transferring or sharing files")
            }
            #endif
        } else {
            endBackgroundTask()
            #if targetEnvironment(macCatalyst)
            if let a = activity { ProcessInfo.processInfo.endActivity(a); activity = nil }
            #endif
        }
    }

    private func endBackgroundTask() {
        if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
    }
}
