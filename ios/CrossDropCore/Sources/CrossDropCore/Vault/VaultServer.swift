import Foundation
import Darwin

/// The LAN Vault: one TCP listener that demultiplexes
///  - the AEAD binary protocol (X25519 → ChaCha20-Poly1305 frames) for native CrossDrop clients, and
///  - a plain HTTP/1.1 file explorer / WebDAV surface for zero-install browsers and OS mounts.
///
/// A binary connection's first byte is a frame-length MSB (0x00…0x3F) — never an uppercase ASCII
/// letter — so peeking one byte splits the streams without consuming anything.
/// Port of `core/vault/VaultServer.kt`. Blocking handler thread per connection (max 16).
public final class VaultServer {
    public enum Event {
        case clientConnected(peerName: String, via: String)
        case clientDisconnected(peerName: String)
        case pullFinished(path: String, bytes: Int64)
        case pushFinished(path: String, bytes: Int64)
    }

    public static let dataChunk = 256 * 1024          // max VAULT_DATA payload
    private static let dirBatch = 512
    private static let maxClients = 16
    /// One misbehaving device must not be able to occupy every handler slot.
    private static let maxClientsPerIp = 6
    private static let idleTimeout: TimeInterval = 45
    private static let authTimeout: TimeInterval = 15
    /// Stat hash only for files ≤ 256 MB — keeps browse snappy.
    private static let hashMax: Int64 = 256 * 1024 * 1024

    public let fs: VaultFs
    public let writable: Bool
    private let identity: SessionIdentity
    private let deviceName: () -> String
    private let pin: String?
    private let instanceId: String
    private let onEvent: (Event) -> Void

    private let lock = NSLock()
    private var _activeClients = 0
    private var _bytesOut: Int64 = 0
    private var _bytesIn: Int64 = 0
    private var _peers: [String] = []
    private var _port = 0
    private var closed = false
    private var listenFd: Int32 = -1
    private var clientSockets: [ObjectIdentifier: TCPSocket] = [:]
    private var nextTid: Int64 = 1
    private var running = 0     // live handler threads
    private var perIp: [String: Int] = [:]
    /// Shared by the HTTP and native PIN checks: guessing over either surface hits one budget.
    private let throttle = PinThrottle()
    private lazy var http = VaultHttp(fs: fs, writable: writable, pin: pin, deviceName: deviceName,
                                      throttle: throttle) { [weak self] n in
        self?.addOut(n)
    }

    /// - Parameter pin: 4-digit PIN → `auth=pin`; nil → `auth=open`.
    public init(fs: VaultFs, identity: SessionIdentity, deviceName: @escaping () -> String,
                writable: Bool = false, pin: String? = nil, instanceId: String,
                onEvent: @escaping (Event) -> Void = { _ in }) {
        self.fs = fs; self.identity = identity; self.deviceName = deviceName
        self.writable = writable; self.pin = pin; self.instanceId = instanceId; self.onEvent = onEvent
    }

