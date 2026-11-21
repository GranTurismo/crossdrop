import Foundation

/// The receiving side of the exchanges that carry no files (Kotlin `ControlHandler`). The transfer listener hands
/// every connection whose first frame isn't an OFFER to `TransferEngine.serveControl`, which calls this.
///
/// This build keeps no pairings or backups: a PAIR is declined without asking anyone and a PAIR_CHECK is answered
/// with no proofs, so only `announced` and `shaken` reach the app. Called on the engine's thread.
public protocol ControlHandler: AnyObject {
    /// A device told us where to reach it: `host` is the address it connected from, `port` its listener.
    func announced(name: String, instanceId: String, host: String, port: Int)
    /// A device says it was just shaken (shake to connect); `port` is its listener, 0 if it didn't say.
    func shaken(name: String, instanceId: String, host: String, port: Int)
}

extension ControlHandler {
    public func shaken(name: String, instanceId: String, host: String, port: Int) {}
}

/// The initiator's end of a connection that carries no files, opened by `TransferEngine.control`. The handshake is
/// done and every frame is sealed. One exchange per connection.
public final class ControlChannel {
    let io: FrameIO
    let keys: SessionKeys
    /// The other device's name (cleaned for display) and per-launch id, from its HELLO_ACK.
    public let peerName: String
    public let peerInstanceId: String
    /// The 6-digit code both screens would show for this connection.
    public var sas: String { keys.sas }

    init(io: FrameIO, keys: SessionKeys, peerName: String, peerInstanceId: String) {
        self.io = io; self.keys = keys; self.peerName = peerName; self.peerInstanceId = peerInstanceId
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

    /// 32 lowercase hex characters: the only pairing ids a CrossDrop build sends.
    static func isPairId(_ s: String) -> Bool {
        s.utf8.count == 32 && s.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }

    static let maxNonce = 64
    static let minNonce = 16
}

extension TransferEngine {
    /// How long `control` waits to connect by default: the device is on this network or it isn't.
    public static let controlConnectTimeout: TimeInterval = 5

    /// Open an encrypted connection that carries no files (announce, shake), run `block` on it and close it.
    /// Blocking: call from a background thread. Throws on connection or protocol errors; `cancel` aborts it.
    public func control<T>(host: String, port: Int, connectTimeout: TimeInterval = TransferEngine.controlConnectTimeout,
                           cancel: CancelToken? = nil, _ block: (ControlChannel) throws -> T) throws -> T {
        let s = try TCPSocket.connect(host: host, port: port, timeout: connectTimeout,
                                      isCancelled: { cancel?.isCancelled ?? false })
        defer { cancel?.detach(); s.close() }
        cancel?.attach { s.shutdown() }
        s.setTimeout(TransferEngine.controlTimeout)
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
                                        peerInstanceId: ack.instanceId))
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
            let pairId = try payload.decodeJSON(PairRequest.self).pairId
            guard ControlChannel.isPairId(pairId) else { throw ProtocolError("bad pair id") }
            // No pairings on this build: decline without asking anyone.
            try io.write(.pair, PairReply(accepted: false).encodeBytes())
        case .pairCheck:
            let check = try payload.decodeJSON(PairCheck.self)
            guard let nonce = Data(base64Encoded: check.nonce),
                  (ControlChannel.minNonce...ControlChannel.maxNonce).contains(nonce.count) else { throw ProtocolError("bad nonce") }
            // We hold no pairing, so none of the proofs can match: the same nonce back, no proofs. A stranger learns nothing.
            try io.write(.pairCheck, PairCheck(nonce: check.nonce, proofs: []).encodeBytes())
        default:
            throw ProtocolError("expected OFFER, got \(type)")
        }
    }
}
