import Foundation
import StoreKit
import UIKit
@preconcurrency import CrossDropCore

/// Asks for an App Store rating, rarely and at a good moment (see `ReviewPolicy`): after a run of transfers that
/// worked, when the person closes the "done" screen. StoreKit's own sheet does the asking, so nothing about the
/// person or the app's use leaves the device; the system may also decide to show nothing.
@MainActor
final class ReviewPrompter {
    private final class Defaults: ReviewStore {
        func getInt(_ key: String) -> Int { UserDefaults.standard.integer(forKey: key) }
        func setInt(_ value: Int, forKey key: String) { UserDefaults.standard.set(value, forKey: key) }
    }
    private let policy = ReviewPolicy(store: Defaults())

    /// A transfer ended well (`success`) or badly; cancels and declines aren't reported.
    func onTransfer(success: Bool) { policy.onTransfer(success: success) }

    /// Call when the person has just finished with a successful transfer (closed its screen).
    func maybeAsk() {
        guard policy.shouldAsk() else { return }
        // Whatever the system decides, this counts as the ask: no retrying sooner because it stayed silent.
        policy.asked()
        // Let the "done" screen finish closing first, so the sheet doesn't land on a moving screen.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            guard let scene = UIApplication.shared.connectedScenes
                .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene else { return }
            AppStore.requestReview(in: scene)
        }
    }
}