    // Live host-dashboard counters.
    public var port: Int { lock.lock(); defer { lock.unlock() }; return _port }
    public var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return listenFd >= 0 && !closed }
    public var activeClients: Int { lock.lock(); defer { lock.unlock() }; return _activeClients }
    public var bytesOut: Int64 { lock.lock(); defer { lock.unlock() }; return _bytesOut }
    public var bytesIn: Int64 { lock.lock(); defer { lock.unlock() }; return _bytesIn }
    public var connectedPeers: [String] { lock.lock(); defer { lock.unlock() }; return _peers }

    private func addOut(_ n: Int64) { lock.lock(); _bytesOut += n; lock.unlock() }
    private func addIn(_ n: Int64) { lock.lock(); _bytesIn += n; lock.unlock() }

    // MARK: lifecycle

    /// Bind the listener. `preferredPort` (e.g. 8080, a memorable address for WebDAV clients) is
    /// tried first; busy/failed → ephemeral.
    @discardableResult
    public func start(preferredPort: Int = 0) throws -> Int {
        lock.lock()
        if listenFd >= 0 { lock.unlock(); throw ProtocolError("already running") }
        lock.unlock()
        var fd: Int32 = -1
        if preferredPort > 0 { fd = Self.bind(port: preferredPort) }
        if fd < 0 { fd = Self.bind(port: 0) }
        guard fd >= 0 else { throw ProtocolError("bind failed: \(String(cString: strerror(errno)))") }
        var bound = sockaddr_in6()
        var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        lock.lock()
        listenFd = fd
        _port = Int(UInt16(bigEndian: bound.sin6_port))
        closed = false
        let p = _port
        lock.unlock()
        let t = Thread { [weak self] in self?.acceptLoop(fd) }
        t.name = "vault-accept"
        t.start()
        return p
    }

    private static func bind(port: Int) -> Int32 {
        let s = socket(AF_INET6, SOCK_STREAM, 0)
        guard s >= 0 else { return -1 }
        var on: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        var off: Int32 = 0
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &off, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in6()
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_port = UInt16(port).bigEndian
        addr.sin6_addr = in6addr_any
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(s, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) }
        }
        guard rc == 0, listen(s, 32) == 0 else { Darwin.close(s); return -1 }
        return s
    }

    private func acceptLoop(_ fd: Int32) {
        // `listenFd == fd` doubles as a generation check: after close() (or close()+start() reusing
        // the same fd number) a stale loop must never poll/accept on the new listener.
        func stillCurrent() -> Bool { lock.lock(); defer { lock.unlock() }; return !closed && listenFd == fd }
        while stillCurrent() {
            // poll so close() can end the loop without relying on closing a blocked accept()
            var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let pr = poll(&pfd, 1, 250)
            if pr <= 0 { continue }
            if !stillCurrent() { break }
            let c = accept(fd, nil, nil)
            if c < 0 { if errno == EINTR || errno == ECONNABORTED { continue }; break }
            let sock = TCPSocket(fd: c)
            let ip = PinThrottle.key(for: sock.peerAddress)
            lock.lock()
            let full = running >= Self.maxClients || closed || (perIp[ip] ?? 0) >= Self.maxClientsPerIp
            if !full { running += 1; perIp[ip, default: 0] += 1; clientSockets[ObjectIdentifier(sock)] = sock }
            lock.unlock()
            if full { sock.close(); continue }
            let t = Thread { [weak self] in
                self?.serve(sock)
                guard let self else { sock.close(); return }
                self.lock.lock()
                self.running -= 1
                self.clientSockets[ObjectIdentifier(sock)] = nil
                if let n = self.perIp[ip] { if n <= 1 { self.perIp[ip] = nil } else { self.perIp[ip] = n - 1 } }
                self.lock.unlock()
                sock.close()
            }
            t.name = "vault-client"
            t.start()
        }
    }

    /// Kill the listener, drop every client, purge upload staging.
    public func close() {
        lock.lock()
        closed = true
        let fd = listenFd
        listenFd = -1
        let socks = Array(clientSockets.values)
        lock.unlock()
        if fd >= 0 { Darwin.close(fd) }
        socks.forEach { $0.shutdown() }
        fs.purgeStaging()
    }

    // MARK: demux

    private func serve(_ socket: TCPSocket) {
        socket.setTimeout(Self.idleTimeout)
        do {
            guard let first = try socket.peekByte() else { return }
            if first >= 0x41 && first <= 0x5A {
                http.handle(socket: socket)           // HTTP method — every verb starts with a capital
            } else {
                _ = try nativeSession(socket)
            }
        } catch {
            // idle / malformed client — never lets one connection take the listener down
        }
    }

    // MARK: native session

    private func nativeSession(_ socket: TCPSocket) throws -> String {
        let io = FrameIO(socket: socket)
        let (t0, p0) = try io.read()
        guard t0 == .hello else { throw ProtocolError("expected HELLO, got \(t0)") }
        let hello = try p0.decodeJSON(Hello.self)
        let peer = String(hello.deviceName.prefix(64))      // untrusted text that ends up in host UI
        try io.write(.helloAck, HelloAck(version: protocolVersion, deviceName: deviceName(),
                                         publicKeyB64: b64(identity.publicKey), instanceId: instanceId,
                                         vaultAuth: pin != nil ? "pin" : "open").encodeBytes())
        let keys = try SessionCrypto.deriveSession(identity: identity, peerPublicKey: try unb64(hello.publicKeyB64), isInitiator: false)
        io.readCipher = RecordCipher(key: keys.recvKey)
        io.writeCipher = RecordCipher(key: keys.sendKey)

        if let pin {
            let ip = socket.peerAddress
            // Locked out (this client, or the whole PIN surface)? Say so and hang up without
            // spending any more effort on it.
            do { try throttle.checkLocked(ip) } catch let e as VaultError {
                try? io.write(.vaultErr, VaultErr(code: e.code, message: e.message).encodeBytes())
                return peer
            }
            socket.setTimeout(Self.authTimeout)
            let (at, ap) = try io.read()
            var ok = false
            if at == .vaultAuth, let auth = try? ap.decodeJSON(VaultAuth.self), let tok = try? unb64(auth.token) {
                ok = VaultWire.constantTimeEqual(tok, SessionCrypto.vaultAuthToken(shared: keys.shared, pin: pin))
            }
            socket.setTimeout(Self.idleTimeout)
            _ = try? throttle.record(ip, ok: ok)
            if !ok {
                // Slow down PIN guessing, then drop without a word.
                Thread.sleep(forTimeInterval: 0.3)
                return peer
            }
            try io.write(.vaultPushAck, VaultAck(status: "accept").encodeBytes())
        }

        lock.lock(); _activeClients += 1; _peers.append(peer); lock.unlock()
        onEvent(.clientConnected(peerName: peer, via: "native"))
        defer {
            lock.lock()
            _activeClients -= 1
            if let i = _peers.firstIndex(of: peer) { _peers.remove(at: i) }
            lock.unlock()
            onEvent(.clientDisconnected(peerName: peer))
        }
        try commandLoop(io)
        return peer
    }

    private func commandLoop(_ io: FrameIO) throws {
        while true {
            lock.lock(); let stop = closed; lock.unlock()
            if stop { return }
            let (type, payload) = try io.read()
            do {
                switch type {
                case .vaultLs: try sendDir(io, try payload.decodeJSON(VaultLs.self).path)
                case .vaultStat: try sendAttr(io, try payload.decodeJSON(VaultStat.self).path)
                case .vaultPull: try doPull(io, try payload.decodeJSON(VaultPull.self))
                case .vaultPushInit: try doPush(io, try payload.decodeJSON(VaultPushInit.self))
                case .cancel: break      // nothing in-flight between commands
                default: sendErr(io, 400, "unsupported frame \(type)")
                }
            } catch let e as VaultError {
                sendErr(io, e.code, e.message)
            }
        }
    }

    private func sendDir(_ io: FrameIO, _ path: String) throws {
        let entries = try fs.list(path).map { VaultDirEntry(name: $0.name, size: $0.size, isDir: $0.isDir, mtime: $0.mtime, mime: $0.mime) }
        if entries.isEmpty {
            try io.write(.vaultDir, VaultDir(path: path, entries: [], eof: true).encodeBytes())
            return
        }
        var sent = 0
        var i = 0
        while i < entries.count {
            let batch = Array(entries[i..<min(entries.count, i + Self.dirBatch)])
            i += batch.count
            sent += batch.count
            try io.write(.vaultDir, VaultDir(path: path, entries: batch, eof: sent >= entries.count).encodeBytes())
        }
    }

    private func sendAttr(_ io: FrameIO, _ path: String) throws {
        let e = try fs.stat(path)
        var hash: String?
        if !e.isDir && e.size <= Self.hashMax {
            let r = try fs.open(path, offset: 0)
            defer { r.close() }
            let h = Blake3()
            while true {
                let part = try r.read(64 * 1024)
                if part.isEmpty { break }
                h.update(part)
            }
            hash = h.hexDigest()
        }
        try io.write(.vaultAttr, VaultAttr(name: e.name, size: e.size, isDir: e.isDir, mtime: e.mtime, mime: e.mime, hash: hash).encodeBytes())
    }

    // MARK: pull

    private func doPull(_ io: FrameIO, _ req: VaultPull) throws {
        let e = try fs.stat(req.path)
        if e.isDir { throw VaultError(400, "not a file") }
        let size = e.size
        let offset = min(max(req.offset, 0), size)
        let length = min(max(req.length < 0 ? size - offset : req.length, 0), size - offset)
        let tid = req.transferId
        let hasher = Blake3()
        let src = try fs.open(req.path, offset: offset)
        defer { src.close() }
        var sent: Int64 = 0
        while sent < length {
            let part = try src.read(Int(min(Int64(Self.dataChunk), length - sent)))
            if part.isEmpty { break }
            hasher.update(part)
            try io.write(.vaultData, VaultWire.header(tid: tid, offset: offset + sent) + part)
            addOut(Int64(part.count))
            sent += Int64(part.count)
        }
        try io.write(.vaultDone, VaultDone(transferId: tid, hash: hasher.hexDigest()).encodeBytes())
        onEvent(.pullFinished(path: req.path, bytes: length))
    }

    // MARK: push

    private func doPush(_ io: FrameIO, _ req: VaultPushInit) throws {
        if !writable { throw VaultError(403, "vault is read-only") }
        let clean = try fs.sanitize(req.path)
        if clean.isEmpty { throw VaultError(400, "empty target") }
        if req.size < 0 { throw VaultError(400, "bad size") }
        if !fs.reserve(req.size) { throw VaultError(413, "insufficient storage") }
        defer { fs.release(req.size) }

        lock.lock(); let tid = nextTid; nextTid += 1; lock.unlock()
        let stage = fs.stageFile(id: tid)
        defer { try? FileManager.default.removeItem(at: stage) }
        try io.write(.vaultPushAck, VaultAck(status: "accept", transferId: tid).encodeBytes())
        FileManager.default.createFile(atPath: stage.path, contents: nil)
        guard let out = FileHandle(forWritingAtPath: stage.path) else { throw VaultError(500, "cannot stage upload") }
        var expected: Int64 = 0
        do {
            while expected < req.size {
                let (t, p) = try io.read()
                switch t {
                case .vaultData:
                    if p.count < 12 || p.count - 12 > Self.dataChunk { throw VaultError(400, "bad data frame") }
                    let (dTid, dOff) = VaultWire.readHeader(p)
                    if dTid != tid { throw VaultError(409, "transfer id mismatch") }
                    if dOff != expected { throw VaultError(409, "out-of-order chunk") }
                    if expected + Int64(p.count - 12) > req.size { throw VaultError(400, "upload exceeds declared size") }
                    try out.write(contentsOf: Data(p[12...]))
                    expected += Int64(p.count - 12)
                    addIn(Int64(p.count - 12))
                case .cancel: throw VaultError(499, "cancelled")
                default: throw VaultError(400, "expected VAULT_DATA, got \(t)")
                }
            }
            try out.close()
        } catch {
            try? out.close()
            throw error
        }
        let (t, p) = try io.read()
        guard t == .vaultDone else { throw VaultError(400, "expected VAULT_DONE") }
        let done = try p.decodeJSON(VaultDone.self)
        let h = Blake3()
        let rd = try VaultFileReader(path: stage.path)
        while true {
            let part = try rd.read(64 * 1024)
            if part.isEmpty { break }
            h.update(part)
        }
        rd.close()
        let actual = h.hexDigest()
        if !VaultWire.constantTimeEqual(Array(actual.utf8), Array(done.hash.utf8)) { throw VaultError(400, "checksum mismatch") }
        let finalPath = try fs.commitStaged(stage, clean)
        try io.write(.vaultDone, VaultDone(transferId: tid, hash: actual, path: finalPath).encodeBytes())
        onEvent(.pushFinished(path: finalPath, bytes: req.size))
    }

    private func sendErr(_ io: FrameIO, _ code: Int, _ msg: String) {
        try? io.write(.vaultErr, VaultErr(code: code, message: msg).encodeBytes())
    }
}
