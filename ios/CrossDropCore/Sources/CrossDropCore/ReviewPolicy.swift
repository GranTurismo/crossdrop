import Foundation

/// A tiny key/value store (UserDefaults in the app, a dictionary in tests).
public protocol ReviewStore: AnyObject {
    func getInt(_ key: String) -> Int
    func setInt(_ value: Int, forKey key: String)
}

/// When it is fair to ask for a store rating: right after a run of transfers that worked, never after one that
/// failed, and rarely. Twin of Kotlin's `ReviewPolicy` (`core/review/ReviewPolicy.kt`): same numbers, same keys.
/// The platform decides how to ask (StoreKit); this only decides *when*. Nothing leaves the device.
public final class ReviewPolicy {
    public static let minStreak = 3
    public static let maxAsks = 3
    public static let minGapSeconds = 120 * 24 * 60 * 60

    private let store: ReviewStore
    private let now: () -> Date

    public init(store: ReviewStore, now: @escaping () -> Date = Date.init) { self.store = store; self.now = now }

    /// A transfer ended: `success` true when it completed, false when it failed. Cancels and declines aren't reported.
    public func onTransfer(success: Bool) {
        store.setInt(success ? store.getInt("review_streak") + 1 : 0, forKey: "review_streak")
    }

    /// True when the moment is right *now* (the person just closed the "done" screen).
    public func shouldAsk() -> Bool {
        guard store.getInt("review_streak") >= Self.minStreak else { return false }
        let asks = store.getInt("review_asks")
        guard asks < Self.maxAsks else { return false }
        return asks == 0 || Int(now().timeIntervalSince1970) - store.getInt("review_last_ask") >= Self.minGapSeconds
    }

    /// Record that we asked: the streak starts over, so the next ask needs a fresh run of good transfers.
    public func asked() {
        store.setInt(store.getInt("review_asks") + 1, forKey: "review_asks")
        store.setInt(Int(now().timeIntervalSince1970), forKey: "review_last_ask")
        store.setInt(0, forKey: "review_streak")
    }
}
