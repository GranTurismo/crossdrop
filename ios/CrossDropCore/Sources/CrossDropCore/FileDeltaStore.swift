import Foundation

/// Filesystem `DeltaReceiver` — port of `core/delta/FileDeltaStore.kt`.
///
/// Layout under `inboxDir`:
///   `<file>`            assembled received files
///   `.staging/<b3>.bin` received-but-not-yet-assembled chunks (survive process death → resume)
///   `.idx/<file>.idx`   persisted chunk indexes so re-shares delta without re-chunking
public final class FileDeltaStore: DeltaReceiver {
    private let inboxDir: URL
    private let nameFor: (FileMeta) -> String
    private let fm = FileManager.default

    public init(inboxDir: URL, nameFor: ((FileMeta) -> String)? = nil) {
        self.inboxDir = inboxDir
        self.nameFor = nameFor ?? { FileDeltaStore.sanitizedFileName($0.name) }
    }

    /// Safe local file name for a peer-chosen one: last path component only, no control characters,
    /// never hidden (`.staging` / `.idx` are ours), never `.` / `..`, at most 180 UTF-8 bytes
    /// (APFS allows 255 and `.part` suffixes are appended) with the extension kept.
    public static func sanitizedFileName(_ raw: String) -> String {
        var base = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        base = String(String.UnicodeScalarView(base.unicodeScalars.map {
            CharacterSet.controlCharacters.contains($0) || $0 == ":" || $0.value == 0xFFFE || $0.value == 0xFFFF ? "_" : $0
        }))
        while base.hasPrefix(".") { base.removeFirst() }
        base = base.trimmingCharacters(in: .whitespaces)
        return clampName(base.isEmpty ? "file" : base)
    }

    static func clampName(_ name: String, maxBytes: Int = 180) -> String {
        if name.utf8.count <= maxBytes { return name }
        let ext = (name as NSString).pathExtension
        let keepExt = ext.utf8.count > 0 && ext.utf8.count <= 16 ? "." + ext : ""
        var stem = String((name as NSString).deletingPathExtension)
        while !stem.isEmpty && stem.utf8.count + keepExt.utf8.count > maxBytes { stem.removeLast() }
        return stem.isEmpty ? "file" + keepExt : stem + keepExt
    }

    private var pool: URL { inboxDir.appendingPathComponent(".staging", isDirectory: true) }
    private var idxDir: URL { inboxDir.appendingPathComponent(".idx", isDirectory: true) }

    public func stagingPool() -> URL {
        try? fm.createDirectory(at: pool, withIntermediateDirectories: true)
        return pool
    }

    /// Final destination, always a direct child of the inbox: whatever `nameFor` returned is re-checked
    /// (a custom sanitiser must not be able to escape the inbox, hide a file or collide with our state dirs).
    public func targetFor(_ meta: FileMeta) -> URL {
        var name = FileDeltaStore.clampName(nameFor(meta))
        if name.isEmpty || name == "." || name == ".." || name.contains("/") || name.contains("\0") { name = "file" }
        if name.hasPrefix(".") { name = "_" + name }
        let url = inboxDir.appendingPathComponent(name)
        guard url.deletingLastPathComponent().standardizedFileURL.path == inboxDir.standardizedFileURL.path else {
            return inboxDir.appendingPathComponent("file")
        }
        return url
    }

