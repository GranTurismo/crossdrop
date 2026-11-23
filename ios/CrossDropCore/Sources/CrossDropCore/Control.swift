import Foundation

/// A pairing this device holds: its id and the 32-byte key both devices derived when they paired.
public struct PairSecret: Equatable {
    public let id: String
    public let key: [UInt8]
    public init(id: String, key: [UInt8]) { self.id = id; self.key = key }
}

/// The receiving side of the exchanges that carry no files (Kotlin `ControlHandler`). The transfer listener hands
/// every connection whose first frame isn't an OFFER to `TransferEngine.serveControl`, which calls this. Every method
/// but `announced` has a harmless default (decline, hold nothing), so an app implements only what it takes part in.
/// Called on the engine's thread; the blocking ones (`pairRequested`) may wait for a human there.
public protocol ControlHandler: AnyObject {
    /// A device told us where to reach it: `host` is the address it connected from, `port` its listener.
    func announced(name: String, instanceId: String, host: String, port: Int)
    /// A device says it was just shaken (shake to connect); `port` is its listener, 0 if it didn't say.
    func shaken(name: String, instanceId: String, host: String, port: Int)
    /// A device asks to pair (`purpose`: `PairPurpose.devices`, or nil for a phone that wants to back up here). Blocks
    /// until the human here answered (both screens show `sas`); false, or no answer, declines.
    func pairRequested(name: String, sas: String, purpose: String?) -> Bool
    /// The human agreed and both devices now hold `key` under `pairId`.
    func paired(pairId: String, name: String, key: [UInt8], purpose: String?)
    /// The pairings tried against a PAIR_CHECK.
    func pairings() -> [PairSecret]
    /// The device holding pairing `pairId` (proven on this connection) copied `text`. Put it on this device's clipboard if
    /// clipboard sharing with that device is on here, and return whether it was taken. `clipId` is fresh for every copy:
    /// one seen before is a replay and must be refused.
    func clipboard(pairId: String, clipId: String, text: String) -> Bool
}

extension ControlHandler {
    public func shaken(name: String, instanceId: String, host: String, port: Int) {}
    public func pairRequested(name: String, sas: String, purpose: String?) -> Bool { false }
    public func paired(pairId: String, name: String, key: [UInt8], purpose: String?) {}
    public func pairings() -> [PairSecret] { [] }
    public func clipboard(pairId: String, clipId: String, text: String) -> Bool { false }
}

/// The initiator's end of a connection that carries no files, opened by `TransferEngine.control`. The handshake is
/// done and every frame is sealed. One exchange per connection, except that a successful `pairCheck` may be followed by
/// any number of `clipboard` calls.
public final class ControlChannel {
    let io: FrameIO
    let keys: SessionKeys
    /// The other device's name (cleaned for display) and per-launch id, from its HELLO_ACK.
    public let peerName: String
    public let peerInstanceId: String
    /// What the other device runs on (`android`, `ios`, `mac`, …), when its HELLO_ACK said.
    public let peerOS: String?
    /// The 6-digit code both screens would show for this connection.
    public var sas: String { keys.sas }
    private var matched: PairSecret?

    init(io: FrameIO, keys: SessionKeys, peerName: String, peerInstanceId: String, peerOS: String? = nil) {
        self.io = io; self.keys = keys; self.peerName = peerName; self.peerInstanceId = peerInstanceId; self.peerOS = peerOS
    }

    /// Tell the other device where to reach us. Returns once it answered.
    public func announce(listenPort: Int) throws {
        try io.write(.announce, Announce(port: listenPort).encodeBytes())
        let (type, _) = try io.read()
        guard type == .announce else { throw ProtocolError("expected ANNOUNCE, got \(type)") }
    }

    /// Tell the other device this one was just shaken (`listenPort` lets it reach us back). Returns once it answered.
    public func shake(listenPort: Int) throws {
        try io.write(.shake, Shake(port: listenPort).encodeBytes())
        let (type, _) = try io.read()
        guard type == .shake else { throw ProtocolError("expected SHAKE, got \(type)") }
    }

