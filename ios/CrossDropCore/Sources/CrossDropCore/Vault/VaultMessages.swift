import Foundation

// JSON payloads for the vault frames (frame IDs 0x10–0x1A in FrameType). Field names and defaults
// match `core/vault/VaultMessages.kt`; decoding tolerates unknown keys and missing defaulted fields.

public struct VaultLs: Codable {
    public var path: String
    public init(path: String = "/") { self.path = path }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? "/"
    }
    enum K: String, CodingKey { case path }
}

public struct VaultDirEntry: Codable, Equatable {
    public var name: String
    public var size: Int64
    public var isDir: Bool
    public var mtime: Int64
    public var mime: String?
    public init(name: String, size: Int64 = 0, isDir: Bool = false, mtime: Int64 = 0, mime: String? = nil) {
        self.name = name; self.size = size; self.isDir = isDir; self.mtime = mtime; self.mime = mime
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        isDir = try c.decodeIfPresent(Bool.self, forKey: .isDir) ?? false
        mtime = try c.decodeIfPresent(Int64.self, forKey: .mtime) ?? 0
        mime = try c.decodeIfPresent(String.self, forKey: .mime)
    }
    enum K: String, CodingKey { case name, size, isDir, mtime, mime }
}

/// Directory batch; a listing may span several frames (`eof` marks the last).
public struct VaultDir: Codable {
    public var path: String
    public var entries: [VaultDirEntry]
    public var eof: Bool
    public init(path: String = "", entries: [VaultDirEntry], eof: Bool = true) {
        self.path = path; self.entries = entries; self.eof = eof
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        entries = try c.decode([VaultDirEntry].self, forKey: .entries)
        eof = try c.decodeIfPresent(Bool.self, forKey: .eof) ?? true
    }
    enum K: String, CodingKey { case path, entries, eof }
}

public struct VaultStat: Codable {
    public var path: String
    public init(path: String) { self.path = path }
}

public struct VaultAttr: Codable, Equatable {
    public var name: String
    public var size: Int64
    public var isDir: Bool
    public var mtime: Int64
    public var mime: String?
    /// BLAKE3 hex for files ≤ 256 MB; nil for dirs/huge files.
    public var hash: String?
    public init(name: String, size: Int64 = 0, isDir: Bool = false, mtime: Int64 = 0, mime: String? = nil, hash: String? = nil) {
        self.name = name; self.size = size; self.isDir = isDir; self.mtime = mtime; self.mime = mime; self.hash = hash
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        isDir = try c.decodeIfPresent(Bool.self, forKey: .isDir) ?? false
        mtime = try c.decodeIfPresent(Int64.self, forKey: .mtime) ?? 0
        mime = try c.decodeIfPresent(String.self, forKey: .mime)
        hash = try c.decodeIfPresent(String.self, forKey: .hash)
    }
    enum K: String, CodingKey { case name, size, isDir, mtime, mime, hash }
}

/// Pull `length` bytes starting at `offset` (-1 = to EOF).
public struct VaultPull: Codable {
    public var path: String
    public var offset: Int64
    public var length: Int64
    public var transferId: Int64
    public init(path: String, offset: Int64 = 0, length: Int64 = -1, transferId: Int64 = 0) {
        self.path = path; self.offset = offset; self.length = length; self.transferId = transferId
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        path = try c.decode(String.self, forKey: .path)
        offset = try c.decodeIfPresent(Int64.self, forKey: .offset) ?? 0
        length = try c.decodeIfPresent(Int64.self, forKey: .length) ?? -1
        transferId = try c.decodeIfPresent(Int64.self, forKey: .transferId) ?? 0
    }
    enum K: String, CodingKey { case path, offset, length, transferId }
}

public struct VaultPushInit: Codable {
    public var path: String
    public var size: Int64
    public var mime: String?
    public init(path: String, size: Int64, mime: String? = nil) { self.path = path; self.size = size; self.mime = mime }
}

/// Reused for both PUSH init replies and PIN auth replies.
public struct VaultAck: Codable {
    public var status: String
    public var transferId: Int64
    public var reason: String?
    public init(status: String, transferId: Int64 = 0, reason: String? = nil) {
        self.status = status; self.transferId = transferId; self.reason = reason
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        status = try c.decode(String.self, forKey: .status)
        transferId = try c.decodeIfPresent(Int64.self, forKey: .transferId) ?? 0
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
    }
    enum K: String, CodingKey { case status, transferId, reason }
}

public struct VaultAuth: Codable {
    public var token: String      // base64 HKDF token
    public init(token: String) { self.token = token }
}

public struct VaultDone: Codable {
    public var transferId: Int64
    public var hash: String
    public var path: String?
    public init(transferId: Int64, hash: String, path: String? = nil) {
        self.transferId = transferId; self.hash = hash; self.path = path
    }
}

public struct VaultErr: Codable {
    public var code: Int
    public var message: String
    public init(code: Int, message: String = "") { self.code = code; self.message = message }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        code = try c.decode(Int.self, forKey: .code)
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }
    enum K: String, CodingKey { case code, message }
}

/// `[u32 tid][u64 offset]` prefix for VAULT_DATA payloads.
enum VaultWire {
    static func header(tid: Int64, offset: Int64) -> [UInt8] {
        var h = [UInt8](repeating: 0, count: 12)
        let t = UInt32(truncatingIfNeeded: tid)
        h[0] = UInt8(t >> 24); h[1] = UInt8((t >> 16) & 0xFF); h[2] = UInt8((t >> 8) & 0xFF); h[3] = UInt8(t & 0xFF)
        let o = UInt64(bitPattern: offset)
        for i in 0..<8 { h[4 + i] = UInt8((o >> UInt64(56 - i * 8)) & 0xFF) }
        return h
    }

    static func readHeader(_ p: [UInt8]) -> (tid: Int64, offset: Int64) {
        let tid = Int64(readBE32(p, 0))
        var off: UInt64 = 0
        for i in 4..<12 { off = (off << 8) | UInt64(p[i]) }
        return (tid, Int64(bitPattern: off))
    }

    static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        if a.count != b.count { return false }
        var d: UInt8 = 0
        for i in 0..<a.count { d |= a[i] ^ b[i] }
        return d == 0
    }
}
