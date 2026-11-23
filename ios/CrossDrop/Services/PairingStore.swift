import Foundation
import Combine
@preconcurrency import CrossDropCore

/// One of "My devices": a device this one paired with. `key` is the shared 32-byte secret, base64. Same JSON as the
/// Kotlin `PairedDevice` (Android/desktop), field for field.
struct PairedDevice: Codable, Identifiable, Equatable {
    var id: String
    /// The name the device had when it was last seen.
    var name: String
    var key: String
    var pairedAt: Int64
    var lastSeen: Int64 = 0
    /// Files from this device are accepted without asking (its own transfers prove the pairing).
    var accept: Bool = false
    /// A pairing for phone backups. An iPhone or iPad never keeps backups, so it never holds one; kept for the shared format.
    var backup: Bool = false
    /// Share the clipboard with this device: copies made here go there, and its copies are taken here. Off until turned on.
    var clipboard: Bool = false

    init(id: String, name: String, key: String, pairedAt: Int64, lastSeen: Int64 = 0, accept: Bool = false,
         backup: Bool = false, clipboard: Bool = false) {
        self.id = id; self.name = name; self.key = key; self.pairedAt = pairedAt; self.lastSeen = lastSeen
        self.accept = accept; self.backup = backup; self.clipboard = clipboard
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        key = try c.decode(String.self, forKey: .key)
        pairedAt = try c.decodeIfPresent(Int64.self, forKey: .pairedAt) ?? 0
        lastSeen = try c.decodeIfPresent(Int64.self, forKey: .lastSeen) ?? 0
        accept = try c.decodeIfPresent(Bool.self, forKey: .accept) ?? false
        backup = try c.decodeIfPresent(Bool.self, forKey: .backup) ?? false
        clipboard = try c.decodeIfPresent(Bool.self, forKey: .clipboard) ?? false
    }

    var secret: PairSecret? {
        guard let d = Data(base64Encoded: key), d.count == 32 else { return nil }
        return PairSecret(id: id, key: [UInt8](d))
    }
}

/// "My devices" on iPhone/iPad/Mac: the pairings this device holds, in one small JSON file in Application Support (never in
/// iCloud backups: a restored backup must not make another phone one of your devices). A pairing is created only when both
/// humans agree (and compare the 6-digit code) and removed by Unpair on either side; nothing about it is advertised.
///
/// Thread-safe: the engine reads `secrets()` on its own threads. SwiftUI observes it (`objectWillChange` is sent on the main
/// queue after each change).
final class PairingStore: ObservableObject, @unchecked Sendable {
    private let lock = NSLock()
    private var list: [PairedDevice]
    private let url: URL

    init(url: URL = PairingStore.defaultURL) {
        self.url = url
        list = (try? JSONDecoder().decode([PairedDevice].self, from: Data(contentsOf: url))) ?? []
    }

    static var defaultURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("pairings.json")
    }

    var devices: [PairedDevice] { lock.lock(); defer { lock.unlock() }; return list }

    func get(_ id: String) -> PairedDevice? { devices.first { $0.id == id } }

    /// The keys held; the ones that accept files first (when two devices hold two pairings with each other, the one answered in
    /// a PAIR_CHECK is the one that matters).
    func secrets() -> [PairSecret] {
        devices.sorted { $0.accept && !$1.accept }.compactMap(\.secret)
    }

    @discardableResult
    func add(id: String, name: String, key: [UInt8], accept: Bool) -> PairedDevice {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let d = PairedDevice(id: id, name: String(name.trimmingCharacters(in: .whitespaces).prefix(64)),
                             key: Data(key).base64EncodedString(), pairedAt: now, lastSeen: now, accept: accept)
        update { $0.removeAll { $0.id == id }; $0.append(d) }
        return d
    }

    /// Remembers the device's current name and when it was last recognised (written at most once a minute per device).
    func seen(_ id: String, name: String? = nil) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let clean = name.map { String($0.trimmingCharacters(in: .whitespaces).prefix(64)) }.flatMap { $0.isEmpty ? nil : $0 }
        guard let cur = get(id) else { return }
        let rename = clean != nil && clean != cur.name
        guard rename || now - cur.lastSeen > 60_000 else { return }
        update { list in
            guard let i = list.firstIndex(where: { $0.id == id }) else { return }
            if let clean { list[i].name = clean }
            list[i].lastSeen = now
        }
    }

    func setAccept(_ id: String, _ on: Bool) { set(id) { $0.accept = on } }
    func setClipboard(_ id: String, _ on: Bool) { set(id) { $0.clipboard = on } }
    func remove(_ id: String) { update { $0.removeAll { $0.id == id } } }

    private func set(_ id: String, _ change: (inout PairedDevice) -> Void) {
        update { list in
            guard let i = list.firstIndex(where: { $0.id == id }) else { return }
            change(&list[i])
        }
    }

    private func update(_ change: (inout [PairedDevice]) -> Void) {
        lock.lock()
        let before = list
        change(&list)
        let after = list
        lock.unlock()
        guard after != before else { return }
        save(after)
        DispatchQueue.main.async { self.objectWillChange.send() }
    }

    private func save(_ devices: [PairedDevice]) {
        let fm = FileManager.default
        try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? enc.encode(devices) else { return }
        #if targetEnvironment(macCatalyst)
        try? data.write(to: url, options: .atomic)
        #else
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #endif
        var u = url
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }
}