    /// Ask the other device to pair under `pairId`. Its human sees `sas` and answers; this waits up to `wait` seconds.
    /// Returns the pairing key when they agreed, nil when they declined (or didn't answer in time on their side).
    public func pair(pairId: String, purpose: String? = PairPurpose.devices,
                     wait: TimeInterval = ControlChannel.pairWait) throws -> [UInt8]? {
        guard ControlChannel.isPairId(pairId) else { throw ProtocolError("bad pair id") }
        try io.write(.pair, PairRequest(pairId: pairId, purpose: purpose).encodeBytes())
        io.socket.setTimeout(wait)
        let (type, payload) = try io.read()
        guard type == .pair else { throw ProtocolError("expected PAIR, got \(type)") }
        guard try payload.decodeJSON(PairReply.self).accepted else { return nil }
        return SessionCrypto.pairKey(shared: keys.shared, binding: keys.binding, pairId: pairId)
    }

    /// Which of `held` the other device holds too, proven both ways over a fresh nonce; nil when none. Strangers learn
    /// nothing they could link to this device later.
    public func pairCheck(_ held: [PairSecret]) throws -> PairSecret? {
        guard !held.isEmpty else { return nil }
        let nonce = randomBytes(16)
        let proofs = held.prefix(ControlChannel.maxProofs).map {
            b64(SessionCrypto.pairProof(key: $0.key, label: "check-i", binding: keys.binding, extra: nonce))
        }
        try io.write(.pairCheck, PairCheck(nonce: b64(nonce), proofs: Array(proofs)).encodeBytes())
        let (type, payload) = try io.read()
        guard type == .pairCheck else { throw ProtocolError("expected PAIR_CHECK, got \(type)") }
        let answer = try payload.decodeJSON(PairCheck.self)
        guard answer.nonce == b64(nonce), let first = answer.proofs.first, let theirs = try? unb64(first) else { return nil }
        matched = held.first {
            constantTimeEqual(theirs, SessionCrypto.pairProof(key: $0.key, label: "check-r", binding: keys.binding, extra: nonce))
        }
        return matched
    }

    /// After a successful `pairCheck`: offer `text` (just copied here) for the other device's clipboard. True when it took
    /// it; false when clipboard sharing with this device is off over there. Throws when the other device is an older build
    /// that doesn't know the message (it drops the connection).
    public func clipboard(_ text: String, wait: TimeInterval = ControlChannel.clipboardWait) throws -> Bool {
        guard matched != nil else { throw ProtocolError("clipboard needs a successful pairCheck first") }
        guard text.utf8.count <= maxClipboardBytes else { throw ProtocolError("copy too large") }
        let id = randomBytes(16).map { String(format: "%02x", $0) }.joined()
        io.socket.setTimeout(wait)
        try io.write(.clipboard, ClipboardPush(id: id, text: text).encodeBytes())
        let (type, payload) = try io.read()
        guard type == .clipboard else { throw ProtocolError("expected CLIPBOARD, got \(type)") }
        return try payload.decodeJSON(ClipboardReply.self).accepted
    }

    /// 32 lowercase hex characters: the only pairing ids a CrossDrop build sends.
    public static func isPairId(_ s: String) -> Bool {
        s.utf8.count == 32 && s.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }

    /// A fresh pairing id: 16 random bytes, hex.
    public static func newPairId() -> String { randomBytes(16).map { String(format: "%02x", $0) }.joined() }

    /// How long `pair` waits for the human on the other side (Kotlin `PAIR_WAIT_MS`).
    public static let pairWait: TimeInterval = 75
    /// Putting text on a clipboard is quick; a device that takes longer is gone.
    public static let clipboardWait: TimeInterval = 5
    static let maxNonce = 64
    static let minNonce = 16
    static let maxProofs = 64
}

