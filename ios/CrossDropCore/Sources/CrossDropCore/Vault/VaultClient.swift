import Foundation

/// Native vault client — one encrypted socket per instance, one operation at a time (serialised by
/// `opLock`). Blocking; call from a background thread. Port of `core/vault/VaultClient.kt`.
///
/// Handshake: HELLO → HELLO_ACK (ack advertises `vaultAuth`) → when the vault needs a PIN,
/// VAULT_AUTH carries HKDF(ECDH, pin) — the PIN never appears on the wire.
public final class VaultClient {
    private static let connectTimeout: TimeInterval = 10
    private static let ioTimeout: TimeInterval = 60
    private static let maxListEntries = 250_000

    private let host: String
    private let port: Int
    private let identity: SessionIdentity
    private let deviceName: String
    private let instanceId: String

    /// True after `connect` when the host demands a PIN.
    public private(set) var requiresPin = false
    /// The peer's display name as announced in HELLO_ACK.
    public private(set) var peerName = ""

    private var socket: TCPSocket?
    private var io: FrameIO?
    private let opLock = NSRecursiveLock()
    private var nextTid: Int64 = 1

    public init(host: String, port: Int, identity: SessionIdentity, deviceName: String, instanceId: String) {
        self.host = host; self.port = port; self.identity = identity
        self.deviceName = deviceName; self.instanceId = instanceId
    }

    // MARK: connect

    /// Connect + handshake (+ PIN auth when `pin` given or required). Throws `VaultError(401)` when
    /// the vault needs a PIN or rejects it.
    public func connect(pin: String? = nil) throws {
        opLock.lock(); defer { opLock.unlock() }
        close()      // re-connecting must not leak the previous socket
        let s = try TCPSocket.connect(host: host, port: port, timeout: Self.connectTimeout)
        do {
            s.setTimeout(Self.ioTimeout)
            let io = FrameIO(socket: s)
            try io.write(.hello, Hello(version: protocolVersion, deviceName: deviceName,
                                       publicKeyB64: b64(identity.publicKey), instanceId: instanceId).encodeBytes())
            let (t, p) = try io.read()
            guard t == .helloAck else { throw ProtocolError("expected HELLO_ACK, got \(t)") }
            let ack = try p.decodeJSON(HelloAck.self)
            peerName = ack.deviceName
            requiresPin = ack.vaultAuth == "pin"

            let keys = try SessionCrypto.deriveSession(identity: identity, peerPublicKey: try unb64(ack.publicKeyB64), isInitiator: true)
            io.readCipher = RecordCipher(key: keys.recvKey)
            io.writeCipher = RecordCipher(key: keys.sendKey)

            if requiresPin {
                guard let pin else { throw VaultError(401, "pin required") }
                let token = b64(SessionCrypto.vaultAuthToken(shared: keys.shared, pin: pin))
                try io.write(.vaultAuth, VaultAuth(token: token).encodeBytes())
                let rt: FrameType, rp: [UInt8]
                do { (rt, rp) = try io.read() }
                catch is EOFError { throw VaultError(401, "wrong pin") }
                catch let e as ProtocolError where e.message.hasPrefix("read failed") { throw VaultError(401, "wrong pin") }
                if rt == .vaultPushAck, (try? rp.decodeJSON(VaultAck.self))?.status == "accept" {
                    // authenticated
                } else if rt == .vaultErr {
                    throw decodeErr(rp)
                } else { throw VaultError(401, "auth rejected") }
            }
            socket = s
            self.io = io
        } catch {
            s.close()
            throw error
        }
    }

    // MARK: commands

    /// Directory listing of `path` (may span several frames).
    public func ls(_ path: String = "/") throws -> [VaultDirEntry] {
        try op { io in
            try io.write(.vaultLs, VaultLs(path: path).encodeBytes())
            var all: [VaultDirEntry] = []
            while true {
                let (t, p) = try io.read()
                switch t {
                case .vaultDir:
                    let d = try p.decodeJSON(VaultDir.self)
                    all += d.entries
                    // A hostile host could stream non-final batches forever; no real folder is this big.
                    if all.count > Self.maxListEntries { throw ProtocolError("directory listing too large") }
                    if d.eof { return all }
                case .vaultErr: throw self.decodeErr(p)
                default: throw ProtocolError("expected VAULT_DIR, got \(t)")
                }
            }
        }
    }

    public func stat(_ path: String) throws -> VaultAttr {
        try op { io in
            try io.write(.vaultStat, VaultStat(path: path).encodeBytes())
            let (t, p) = try io.read()
            switch t {
            case .vaultAttr: return try p.decodeJSON(VaultAttr.self)
            case .vaultErr: throw self.decodeErr(p)
            default: throw ProtocolError("expected VAULT_ATTR, got \(t)")
            }
        }
    }

