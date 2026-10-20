import Foundation

/// Wire-facing error: `code` maps to an HTTP status / VAULT_ERR code.
public struct VaultError: Error, CustomStringConvertible {
    public let code: Int
    public let message: String
    public init(_ code: Int, _ message: String) { self.code = code; self.message = message }
    public var description: String { "VaultError(\(code)): \(message)" }
    public var localizedDescription: String { message }
}

public struct VaultEntry: Equatable {
    public var name: String
    public var size: Int64
    public var isDir: Bool
    public var mtime: Int64          // seconds since epoch
    public var mime: String?
    public init(name: String, size: Int64, isDir: Bool, mtime: Int64, mime: String?) {
        self.name = name; self.size = size; self.isDir = isDir; self.mtime = mtime; self.mime = mime
    }
}

/// Pull-style byte stream (empty array = EOF).
public protocol VaultByteSource: AnyObject {
    func read(_ max: Int) throws -> [UInt8]
}

/// Random-access reader over a vault file. Caller closes.
public final class VaultFileReader: VaultByteSource {
    private let handle: FileHandle
    public init(path: String, offset: Int64 = 0) throws {
        guard let h = FileHandle(forReadingAtPath: path) else { throw VaultError(404, "not found") }
        handle = h
        if offset > 0 { try h.seek(toOffset: UInt64(offset)) }
    }
    public func read(_ max: Int) throws -> [UInt8] { [UInt8](try handle.read(upToCount: max) ?? Data()) }
    public func close() { try? handle.close() }
}

/// Sandboxed filesystem view for the LAN vault / "wireless drive". Every inbound path — binary
/// protocol, HTTP explorer, or WebDAV mount — passes `sanitize` plus backend resolution, which
/// normalises (Unicode NFC, forward slashes), rejects traversal/hidden segments, and jails the
/// result inside the backend root. Anything outside is a `VaultError(403)`.
public protocol VaultFs: AnyObject {
    func sanitize(_ rawPath: String) throws -> String
    /// Dirs first, then name; dotfiles never listable.
    func list(_ rawPath: String) throws -> [VaultEntry]
    /// 404 if missing or hidden.
    func stat(_ rawPath: String) throws -> VaultEntry
    func hasRoom(forBytes: Int64) -> Bool
    /// Claim `bytes` of free space for an in-flight upload so concurrent uploads can't each pass
    /// `hasRoom` and then jointly fill the disk. Pair every successful reserve with `release`.
    func reserve(_ bytes: Int64) -> Bool
    func release(_ bytes: Int64)
    /// Open a file positioned at `offset` (Range requests, pull streams).
    func open(_ rawPath: String, offset: Int64) throws -> VaultFileReader
    func stageFile(id: Int64) -> URL
    /// Commit a staged file to `rawPath`, creating parents. Collisions get "name (n).ext".
    func commitStaged(_ stage: URL, _ rawPath: String) throws -> String
    func purgeStaging()
    func mimeOf(_ name: String) -> String

    func mkdir(_ rawPath: String) throws -> String
    func putStream(_ rawPath: String, input: VaultByteSource, length: Int64) throws -> String
    func delete(_ rawPath: String) throws
    func move(_ src: String, _ dst: String, overwrite: Bool) throws
    func copy(_ src: String, _ dst: String, overwrite: Bool) throws
}

public extension VaultFs {
    func reserve(_ bytes: Int64) -> Bool { hasRoom(forBytes: bytes) }
    func release(_ bytes: Int64) {}
    func sanitize(_ rawPath: String) throws -> String { try VaultPaths.sanitize(rawPath) }
    func mimeOf(_ name: String) -> String { VaultPaths.mimeType(of: name) }
}

public enum VaultPaths {
    /// Keep at least 32 MB free — uploads stop before the disk is full.
    public static let minFree: Int64 = 32 * 1024 * 1024
    /// `putStream(length:)` sentinel: body length unknown (HTTP chunked) — read until EOF.
    public static let unknownLength: Int64 = -1

    /// Normalise + validate a relative path. Returns "" for the root.
    /// Rejects: NULs, `..`, drive letters, hidden/dot segments.
    public static func sanitize(_ rawPath: String) throws -> String {
        if rawPath.unicodeScalars.contains(where: { $0.value == 0 }) { throw VaultError(400, "null byte in path") }
        // Work on UTF-8 bytes so '/' splitting matches the Kotlin char-level behaviour exactly.
        var bytes = Array(rawPath.precomposedStringWithCanonicalMapping.utf8).map { $0 == 0x5C ? UInt8(0x2F) : $0 }
        while bytes.first == 0x2F { bytes.removeFirst() }
        if bytes.count >= 2, bytes[1] == 0x3A, (bytes[0] | 0x20) >= 0x61, (bytes[0] | 0x20) <= 0x7A {
            throw VaultError(400, "absolute path")
        }
        if bytes == [0x2E] || bytes == [0x2E, 0x2F] { return "" }
        var out: [String] = []
        for seg in bytes.split(separator: 0x2F, omittingEmptySubsequences: false) {
            if seg.isEmpty || Array(seg) == [0x2E] { continue }
            if Array(seg) == [0x2E, 0x2E] { throw VaultError(403, "directory traversal") }
            if seg.first == 0x2E { throw VaultError(403, "hidden path") }
            out.append(String(decoding: seg, as: UTF8.self))
        }
        return out.joined(separator: "/")
    }

    public static func mimeType(of name: String) -> String {
        guard let dot = name.lastIndex(of: ".") else { return "application/octet-stream" }
        return mime[String(name[name.index(after: dot)...]).lowercased()] ?? "application/octet-stream"
    }

    static let mime: [String: String] = [
        "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
        "gif": "image/gif", "webp": "image/webp", "avif": "image/avif",
        "heic": "image/heic", "heif": "image/heif", "bmp": "image/bmp",
        "svg": "image/svg+xml", "ico": "image/x-icon",
        "mp4": "video/mp4", "m4v": "video/mp4", "mkv": "video/x-matroska",
        "webm": "video/webm", "mov": "video/quicktime", "avi": "video/x-msvideo",
        "3gp": "video/3gpp", "ts": "video/mp2t",
        "mp3": "audio/mpeg", "m4a": "audio/mp4", "aac": "audio/aac",
        "flac": "audio/flac", "ogg": "audio/ogg", "opus": "audio/opus",
        "wav": "audio/wav", "wma": "audio/x-ms-wma",
        "pdf": "application/pdf", "txt": "text/plain", "md": "text/markdown",
        "html": "text/html", "htm": "text/html", "css": "text/css",
        "js": "text/javascript", "json": "application/json",
        "csv": "text/csv", "xml": "text/xml",
        "doc": "application/msword",
        "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls": "application/vnd.ms-excel",
        "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt": "application/vnd.ms-powerpoint",
        "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub": "application/epub+zip",
        "zip": "application/zip", "tar": "application/x-tar",
        "gz": "application/gzip", "7z": "application/x-7z-compressed",
        "rar": "application/vnd.rar", "bz2": "application/x-bzip2",
        "apk": "application/vnd.android.package-archive",
        "iso": "application/x-iso9660-image",
        "ttf": "font/ttf", "otf": "font/otf", "woff": "font/woff", "woff2": "font/woff2",
    ]
}