func randomBytes(_ n: Int) -> [UInt8] {
    var g = SystemRandomNumberGenerator()        // the system CSPRNG
    return (0..<n).map { _ in UInt8.random(in: 0...255, using: &g) }
}

func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
    guard a.count == b.count else { return false }
    var diff: UInt8 = 0
    for (x, y) in zip(a, b) { diff |= x ^ y }
    return diff == 0
}

extension TransferEngine {
    /// How long `control` waits to connect by default: the device is on this network or it isn't.
    public static let controlConnectTimeout: TimeInterval = 5

    /// Open an encrypted connection that carries no files (announce, shake, pairing, clipboard), run `block` on it and
    /// close it. Blocking: call from a background thread. Throws on connection or protocol errors; `cancel` aborts it.
    /// `readTimeout` bounds every wait for the other device (a quick "is this one of mine?" check wants a short one).
    public func control<T>(host: String, port: Int, connectTimeout: TimeInterval = TransferEngine.controlConnectTimeout,
                           readTimeout: TimeInterval = TransferEngine.controlTimeout,
                           cancel: CancelToken? = nil, _ block: (ControlChannel) throws -> T) throws -> T {
        let s = try TCPSocket.connect(host: host, port: port, timeout: connectTimeout,
                                      isCancelled: { cancel?.isCancelled ?? false })
        defer { cancel?.detach(); s.close() }
        cancel?.attach { s.shutdown() }
        s.setTimeout(readTimeout)
        let fio = FrameIO(socket: s)
        try fio.write(.hello, Hello(version: version, deviceName: deviceName(),
                                    publicKeyB64: b64(identity.publicKey), instanceId: instanceId, os: os).encodeBytes())
        let (ackType, ackPayload) = try fio.read()
        guard ackType == .helloAck else { throw ProtocolError("expected HELLO_ACK, got \(ackType)") }
        let ack = try ackPayload.decodeJSON(HelloAck.self)
        if ack.vaultAuth != nil { throw ProtocolError("that's a drive, not a device") }
        let keys = try SessionCrypto.deriveSession(identity: identity, peerPublicKey: try unb64(ack.publicKeyB64), isInitiator: true)
        fio.writeCipher = RecordCipher(key: keys.sendKey)
        fio.readCipher = RecordCipher(key: keys.recvKey)
        return try block(ControlChannel(io: fio, keys: keys, peerName: TransferEngine.displayName(ack.deviceName),
                                        peerInstanceId: ack.instanceId, peerOS: ack.os))
    }

    /// Tell the device at `host:port` that this one listens on `listenPort` (ANNOUNCE), so it shows us on its radar
    /// even where discovery can't. Returns the other device's name and per-launch id from its HELLO_ACK.
    public func announce(host: String, port: Int, listenPort: Int,
                         connectTimeout: TimeInterval = TransferEngine.controlConnectTimeout,
                         cancel: CancelToken? = nil) throws -> (name: String, instanceId: String) {
        try control(host: host, port: port, connectTimeout: connectTimeout, cancel: cancel) { ch in
            try ch.announce(listenPort: listenPort)
            return (ch.peerName, ch.peerInstanceId)
        }
    }

    /// Tell the device at `host:port` that this one was just shaken (SHAKE), with `listenPort` so it can reach us back.
    /// It checks for a shake of its own (`ShakeMatcher`). Returns the other device's name and per-launch id.
    public func shake(host: String, port: Int, listenPort: Int,
                      connectTimeout: TimeInterval = TransferEngine.controlConnectTimeout,
                      cancel: CancelToken? = nil) throws -> (name: String, instanceId: String) {
        try control(host: host, port: port, connectTimeout: connectTimeout, cancel: cancel) { ch in
            try ch.shake(listenPort: listenPort)
            return (ch.peerName, ch.peerInstanceId)
        }
    }

