import Foundation
import Darwin

/// The built-in vault backend: a file tree under `rootDir`, jailed by canonical-path comparison.
/// Upload staging is a hidden `.staging` dir inside the jail unless a separate `stagingRoot` is
/// given (hosts sharing a user's own folder pass one so nothing but the user's files appears).
public final class FileVaultFs: VaultFs {
    /// Canonical jail root — created on first use.
    public let root: String
    private let rootPrefix: String
    private let stagingRoot: String?
    private let fm = FileManager.default

    public init(rootDir: URL, stagingRoot: URL? = nil) {
        try? fm.createDirectory(at: rootDir, withIntermediateDirectories: true)
        root = Self.canonical(rootDir.path)
        rootPrefix = root == "/" ? "/" : root + "/"
        self.stagingRoot = stagingRoot?.path
    }

    public var stagingDir: URL {
        let p = stagingRoot ?? (root + "/.staging")
        try? fm.createDirectory(atPath: p, withIntermediateDirectories: true)
        return URL(fileURLWithPath: p, isDirectory: true)
    }

    // MARK: jail

    /// Resolve a client path to a jailed absolute path. `""`, `"/"`, `"."` all resolve to the root.
    public func resolve(_ rawPath: String) throws -> String {
        let clean = try sanitize(rawPath)
        let target = clean.isEmpty ? root : Self.canonical(root + "/" + clean)
        if target != root && !target.hasPrefix(rootPrefix) { throw VaultError(403, "path escapes vault root") }
        return target
    }

    /// Relative jail path of `path`, or "" for the root itself.
    public func relative(_ path: String) -> String {
        let c = Self.canonical(path)
        return c == root ? "" : String(c.dropFirst(rootPrefix.count))
    }

    /// Parent must stay inside the jail too (symlinked parents).
    private func jailCheckParent(_ target: String) throws {
        let parent = (target as NSString).deletingLastPathComponent
        let c = Self.canonical(parent)
        if c != root && !c.hasPrefix(rootPrefix) { throw VaultError(403, "target escapes vault root") }
    }

    /// realpath() of the deepest existing prefix, with the not-yet-existing tail appended.
    static func canonical(_ path: String) -> String {
        var comps: [String] = []
        var cur = URL(fileURLWithPath: path).standardizedFileURL.path
        while true {
            var buf = [CChar](repeating: 0, count: Int(PATH_MAX))
            if realpath(cur, &buf) != nil {
                let base = String(cString: buf)
                return comps.isEmpty ? base : (base == "/" ? "" : base) + "/" + comps.reversed().joined(separator: "/")
            }
            if cur == "/" || cur.isEmpty { return path }
            comps.append((cur as NSString).lastPathComponent)
            cur = (cur as NSString).deletingLastPathComponent
        }
    }

    private func st(_ path: String) -> Darwin.stat? {
        var s = Darwin.stat()
        return fstatat(AT_FDCWD, path, &s, 0) == 0 ? s : nil
    }
    private func isDir(_ path: String) -> Bool { st(path).map { ($0.st_mode & S_IFMT) == S_IFDIR } ?? false }
    private func isFile(_ path: String) -> Bool { st(path).map { ($0.st_mode & S_IFMT) == S_IFREG } ?? false }
    private func exists(_ path: String) -> Bool { st(path) != nil }

    /// A symlink inside the vault that points outside it must not even be listed (size/mtime leak).
    private func inJail(_ path: String) -> Bool {
        let c = Self.canonical(path)
        return c == root || c.hasPrefix(rootPrefix)
    }

    func entryOf(_ path: String) -> VaultEntry {
        let s = st(path)
        let file = s.map { ($0.st_mode & S_IFMT) == S_IFREG } ?? false
        let name = (path as NSString).lastPathComponent
        return VaultEntry(
            name: name,
            size: file ? Int64(s!.st_size) : 0,
            isDir: s.map { ($0.st_mode & S_IFMT) == S_IFDIR } ?? false,
            mtime: s.map { Int64($0.st_mtimespec.tv_sec) } ?? 0,
            mime: file ? mimeOf(name) : nil)
    }

    // MARK: read surface

    public func list(_ rawPath: String) throws -> [VaultEntry] {
        let dir = try resolve(rawPath)
        if !exists(dir) { throw VaultError(404, "not found") }
        if !isDir(dir) { throw VaultError(400, "not a directory") }
        let names = (try? fm.contentsOfDirectory(atPath: dir)) ?? []
        return names.filter { !$0.hasPrefix(".") }
            .filter { inJail(dir + "/" + $0) }
            .map { entryOf(dir + "/" + $0) }
            .sorted { a, b in
                if a.isDir != b.isDir { return a.isDir }
                return a.name.lowercased() < b.name.lowercased()
            }
    }

