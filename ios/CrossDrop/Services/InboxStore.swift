import Foundation
@preconcurrency import CrossDropCore

/// Where received files land.
///  - **Mac (Catalyst)**: a `CrossDrop` folder inside the real Downloads (sandbox entitlement
///    `files.downloads.read-write`); the resume pool and delta indexes stay in Application Support.
///  - **iPhone / iPad**: iOS gives an app no shared Downloads folder to write to, so it is the app's
///    `Documents/Received` — `UIFileSharingEnabled` lists it in the Files app under On My iPhone › CrossDrop.
///    Dot-directories (.staging/.idx) stay hidden there.
/// Engine threads only call `sinkFor`/`delta` (stateless file work); `files` is written on the main thread.
final class InboxStore: ObservableObject, @unchecked Sendable {
    struct ReceivedFile: Identifiable, Equatable {
        let url: URL
        let size: Int64
        let modified: Date
        var id: String { url.lastPathComponent }
        var name: String { url.lastPathComponent }
    }

    let dir: URL
    /// Resume pool + delta indexes. The inbox itself on iPhone/iPad; outside the visible folder on Mac.
    let state: URL
    let delta: FileDeltaStore
    @Published private(set) var files: [ReceivedFile] = []

    init() {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        // not "Inbox": iOS reserves that name for Open-in copies
        let received = docs.appendingPathComponent("Received", isDirectory: true)
        var visible = received, bookkeeping = received
        #if targetEnvironment(macCatalyst)
        if let downloads = try? fm.url(for: .downloadsDirectory, in: .userDomainMask, appropriateFor: nil, create: true) {
            let target = downloads.appendingPathComponent("CrossDrop", isDirectory: true)
            try? fm.createDirectory(at: target, withIntermediateDirectories: true)
            if fm.isWritableFile(atPath: target.path) {     // not writable → stay in the app container
                visible = target
                bookkeeping = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("InboxState", isDirectory: true)
            }
        }
        #endif
        dir = visible
        state = bookkeeping
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: state, withIntermediateDirectories: true)
        delta = FileDeltaStore(inboxDir: dir, stateDir: state) { meta in InboxStore.sanitize(meta.name) }
        refresh()
        // A big old inbox can take a while to move (Downloads may be on another volume): never at launch, on the main thread.
        if received != dir {
            let (from, to, st) = (received, dir, state)
            DispatchQueue.global(qos: .utility).async { [weak self] in
                Self.migrate(from: from, to: to, state: st)
                self?.refresh()
            }
        }
    }

    /// One-time move of the pre-Downloads inbox (`Documents/Received`) into the new one: visible files keep
    /// their names (numbered on a clash), the resume pool and indexes follow into `state`. Free when there's
    /// nothing to move.
    static func migrate(from old: URL, to dir: URL, state: URL) {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: old.path) else { return }
        var newName: [String: String] = [:]          // old name -> name it got here (numbered on a clash)
        for name in names where !name.hasPrefix(".") {
            let src = old.appendingPathComponent(name)
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: src.path, isDirectory: &isDir), !isDir.boolValue else { continue }
            let ext = (name as NSString).pathExtension, stem = (name as NSString).deletingPathExtension
            var target = dir.appendingPathComponent(name)
            var i = 1
            while fm.fileExists(atPath: target.path) {
                target = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
                i += 1
            }
            if (try? fm.moveItem(at: src, to: target)) != nil { newName[name] = target.lastPathComponent }
        }
        for sub in [".staging", ".idx"] {
            let from = old.appendingPathComponent(sub, isDirectory: true)
            let to = state.appendingPathComponent(sub, isDirectory: true)
            try? fm.createDirectory(at: to, withIntermediateDirectories: true)
            for item in (try? fm.contentsOfDirectory(atPath: from.path)) ?? [] {
                // An index is `<file>.idx`: it follows its file, so a renumbered file never overwrites
                // (or inherits) the index of the one that was already here.
                var target = item
                if sub == ".idx", item.hasSuffix(".idx"), let n = newName[String(item.dropLast(4))] { target = n + ".idx" }
                let dst = to.appendingPathComponent(target)
                try? fm.removeItem(at: dst)
                try? fm.moveItem(at: from.appendingPathComponent(item), to: dst)
            }
            try? fm.removeItem(at: from)
        }
    }

    func refresh() {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey],
            options: [.skipsHiddenFiles])) ?? []
        let next: [ReceivedFile] = urls.compactMap { u in
            let v = try? u.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey])
            guard v?.isRegularFile == true, !u.pathExtension.lowercased().hasSuffix("part") else { return nil }
            return ReceivedFile(url: u, size: Int64(v?.fileSize ?? 0), modified: v?.contentModificationDate ?? .distantPast)
        }.sorted { $0.modified > $1.modified }
        if Thread.isMainThread { files = next } else { DispatchQueue.main.async { self.files = next } }
    }

    /// Collision- and traversal-safe sink for the v1 (monolithic) path.
    func sinkFor(_ meta: FileMeta) throws -> OutputSink { try newFile(meta).sink }

    /// A new, collision-free inbox file for `meta` and a sink writing it.
    func newFile(_ meta: FileMeta) throws -> (url: URL, sink: OutputSink) {
        let safe = InboxStore.sanitize(meta.name)
        let ext = (safe as NSString).pathExtension
        let stem = (safe as NSString).deletingPathExtension
        var target = dir.appendingPathComponent(safe)
        var i = 1
        while FileManager.default.fileExists(atPath: target.path) {
            target = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            i += 1
        }
        guard FileManager.default.createFile(atPath: target.path, contents: nil),
              let h = FileHandle(forWritingAtPath: target.path) else { throw ProtocolError("cannot create \(safe)") }
        return (target, HandleSink(h))
    }

    /// Single-pass zero-fill then unlink.
    func shred(_ file: ReceivedFile) {
        if let h = try? FileHandle(forWritingTo: file.url) {
            let zeros = Data(count: 64 * 1024)
            var left = file.size
            while left > 0 {
                let n = Int(min(left, Int64(zeros.count)))
                try? h.write(contentsOf: zeros.prefix(n))
                left -= Int64(n)
            }
            try? h.close()
        }
        try? FileManager.default.removeItem(at: file.url)
        try? FileManager.default.removeItem(at: state.appendingPathComponent(".idx/\(file.name).idx"))
        refresh()
    }

    func shredAll() {
        files.forEach { shred($0) }
        delta.clearPool()
        try? FileManager.default.removeItem(at: state.appendingPathComponent(".idx"))
        refresh()
    }

    /// Safe local file name for a peer-supplied one: no path parts, no control characters or ':', no
    /// leading dots, NFC-normalised (a Mac sender may use NFD — same name, different bytes), and short enough
    /// for the filesystem (255 UTF-8 bytes per component, minus room for " (n)" and our ".<n>.part" temp
    /// suffix) with the extension kept. Georgian and other non-Latin names pass through untouched.
    static func sanitize(_ name: String) -> String {
        var base = name.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        base = String(base.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) || $0 == ":" ? "_" : Character($0) })
        base = base.precomposedStringWithCanonicalMapping
        while base.hasPrefix(".") { base.removeFirst() }
        base = base.trimmingCharacters(in: .whitespaces)
        if base.isEmpty { return "file" }
        // Foundation hands the filesystem the *decomposed* (NFD) form, which can be twice as long for accented
        // Latin, so measure that. 170 leaves room for " (n)" and our ".<n>.part" temp suffix under 255.
        let maxBytes = 170
        func fsBytes(_ x: String) -> Int { x.decomposedStringWithCanonicalMapping.utf8.count }
        func clip(_ x: String, _ limit: Int) -> String {
            var out = "", n = 0
            for ch in x {
                let c = fsBytes(String(ch))
                if n + c > limit { break }
                out.append(ch); n += c
            }
            return out
        }
        if fsBytes(base) > maxBytes {
            let ext = (base as NSString).pathExtension
            let keepExt = ext.utf8.count <= 16 && !ext.isEmpty ? "." + ext : ""
            let stem = keepExt.isEmpty ? base : (base as NSString).deletingPathExtension
            base = clip(stem, maxBytes - fsBytes(keepExt)) + keepExt
        }
        return base.isEmpty ? "file" : base
    }
}