    /// The listener's side of one control exchange: `first` is the frame that came instead of an OFFER. Returns when
    /// the exchange is over (the caller closes the socket) without any session event.
    func serveControl(_ socket: TCPSocket, _ io: FrameIO, _ keys: SessionKeys, peerName: String, peerInstanceId: String,
                      first: (FrameType, [UInt8]), handler: ControlHandler) throws {
        let (type, payload) = first
        switch type {
        case .announce:
            let port = try payload.decodeJSON(Announce.self).port
            guard (1...65535).contains(port) else { throw ProtocolError("bad announced port \(port)") }
            guard let host = socket.remoteAddress() else { throw ProtocolError("no peer address") }
            handler.announced(name: peerName, instanceId: peerInstanceId, host: host, port: port)
            try io.write(.announce, Announce(port: 0).encodeBytes())
        case .shake:
            // A port we couldn't call back on just means "didn't say": the shake itself still counts.
            let said = try payload.decodeJSON(Shake.self).port
            let port = (1...65535).contains(said) ? said : 0
            guard let host = socket.remoteAddress() else { throw ProtocolError("no peer address") }
            handler.shaken(name: peerName, instanceId: peerInstanceId, host: host, port: port)
            try io.write(.shake, Shake(port: 0).encodeBytes())
        case .pair:
            let request = try payload.decodeJSON(PairRequest.self)
            guard ControlChannel.isPairId(request.pairId) else { throw ProtocolError("bad pair id") }
            // The human here may take a while; the asking side waits `pairWait`.
            socket.setTimeout(ControlChannel.pairWait + 5)
            let accepted = handler.pairRequested(name: peerName, sas: keys.sas, purpose: request.purpose)
            try io.write(.pair, PairReply(accepted: accepted).encodeBytes())
            if accepted {
                handler.paired(pairId: request.pairId, name: peerName,
                               key: SessionCrypto.pairKey(shared: keys.shared, binding: keys.binding, pairId: request.pairId),
                               purpose: request.purpose)
            }
        case .pairCheck:
            let check = try payload.decodeJSON(PairCheck.self)
            guard let nonceData = Data(base64Encoded: check.nonce),
                  (ControlChannel.minNonce...ControlChannel.maxNonce).contains(nonceData.count) else { throw ProtocolError("bad nonce") }
            let nonce = [UInt8](nonceData)
            let proofs = check.proofs.prefix(ControlChannel.maxProofs).compactMap { try? unb64($0) }
            let match = handler.pairings().first { secret in
                let want = SessionCrypto.pairProof(key: secret.key, label: "check-i", binding: keys.binding, extra: nonce)
                return proofs.contains { constantTimeEqual($0, want) }
            }
            // A stranger (or a device whose pairing we don't hold) gets the same nonce back and no proofs: nothing to link.
            let answer = match.map { [b64(SessionCrypto.pairProof(key: $0.key, label: "check-r", binding: keys.binding, extra: nonce))] } ?? []
            try io.write(.pairCheck, PairCheck(nonce: check.nonce, proofs: answer).encodeBytes())
            guard let match else { return }
            // A paired device may now share its clipboard (or ask about backups), as long as it keeps the connection open.
            socket.setTimeout(TransferEngine.dataTimeout)
            while true {
                let next: (FrameType, [UInt8])
                do { next = try io.read() } catch is EOFError { return }
                switch next.0 {
                case .clipboard:
                    let push = try next.1.decodeJSON(ClipboardPush.self)
                    let fits = (16...64).contains(push.id.utf8.count) && push.text.utf8.count <= maxClipboardBytes
                    let taken = fits && handler.clipboard(pairId: match.id, clipId: push.id, text: push.text)
                    try io.write(.clipboard, ClipboardReply(accepted: taken).encodeBytes())
                case .backupVerify:
                    // This build keeps no backups: it vouches for nothing.
                    _ = try next.1.decodeJSON(VerifyRequest.self)
                    try io.write(.backupVerify, VerifyReply(ok: []).encodeBytes())
                default:
                    return
                }
            }
        default:
            throw ProtocolError("expected OFFER, got \(type)")
        }
    }
}