    public func stat(_ rawPath: String) throws -> VaultEntry {
        let f = try resolve(rawPath)
        if !exists(f) || (f != root && (f as NSString).lastPathComponent.hasPrefix(".")) { throw VaultError(404, "not found") }
        return entryOf(f)
    }

    private let reservedLock = NSLock()
    private var reserved: Int64 = 0

    /// Free bytes on the vault's volume (nil when statfs fails).
    private func freeBytes() -> Int64? {
        var s = statfs()
        guard statfs(root, &s) == 0 else { return nil }
        let (prod, overflow) = Int64(s.f_bavail).multipliedReportingOverflow(by: Int64(s.f_bsize))
        return overflow ? Int64.max : prod
    }

    /// Overflow-safe: `forBytes` comes straight off the wire (it may be Int64.max).
    public func hasRoom(forBytes: Int64) -> Bool {
        reservedLock.lock(); defer { reservedLock.unlock() }
        return roomLocked(forBytes)
    }

    private func roomLocked(_ bytes: Int64) -> Bool {
        guard bytes >= 0 else { return false }
        guard let free = freeBytes() else { return true }
        let usable = free - VaultPaths.minFree - reserved
        return usable > 0 && bytes <= usable
    }

    public func reserve(_ bytes: Int64) -> Bool {
        reservedLock.lock(); defer { reservedLock.unlock() }
        guard roomLocked(bytes) else { return false }
        reserved += bytes
        return true
    }

    public func release(_ bytes: Int64) {
        reservedLock.lock(); reserved = max(0, reserved - bytes); reservedLock.unlock()
    }

    public func open(_ rawPath: String, offset: Int64) throws -> VaultFileReader {
        let f = try resolve(rawPath)
        if !isFile(f) { throw VaultError(404, "not found") }
        return try VaultFileReader(path: f, offset: offset)
    }

    public func stageFile(id: Int64) -> URL { stagingDir.appendingPathComponent("\(id).part") }