    /// Stream `path[offset ..< offset+length)` into `out` (`length` -1 ⇒ EOF). Returns the host's
    /// VAULT_DONE — its `hash` is BLAKE3 of exactly the bytes sent, verified on receipt.
    @discardableResult
    public func pull(_ path: String, offset: Int64 = 0, length: Int64 = -1,
                     out: ([UInt8]) throws -> Void, onProgress: (Int64) -> Void = { _ in }) throws -> VaultDone {
        try op { io in
            let tid = self.nextTransferId()
            try io.write(.vaultPull, VaultPull(path: path, offset: offset, length: length, transferId: tid).encodeBytes())
            let hasher = Blake3()
            var expect = offset
            var got: Int64 = 0
            while true {
                let (t, p) = try io.read()
                switch t {
                case .vaultData:
                    guard p.count >= 12 else { throw ProtocolError("short VAULT_DATA") }
                    let (dTid, dOff) = VaultWire.readHeader(p)
                    if dTid != tid { throw ProtocolError("pull tid mismatch") }
                    if dOff != expect { throw ProtocolError("pull offset gap") }
                    let body = Array(p[12...])
                    // The host must not send more than was asked for (it would otherwise fill the disk).
                    if length >= 0 && got + Int64(body.count) > length { throw ProtocolError("host sent more than requested") }
                    hasher.update(body)
                    try out(body)
                    expect += Int64(body.count); got += Int64(body.count)
                    onProgress(got)
                case .vaultDone:
                    let done = try p.decodeJSON(VaultDone.self)
                    if !done.hash.isEmpty && done.hash != hasher.hexDigest() { throw VaultError(400, "pull checksum mismatch") }
                    return done
                case .vaultErr: throw self.decodeErr(p)
                case .cancel: throw VaultError(499, "cancelled by host")
                default: throw ProtocolError("expected VAULT_DATA, got \(t)")
                }
            }
        }
    }

    /// Convenience: pull straight into a local file (created/truncated).
    @discardableResult
    public func pull(_ path: String, offset: Int64 = 0, length: Int64 = -1, to url: URL,
                     onProgress: (Int64) -> Void = { _ in }) throws -> VaultDone {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let h = FileHandle(forWritingAtPath: url.path) else { throw VaultError(500, "cannot create \(url.lastPathComponent)") }
        defer { try? h.close() }
        return try pull(path, offset: offset, length: length, out: { try h.write(contentsOf: Data($0)) }, onProgress: onProgress)
    }

    /// Upload `size` bytes from `source` to vault-relative `path`. The host stages the bytes,
    /// verifies our BLAKE3, and commits atomically; the returned `path` is the final (possibly
    /// de-duplicated) vault path.
    @discardableResult
    public func push(_ path: String, size: Int64, mime: String? = nil, source: VaultByteSource,
                     onProgress: (Int64) -> Void = { _ in }) throws -> VaultDone {
        try op { io in
            try io.write(.vaultPushInit, VaultPushInit(path: path, size: size, mime: mime).encodeBytes())
            let (t, p) = try io.read()
            let ack: VaultAck
            switch t {
            case .vaultPushAck: ack = try p.decodeJSON(VaultAck.self)
            case .vaultErr: throw self.decodeErr(p)
            default: throw ProtocolError("expected VAULT_PUSH_ACK, got \(t)")
            }
            if ack.status != "accept" { throw VaultError(403, ack.reason ?? "rejected") }

            let hasher = Blake3()
            var sent: Int64 = 0
            while sent < size {
                let part = try source.read(Int(min(Int64(VaultServer.dataChunk), size - sent)))
                if part.isEmpty { break }
                hasher.update(part)
                try io.write(.vaultData, VaultWire.header(tid: ack.transferId, offset: sent) + part)
                sent += Int64(part.count)
                onProgress(sent)
            }
            if sent < size { throw EOFError() }
            try io.write(.vaultDone, VaultDone(transferId: ack.transferId, hash: hasher.hexDigest()).encodeBytes())
            let (ft, fp) = try io.read()
            switch ft {
            case .vaultDone: return try fp.decodeJSON(VaultDone.self)
            case .vaultErr: throw self.decodeErr(fp)
            default: throw ProtocolError("expected VAULT_DONE, got \(ft)")
            }
        }
    }

    /// Convenience: upload a local file.
    @discardableResult
    public func push(_ path: String, fileURL: URL, mime: String? = nil, onProgress: (Int64) -> Void = { _ in }) throws -> VaultDone {
        let size = ((try? FileManager.default.attributesOfItem(atPath: fileURL.path))?[.size] as? NSNumber)?.int64Value ?? 0
        let r = try VaultFileReader(path: fileURL.path)
        defer { r.close() }
        return try push(path, size: size, mime: mime, source: r, onProgress: onProgress)
    }

    /// Best-effort abort of an in-flight transfer.
    public func cancel() {
        try? io?.write(.cancel, Cancel().encodeBytes())
    }

    public func close() {
        socket?.close()
        socket = nil
        io = nil
    }

    // MARK: impl

    private func nextTransferId() -> Int64 { let t = nextTid; nextTid += 1; return t }

    private func op<T>(_ block: (FrameIO) throws -> T) throws -> T {
        opLock.lock(); defer { opLock.unlock() }
        guard let io else { throw VaultError(0, "not connected") }
        return try block(io)
    }

    private func decodeErr(_ p: [UInt8]) -> VaultError {
        let e = try? p.decodeJSON(VaultErr.self)
        return VaultError(e?.code ?? 500, e?.message ?? "unknown error")
    }
}
