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
    /// What the item is in a "Switch phones" move (`ItemCategory`); nil = an ordinary file.
    public var cat: String?
    /// Original capture/modification time, epoch ms.
    public var mtime: Int64?
    /// `OfferKind.backup`: the sender's stable id for this item, so the receiver can recognise it later.
    public var key: String?
    public init(name: String, size: Int64, mime: String? = nil, cat: String? = nil, mtime: Int64? = nil, key: String? = nil) {
        self.name = name; self.size = size; self.mime = mime; self.cat = cat; self.mtime = mtime; self.key = key
    }
}

/// Values of `FileMeta.cat`.
public enum ItemCategory {
    public static let photo = "photo"
    public static let video = "video"
    public static let contacts = "contacts"
}

/// Values of `Offer.kind`. Receivers that predate a kind treat the offer as plain files.
public enum OfferKind {
    public static let files = "files"
    public static let text = "text"
    public static let camera = "camera"
    public static let switchPhones = "switch"
    /// One or more contact cards: a single `.vcf`; `Offer.text` carries a photo-less copy for the prompt.
    public static let contact = "contact"
    /// One batch of a phone backup to a paired computer; carries `Offer.pairProof`. Never prompts.
    public static let backup = "backup"
}

/// Longest `Offer.text` a sender inlines (the file copy carries anything longer).
public let maxInlineText = 64 * 1024

public struct Offer: Codable {
    public var transferId: String
    public var files: [FileMeta]
    public var kind: String?
    /// `OfferKind.text`: the shared text itself.
    public var text: String?
    /// Set when the sender holds a `TrustGrant` from this receiver: its id and
    /// base64 HMAC-SHA256(token, session binding ‖ transferId).
    public var trustId: String?
    public var trustProof: String?
    /// `OfferKind.switchPhones`: where this batch sits in the whole move.
    public var batch: BatchInfo?
    /// Set when sender and receiver are paired ("My devices"): base64 HMAC(pairing key, "offer" ‖ binding ‖ transferId).
    public var pairProof: String?
    public init(transferId: String, files: [FileMeta], kind: String? = nil, text: String? = nil,
                trustId: String? = nil, trustProof: String? = nil, batch: BatchInfo? = nil, pairProof: String? = nil) {
        self.transferId = transferId; self.files = files; self.kind = kind; self.text = text
        self.trustId = trustId; self.trustProof = trustProof; self.batch = batch; self.pairProof = pairProof
    }
}

/// Totals of a whole "Switch phones" move, repeated on each batch's offer.
public struct BatchInfo: Codable, Equatable {
    public var index: Int
    public var count: Int
    public var photos: Int
    public var videos: Int
    public var contacts: Int
    public var files: Int
    public var totalBytes: Int64
    public init(index: Int, count: Int, photos: Int = 0, videos: Int = 0, contacts: Int = 0, files: Int = 0, totalBytes: Int64 = 0) {
        self.index = index; self.count = count; self.photos = photos; self.videos = videos
        self.contacts = contacts; self.files = files; self.totalBytes = totalBytes
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        index = try c.decode(Int.self, forKey: .index)
        count = try c.decode(Int.self, forKey: .count)
        photos = try c.decodeIfPresent(Int.self, forKey: .photos) ?? 0
        videos = try c.decodeIfPresent(Int.self, forKey: .videos) ?? 0
        contacts = try c.decodeIfPresent(Int.self, forKey: .contacts) ?? 0
        files = try c.decodeIfPresent(Int.self, forKey: .files) ?? 0
        totalBytes = try c.decodeIfPresent(Int64.self, forKey: .totalBytes) ?? 0
    }
    enum K: String, CodingKey { case index, count, photos, videos, contacts, files, totalBytes }
}

/// Receiver → sender inside an encrypted ACCEPT: offers of `kind` that prove they hold `token`
/// skip the receiver's prompt until it stops trusting or `ttlMs` passes. Memory only.
public struct TrustGrant: Codable, Equatable {
    public var id: String
    /// 32 random bytes, base64.
    public var token: String
    public var kind: String
    public var ttlMs: Int64
    public init(id: String, token: String, kind: String, ttlMs: Int64) {
        self.id = id; self.token = token; self.kind = kind; self.ttlMs = ttlMs
    }
}

public struct Accept: Codable {
    public var transferId: String
    public var delta: Bool
    public var push: Bool
    public var trust: TrustGrant?
    /// Answer to `Offer.pairProof`: base64 HMAC(pairing key, "accept" ‖ binding ‖ transferId).
    public var pairProof: String?
    public init(transferId: String, delta: Bool = false, push: Bool = false, trust: TrustGrant? = nil, pairProof: String? = nil) {
        self.transferId = transferId; self.delta = delta; self.push = push; self.trust = trust; self.pairProof = pairProof
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        transferId = try c.decode(String.self, forKey: .transferId)
        delta = try c.decodeIfPresent(Bool.self, forKey: .delta) ?? false
        push = try c.decodeIfPresent(Bool.self, forKey: .push) ?? false
        trust = try? c.decodeIfPresent(TrustGrant.self, forKey: .trust)
        pairProof = try c.decodeIfPresent(String.self, forKey: .pairProof)
    }
    enum K: String, CodingKey { case transferId, delta, push, trust, pairProof }
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

// MARK: control exchanges (no files)

/// ANNOUNCE: "I'm here; reach me on `port`". Lets a device that joined another's private link (or any network
/// where multicast is blocked) appear on that device's radar. The answer's `port` is 0: "the port you connected to".
public struct Announce: Codable, Equatable {
    public var port: Int
    public init(port: Int) { self.port = port }
}

/// PAIR, first frame: the initiator asks to become one of the receiver's devices under `pairId` (32 hex).
public struct PairRequest: Codable, Equatable {
    public var pairId: String
    public init(pairId: String) { self.pairId = pairId }
}

/// PAIR, answer: the human on the receiving side agreed (or not, or didn't answer in time).
public struct PairReply: Codable, Equatable {
    public var accepted: Bool
    public init(accepted: Bool) { self.accepted = accepted }
}

/// PAIR_CHECK, both directions. Request: a fresh `nonce` (base64) and one proof per pairing the initiator holds,
/// HMAC(key, "check-i" ‖ binding ‖ nonce). Answer: the same nonce and, if one of them is a pairing this device holds
/// too, that pairing's HMAC(key, "check-r" ‖ binding ‖ nonce); otherwise no proofs.
public struct PairCheck: Codable, Equatable {
    public var nonce: String
    public var proofs: [String]
    public init(nonce: String, proofs: [String] = []) { self.nonce = nonce; self.proofs = proofs }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        nonce = try c.decode(String.self, forKey: .nonce)
        proofs = try c.decodeIfPresent([String].self, forKey: .proofs) ?? []
    }
    enum K: String, CodingKey { case nonce, proofs }
}

/// One backed-up item as the phone remembers it: its key, whole-file BLAKE3 root and size.
public struct VerifyItem: Codable, Equatable {
    public var key: String
    public var root: String
    public var size: Int64
    public init(key: String, root: String, size: Int64) { self.key = key; self.root = root; self.size = size }
}

/// BACKUP_VERIFY (only after a successful PAIR_CHECK on the same connection): "do you still hold these?"
public struct VerifyRequest: Codable, Equatable {
    public var items: [VerifyItem]
    public init(items: [VerifyItem]) { self.items = items }
}

/// The keys of the items the receiver re-read and found intact.
public struct VerifyReply: Codable, Equatable {
    public var ok: [String]
    public init(ok: [String]) { self.ok = ok }
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