    public func commitStaged(_ stage: URL, _ rawPath: String) throws -> String {
        let clean = try sanitize(rawPath)
        if clean.isEmpty { throw VaultError(400, "empty target") }
        var target = try resolve(clean)
        try? fm.createDirectory(atPath: (target as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
        try jailCheckParent(target)
        if exists(target) { target = dedupe(target) }
        try moveReplacing(stage.path, target)
        return relative(target)
    }

    public func purgeStaging() {
        let dir = stagingDir.path
        for n in (try? fm.contentsOfDirectory(atPath: dir)) ?? [] { try? fm.removeItem(atPath: dir + "/" + n) }
    }

    // MARK: write surface

    public func mkdir(_ rawPath: String) throws -> String {
        let clean = try sanitize(rawPath)
        if clean.isEmpty { throw VaultError(405, "cannot create root") }
        let target = try resolve(clean)
        if exists(target) { throw VaultError(405, "already exists") }
        try jailCheckParent(target)
        if !isDir((target as NSString).deletingLastPathComponent) { throw VaultError(409, "parent missing") }
        if Darwin.mkdir(target, 0o755) != 0 { throw VaultError(500, "mkdir failed") }
        return relative(target)
    }

    /// `length` ≥ 0: exactly that many bytes (413 up-front when the volume can't hold them).
    /// `VaultPaths.unknownLength` (chunked PUT, Finder): read to EOF, claiming free space chunk by chunk.
    public func putStream(_ rawPath: String, input: VaultByteSource, length: Int64) throws -> String {
        let clean = try sanitize(rawPath)
        if clean.isEmpty { throw VaultError(400, "empty target") }
        let target = try resolve(clean)
        if isDir(target) { throw VaultError(409, "target is a directory") }
        try jailCheckParent(target)
        if !isDir((target as NSString).deletingLastPathComponent) { throw VaultError(409, "parent missing") }
        let unknown = length == VaultPaths.unknownLength
        if length < 0 && !unknown { throw VaultError(400, "bad length") }
        var claimed: Int64 = 0
        if !unknown {
            if !reserve(length) { throw VaultError(413, "insufficient storage") }
            claimed = length
        }
        defer { release(claimed) }
        let tmp = stagingDir.appendingPathComponent("put-\(DispatchTime.now().uptimeNanoseconds).tmp")
        defer { try? fm.removeItem(at: tmp) }
        guard fm.createFile(atPath: tmp.path, contents: nil), let out = FileHandle(forWritingAtPath: tmp.path) else {
            throw VaultError(500, "cannot stage upload")
        }
        var left = length
        do {
            while unknown || left > 0 {
                let part = try input.read(unknown ? 64 * 1024 : Int(min(64 * 1024, left)))
                if part.isEmpty { break }
                if unknown {
                    if !reserve(Int64(part.count)) { throw VaultError(413, "insufficient storage") }
                    claimed += Int64(part.count)
                } else { left -= Int64(part.count) }
                try out.write(contentsOf: Data(part))
            }
            try out.close()
        } catch {
            try? out.close()
            throw error
        }
        if !unknown && left != 0 { throw VaultError(400, "body truncated") }
        try moveReplacing(tmp.path, target)
        return relative(target)
    }

    public func delete(_ rawPath: String) throws {
        let f = try resolve(rawPath)
        if f == root { throw VaultError(403, "cannot delete vault root") }
        if !exists(f) { throw VaultError(404, "not found") }
        do { try fm.removeItem(atPath: f) } catch { throw VaultError(500, "delete failed") }
    }

    public func move(_ src: String, _ dst: String, overwrite: Bool) throws {
        let s = try resolve(src)
        if s == root { throw VaultError(403, "cannot move vault root") }
        if !exists(s) { throw VaultError(404, "not found") }
        let d = try resolve(dst)
        if d == root { throw VaultError(403, "cannot replace vault root") }
        if d == s { throw VaultError(400, "same source and destination") }
        if d.hasPrefix(s + "/") { throw VaultError(409, "cannot move a folder into itself") }
        // Replacing an ancestor of the source would delete the source along with it.
        if s.hasPrefix(d + "/") { throw VaultError(409, "destination contains the source") }
        if exists(d) {
            if !overwrite { throw VaultError(412, "destination exists") }
            do { try fm.removeItem(atPath: d) } catch { throw VaultError(500, "cannot replace destination") }
        }
        try jailCheckParent(d)
        if !isDir((d as NSString).deletingLastPathComponent) { throw VaultError(409, "parent missing") }
        if Darwin.rename(s, d) != 0 {
            // cross-filesystem or lock issues — fall back to copy+delete
            do { try fm.copyItem(atPath: s, toPath: d); try fm.removeItem(atPath: s) }
            catch { throw VaultError(500, "move cleanup failed") }
        }
    }

    public func copy(_ src: String, _ dst: String, overwrite: Bool) throws {
        let s = try resolve(src)
        if !exists(s) { throw VaultError(404, "not found") }
        let d = try resolve(dst)
        if d == root { throw VaultError(403, "cannot replace vault root") }
        if d == s { throw VaultError(400, "same source and destination") }
        if d.hasPrefix(s + "/") { throw VaultError(409, "cannot copy a folder into itself") }
        if s.hasPrefix(d + "/") { throw VaultError(409, "destination contains the source") }
        if exists(d) {
            if !overwrite { throw VaultError(412, "destination exists") }
            do { try fm.removeItem(atPath: d) } catch { throw VaultError(500, "cannot replace destination") }
        }
        try jailCheckParent(d)
        if !isDir((d as NSString).deletingLastPathComponent) { throw VaultError(409, "parent missing") }
        do { try fm.copyItem(atPath: s, toPath: d) } catch { throw VaultError(500, "copy failed") }
    }

    // MARK: helpers

    /// rename(2) replaces atomically; across volumes fall back to copy-to-temp + rename.
    private func moveReplacing(_ src: String, _ dst: String) throws {
        if Darwin.rename(src, dst) == 0 { return }
        guard errno == EXDEV else { throw VaultError(500, "move failed") }
        let tmp = dst + ".\(DispatchTime.now().uptimeNanoseconds).part"
        do {
            try fm.copyItem(atPath: src, toPath: tmp)
            guard Darwin.rename(tmp, dst) == 0 else { throw VaultError(500, "move failed") }
            try? fm.removeItem(atPath: src)
        } catch let e as VaultError { try? fm.removeItem(atPath: tmp); throw e }
        catch { try? fm.removeItem(atPath: tmp); throw VaultError(500, "move failed") }
    }

    /// "a.jpg" → "a (2).jpg", "dir" → "dir (2)".
    private func dedupe(_ path: String) -> String {
        let ns = path as NSString
        let name = ns.lastPathComponent
        let parent = ns.deletingLastPathComponent
        var base = name
        var ext: String?
        if let dot = name.lastIndex(of: ".") {
            base = String(name[..<dot])
            ext = String(name[name.index(after: dot)...])
        }
        var n = 2
        while true {
            let cand = parent + "/" + (ext != nil ? "\(base) (\(n)).\(ext!)" : "\(base) (\(n))")
            if !exists(cand) { return cand }
            n += 1
        }
    }
}