    public func baselineFor(_ meta: FileMeta) -> Baseline? {
        let file = targetFor(meta)
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: file.path, isDirectory: &isDir), !isDir.boolValue else { return nil }
        let idx = indexFile(for: file)
        let indexed: Indexed
        if let cached = readIndex(idx, file) {
            indexed = cached
        } else if let built = try? chunkFile(file) {
            writeIndex(idx, file, built.chunks, built.root)
            indexed = built
        } else { return nil }
        return Baseline(file: file, chunks: indexed.chunks, root: indexed.root)
    }

    public func canPush(_ files: [FileMeta]) -> Bool {
        let bins = (try? fm.contentsOfDirectory(atPath: pool.path))?.filter { $0.hasSuffix(".bin") } ?? []
        return bins.isEmpty && !files.contains { fm.fileExists(atPath: targetFor($0).path) }
    }

    public func publishIndex(file: URL, chunks: [ChunkEntry], root: String) {
        writeIndex(indexFile(for: file), file, chunks, root)
    }

    public func invalidate(_ meta: FileMeta) {
        try? fm.removeItem(at: indexFile(for: targetFor(meta)))
    }

    public func clearPool() { try? fm.removeItem(at: pool) }

    /// Staging chunks older than a week and, beyond 2 GiB, the oldest ones go; half-written `.part`
    /// files and stale `.tmp` leftovers (a crash mid-transfer) older than a day go too.
    public func prunePool(maxBytes: Int64 = 2 << 30, maxAge: TimeInterval = 7 * 86_400, now: Date = Date()) {
        func entries(_ dir: URL) -> [(url: URL, size: Int64, date: Date)] {
            ((try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []).compactMap {
                let v = try? $0.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
                return ($0, Int64(v?.fileSize ?? 0), v?.contentModificationDate ?? .distantPast)
            }
        }
        var bins: [(url: URL, size: Int64, date: Date)] = []
        for e in entries(pool) {
            if e.url.lastPathComponent.hasSuffix(".tmp") || now.timeIntervalSince(e.date) > maxAge { try? fm.removeItem(at: e.url) }
            else { bins.append(e) }
        }
        var total = bins.reduce(Int64(0)) { $0 + $1.size }
        for e in bins.sorted(by: { $0.date < $1.date }) where total > maxBytes {
            try? fm.removeItem(at: e.url); total -= e.size
        }
        for e in entries(inboxDir) where e.url.pathExtension == "part" && now.timeIntervalSince(e.date) > 86_400 {
            try? fm.removeItem(at: e.url)
        }
        for e in entries(idxDir) where e.url.pathExtension == "tmp" && now.timeIntervalSince(e.date) > 86_400 {
            try? fm.removeItem(at: e.url)
        }
    }

    public func poolBytes() -> Int64 {
        ((try? fm.contentsOfDirectory(at: pool, includingPropertiesForKeys: [.fileSizeKey])) ?? [])
            .reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    // MARK: internals

    private func indexFile(for file: URL) -> URL { idxDir.appendingPathComponent("\(file.lastPathComponent).idx") }

    public struct Indexed { public let chunks: [ChunkEntry]; public let root: String }

    /// FastCDC-split `file` into BLAKE3-keyed entries + whole-file root in one streaming pass.
    public func chunkFile(_ file: URL) throws -> Indexed {
        var out: [ChunkEntry] = []
        let hasher = Blake3()
        try FastCdc.chunks(of: file) { bytes, _ in
            out.append(ChunkEntry(len: bytes.count, hash: Blake3.hash(bytes)))
            hasher.update(bytes)
        }
        return Indexed(chunks: out, root: hasher.hexDigest())
    }

    private static let magic: UInt32 = 0x43445833   // "CDX3"

    private func stat(_ file: URL) -> (size: Int64, mtimeMs: Int64)? {
        guard let a = try? fm.attributesOfItem(atPath: file.path),
              let size = (a[.size] as? NSNumber)?.int64Value,
              let date = a[.modificationDate] as? Date else { return nil }
        return (size, Int64((date.timeIntervalSince1970 * 1000).rounded(.down)))
    }

    /// `.idx`: `CDX3` | 32B root | u64 len | u64 mtime(ms) | u32 count | (u32 len | 32B hash)*
    private func readIndex(_ idx: URL, _ file: URL) -> Indexed? {
        guard let data = try? Data(contentsOf: idx), let st = stat(file) else { return nil }
        let b = [UInt8](data)
        guard b.count >= 56, readBE32(b, 0) == FileDeltaStore.magic else { return nil }
        let root = Array(b[4..<36])
        func u64(_ o: Int) -> Int64 { (Int64(readBE32(b, o)) << 32) | Int64(readBE32(b, o + 4)) }
        guard u64(36) == st.size, u64(44) == st.mtimeMs else { return nil }
        let count = Int(Int32(bitPattern: readBE32(b, 52)))
        guard count >= 0, Int64(count) <= st.size / Int64(FastCdc.minSize) + 1, b.count == 56 + count * 36 else { return nil }
        var chunks: [ChunkEntry] = []
        var o = 56
        var total: Int64 = 0
        for _ in 0..<count {
            let len = Int(Int32(bitPattern: readBE32(b, o)))
            guard len > 0, len <= FastCdc.maxSize else { return nil }       // corrupt sidecar → rebuild
            chunks.append(ChunkEntry(len: len, hash: Array(b[o + 4..<o + 36])))
            total += Int64(len); o += 36
        }
        guard total == st.size else { return nil }
        return Indexed(chunks: chunks, root: hex(root))
    }

    private func writeIndex(_ idx: URL, _ file: URL, _ chunks: [ChunkEntry], _ root: String) {
        guard let st = stat(file) else { return }
        try? fm.createDirectory(at: idxDir, withIntermediateDirectories: true)
        var out = be32(FileDeltaStore.magic)
        var rootBytes: [UInt8] = []
        var i = root.startIndex
        while i < root.endIndex {
            let j = root.index(i, offsetBy: 2)
            rootBytes.append(UInt8(root[i..<j], radix: 16) ?? 0)
            i = j
        }
        out += rootBytes
        out += be32(UInt32(truncatingIfNeeded: st.size >> 32)) + be32(UInt32(truncatingIfNeeded: st.size))
        out += be32(UInt32(truncatingIfNeeded: st.mtimeMs >> 32)) + be32(UInt32(truncatingIfNeeded: st.mtimeMs))
        out += be32(UInt32(chunks.count))
        for c in chunks { out += be32(UInt32(c.len)); out += c.hash }
        let tmp = idxDir.appendingPathComponent("\(file.lastPathComponent).tmp")
        guard (try? Data(out).write(to: tmp)) != nil else { return }
        _ = rename(tmp.path, idx.path)
    }
}
