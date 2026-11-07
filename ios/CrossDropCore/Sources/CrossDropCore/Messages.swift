import Foundation

/// v2 adds content-defined-chunk delta/resume; v3 adds push mode.
public let protocolVersion = 3

public struct Hello: Codable {
    public var version: Int
    public var deviceName: String
    public var publicKeyB64: String
    public var instanceId: String
    public init(version: Int, deviceName: String, publicKeyB64: String, instanceId: String) {
        self.version = version; self.deviceName = deviceName; self.publicKeyB64 = publicKeyB64; self.instanceId = instanceId
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1     // missing ⇒ v1
        deviceName = try c.decode(String.self, forKey: .deviceName)
        publicKeyB64 = try c.decode(String.self, forKey: .publicKeyB64)
        instanceId = try c.decode(String.self, forKey: .instanceId)
    }
    enum K: String, CodingKey { case version, deviceName, publicKeyB64, instanceId }
}

public struct HelloAck: Codable {
    public var version: Int
    public var deviceName: String
    public var publicKeyB64: String
    public var instanceId: String
    public var vaultAuth: String?
    public init(version: Int, deviceName: String, publicKeyB64: String, instanceId: String, vaultAuth: String? = nil) {
        self.version = version; self.deviceName = deviceName; self.publicKeyB64 = publicKeyB64
        self.instanceId = instanceId; self.vaultAuth = vaultAuth
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        deviceName = try c.decode(String.self, forKey: .deviceName)
        publicKeyB64 = try c.decode(String.self, forKey: .publicKeyB64)
        instanceId = try c.decode(String.self, forKey: .instanceId)
        vaultAuth = try c.decodeIfPresent(String.self, forKey: .vaultAuth)
    }
    enum K: String, CodingKey { case version, deviceName, publicKeyB64, instanceId, vaultAuth }
}

public struct FileMeta: Codable, Equatable {
    public var name: String
    public var size: Int64
    public var mime: String?
    public init(name: String, size: Int64, mime: String? = nil) { self.name = name; self.size = size; self.mime = mime }
}

public struct Offer: Codable {
    public var transferId: String
    public var files: [FileMeta]
    public init(transferId: String, files: [FileMeta]) { self.transferId = transferId; self.files = files }
}

public struct Accept: Codable {
    public var transferId: String
    public var delta: Bool
    public var push: Bool
    public init(transferId: String, delta: Bool = false, push: Bool = false) {
        self.transferId = transferId; self.delta = delta; self.push = push
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        transferId = try c.decode(String.self, forKey: .transferId)
        delta = try c.decodeIfPresent(Bool.self, forKey: .delta) ?? false
        push = try c.decodeIfPresent(Bool.self, forKey: .push) ?? false
    }
    enum K: String, CodingKey { case transferId, delta, push }
}

public struct Decline: Codable {
    public var transferId: String
    public var reason: String?
}

public struct Done: Codable {
    public var transferId: String
    public var sha256: String
    public var roots: [String]?
    public init(transferId: String, sha256: String, roots: [String]? = nil) {
        self.transferId = transferId; self.sha256 = sha256; self.roots = roots
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        transferId = try c.decode(String.self, forKey: .transferId)
        sha256 = try c.decodeIfPresent(String.self, forKey: .sha256) ?? ""
        roots = try c.decodeIfPresent([String].self, forKey: .roots)
    }
    enum K: String, CodingKey { case transferId, sha256, roots }
}

public struct Cancel: Codable {
    public var reason: String?
    public init(reason: String? = nil) { self.reason = reason }
}

extension Encodable {
    /// Kotlin side uses `encodeDefaults = true`; nil optionals are simply omitted, which the
    /// Kotlin decoder accepts (all nullable fields default to null).
    func encodeBytes() throws -> [UInt8] { [UInt8](try JSONEncoder().encode(self)) }
}

extension Array where Element == UInt8 {
    func decodeJSON<T: Decodable>(_ type: T.Type) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: Data(self)) }
        catch { throw ProtocolError("malformed \(T.self) message") }
    }
}

func b64(_ b: [UInt8]) -> String { Data(b).base64EncodedString() }
func unb64(_ s: String) throws -> [UInt8] {
    guard let d = Data(base64Encoded: s) else { throw ProtocolError("bad base64") }
    return [UInt8](d)
}
