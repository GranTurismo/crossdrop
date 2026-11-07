import Foundation

public struct ProtocolError: Error, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
    public var localizedDescription: String { message }
}

/// Wire frame types. HELLO/HELLO_ACK are cleartext; everything after is AEAD-sealed.
public enum FrameType: UInt8 {
    case hello = 0, helloAck = 1, offer = 2, accept = 3, decline = 4
    case data = 5, done = 6, cancel = 7, error = 8
    case chunkMap = 9, chunkReq = 10, chunk = 11
    case pushChunk = 12, pushEnd = 13
    case vaultLs = 0x10, vaultDir = 0x11, vaultStat = 0x12, vaultAttr = 0x13
    case vaultPull = 0x14, vaultData = 0x15, vaultPushInit = 0x16
    case vaultPushAck = 0x17, vaultDone = 0x18, vaultErr = 0x19, vaultAuth = 0x1A
}

/// `[u32 BE bodyLen][body]`, body = `[u8 type][payload]` or `seal(type || payload)`.
public final class FrameIO {
    public static let maxFrame = 4 * 1024 * 1024
    let socket: TCPSocket
    private let writeLock = NSLock()
    private let cipherLock = NSLock()
    private var _readCipher: RecordCipher?
    private var _writeCipher: RecordCipher?

    var readCipher: RecordCipher? {
        get { cipherLock.lock(); defer { cipherLock.unlock() }; return _readCipher }
        set { cipherLock.lock(); _readCipher = newValue; cipherLock.unlock() }
    }
    var writeCipher: RecordCipher? {
        get { cipherLock.lock(); defer { cipherLock.unlock() }; return _writeCipher }
        set { cipherLock.lock(); _writeCipher = newValue; cipherLock.unlock() }
    }

    init(socket: TCPSocket) { self.socket = socket }

    func write(_ type: FrameType, _ payload: [UInt8] = []) throws {
        writeLock.lock(); defer { writeLock.unlock() }
        try writeLocked(type, payload) { try socket.writeAll($0) }
    }

    /// Frame encoding shared by `write` and the cancel path. Caller holds `writeLock` (the seal counter
    /// and the byte stream must advance together).
    private func writeLocked(_ type: FrameType, _ payload: [UInt8], _ send: ([UInt8]) throws -> Void) throws {
        var body = [UInt8](repeating: 0, count: 1 + payload.count)
        body[0] = type.rawValue
        body.replaceSubrange(1..., with: payload)
        if let c = writeCipher { body = try c.seal(body) }
        guard body.count <= FrameIO.maxFrame else { throw ProtocolError("frame too large: \(body.count)") }
        var out = [UInt8](); out.reserveCapacity(4 + body.count)
        out += be32(UInt32(body.count))
        out += body
        try send(out)
    }

    /// Abort the session from any thread without ever blocking: send a parting CANCEL only if the
    /// channel is secured, no other write is in progress and the kernel buffer has room, then shut the
    /// socket down so every blocked reader/writer wakes up. (`cancel()` runs on the UI thread.)
    func cancelAndShutdown(reason: String) {
        if writeCipher != nil, writeLock.`try`() {
            if let payload = try? Cancel(reason: reason).encodeBytes() {
                try? writeLocked(.cancel, payload) { socket.trySendNoWait($0) }
            }
            writeLock.unlock()
        }
        socket.shutdown()
    }

    func read() throws -> (FrameType, [UInt8]) {
        let hdr = try socket.readFully(4)
        let len = Int(readBE32(hdr, 0))
        guard len > 0, len <= FrameIO.maxFrame else { throw ProtocolError("bad frame length \(len)") }
        var body = try socket.readFully(len)
        if let c = readCipher { body = try c.open(body) }
        guard !body.isEmpty else { throw ProtocolError("empty frame body") }
        guard let t = FrameType(rawValue: body[0]) else { throw ProtocolError("unknown frame type \(body[0])") }
        return (t, Array(body[1...]))
    }
}

@inline(__always) func be32(_ v: UInt32) -> [UInt8] {
    [UInt8(v >> 24), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
}

@inline(__always) func readBE32(_ b: [UInt8], _ o: Int) -> UInt32 {
    (UInt32(b[o]) << 24) | (UInt32(b[o + 1]) << 16) | (UInt32(b[o + 2]) << 8) | UInt32(b[o + 3])
}

func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
