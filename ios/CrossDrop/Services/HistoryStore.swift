import Foundation

/// Persisted log of finished transfers — local-only, capped.
final class HistoryStore: ObservableObject {
    @Published private(set) var entries: [HistoryEntry] = []
    private static let storageKey = "transfer_history"
    private let key = HistoryStore.storageKey

    /// Whether anything was ever logged, read without creating a store (the first-run welcome asks at launch).
    static var hasEntries: Bool {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let all = try? JSONDecoder().decode([HistoryEntry].self, from: data) else { return false }
        return !all.isEmpty
    }
    /// How many finished sessions are kept (the Transfers tab says so).
    static let maxEntries = 100

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = decoded
        }
    }

    func add(_ e: HistoryEntry) {
        entries = Array(([e] + entries).prefix(Self.maxEntries))
        save()
    }

    func clear() { entries = []; save() }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { UserDefaults.standard.set(data, forKey: key) }
    }
}