final class HandleSink: OutputSink {
    private let h: FileHandle
    init(_ h: FileHandle) { self.h = h }
    func write(_ bytes: [UInt8]) throws { try h.write(contentsOf: Data(bytes)) }
    func close() { try? h.close() }
}


#if DEBUG
extension InboxStore {
    /// Screenshot seed (`-dev_seed_inbox YES`): sparse files with believable names, sizes and dates.
    func seedDebug() {
        let items: [(String, Int64, TimeInterval)] = [
            ("Holiday in Tbilisi.mov", 412_000_000, 120), ("IMG_4021.heic", 3_100_000, 900),
            ("Boarding pass.pdf", 312_000, 3_600), ("Project Files.zip", 84_000_000, 7_200),
            ("Podcast ep 12.m4a", 27_000_000, 26_000), ("Résumé 2026.docx", 58_000, 90_000),
        ]
        let fm = FileManager.default
        for (name, size, ago) in items {
            let url = dir.appendingPathComponent(name)
            if !fm.fileExists(atPath: url.path) {
                fm.createFile(atPath: url.path, contents: nil)
                if let h = try? FileHandle(forWritingTo: url) { try? h.truncate(atOffset: UInt64(size)); try? h.close() }
            }
            try? fm.setAttributes([.modificationDate: Date().addingTimeInterval(-ago)], ofItemAtPath: url.path)
        }
        refresh()
    }
}
#endif
