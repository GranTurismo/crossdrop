import Foundation
#if canImport(Darwin)
import Darwin
#endif

public struct EOFError: Error, CustomStringConvertible { public var description: String { "stream closed" } }

/// Thin blocking BSD-socket wrapper. The engine runs on dedicated threads (mirrors the Kotlin core's
/// blocking streams), so no async plumbing is needed.
///
/// Lifecycle rules (these matter: a stale fd number may already belong to another socket):
///  - every syscall on `fd` made through this class is guarded against `close()`;
///  - `close()` shuts the socket down first (waking any thread blocked in recv/send) and the fd itself
///    is only released once the last in-flight read/write has returned;
///  - `shutdown()` / `setTimeout` on a closed socket are no-ops, never touching a recycled fd.
public final class TCPSocket {
    public let fd: Int32
    private let lock = NSLock()
    private var closed = false
    private var fdReleased = false
    private var inflight = 0

    public init(fd: Int32) {
        self.fd = fd
        var on: Int32 = 1
        let sz = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, sz)
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sz)
        // Dead-peer detection for the phases that read with no timeout (≈ 30 s idle + 3 × 10 s probes).
        setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sz)
        var idle: Int32 = 30, intvl: Int32 = 10, cnt: Int32 = 3
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPALIVE, &idle, sz)
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sz)
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sz)
    }

    deinit { close() }

    /// Connect with a timeout. Resolves IPv4/IPv6 literals and hostnames (e.g. Bonjour `.local`).
    /// `isCancelled` is polled every ~100 ms so a user cancel doesn't wait out the whole timeout.
    public static func connect(host: String, port: Int, timeout: TimeInterval = 15,
                               isCancelled: (() -> Bool)? = nil) throws -> TCPSocket {
        guard (1...65535).contains(port) else { throw ProtocolError("bad port \(port)") }
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        let rc = getaddrinfo(host, String(port), &hints, &res)
        guard rc == 0, let first = res else {
            throw ProtocolError("cannot resolve \(host): \(String(cString: gai_strerror(rc)))")
        }
        defer { freeaddrinfo(res) }

        let deadline = Date().addingTimeInterval(timeout)
        var lastErr = "connect failed"
        var cur: UnsafeMutablePointer<addrinfo>? = first
        while let ai = cur {
            cur = ai.pointee.ai_next
            if isCancelled?() == true { throw ProtocolError("cancelled") }
            let fd = socket(ai.pointee.ai_family, ai.pointee.ai_socktype, ai.pointee.ai_protocol)
            guard fd >= 0 else { lastErr = String(cString: strerror(errno)); continue }
            let flags = fcntl(fd, F_GETFL)
            _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
            var rcc = Darwin.connect(fd, ai.pointee.ai_addr, ai.pointee.ai_addrlen)
            var err: Int32 = rcc == 0 ? 0 : errno
            if rcc != 0 && err == EINPROGRESS {
                err = ETIMEDOUT
                while Date() < deadline {
                    if isCancelled?() == true { Darwin.close(fd); throw ProtocolError("cancelled") }
                    var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                    let slice = Int32(max(1, min(100, deadline.timeIntervalSinceNow * 1000)))
                    let pr = poll(&pfd, 1, slice)
                    if pr < 0 { if errno == EINTR { continue }; err = errno; break }
                    if pr > 0 {
                        var soErr: Int32 = 0
                        var len = socklen_t(MemoryLayout<Int32>.size)
                        getsockopt(fd, SOL_SOCKET, SO_ERROR, &soErr, &len)
                        err = soErr
                        break
                    }
                }
                rcc = err == 0 ? 0 : -1
            }
            if rcc == 0 {
                _ = fcntl(fd, F_SETFL, flags)
                return TCPSocket(fd: fd)
            }
            lastErr = String(cString: strerror(err))
            Darwin.close(fd)
            if Date() >= deadline { break }
        }
        throw ProtocolError("connect to \(host):\(port) failed: \(lastErr)")
    }

    // MARK: options

    /// Read + write timeout in seconds; 0 disables both.
    public func setTimeout(_ seconds: TimeInterval) {
        setTimeouts(receive: seconds, send: seconds)
    }

    /// Independent read / write timeouts (0 = none). A sender wants no read timeout while the human
    /// decides, but must not block forever writing to a peer that stopped reading.
    public func setTimeouts(receive: TimeInterval, send: TimeInterval) {
        func tv(_ s: TimeInterval) -> timeval {
            let t = max(0, s)
            return timeval(tv_sec: Int(t), tv_usec: Int32((t - floor(t)) * 1_000_000))
        }
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return }
        var r = tv(receive), w = tv(send)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &r, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &w, socklen_t(MemoryLayout<timeval>.size))
    }

    // MARK: I/O

    private func begin() throws {
        lock.lock(); defer { lock.unlock() }
        if closed { throw ProtocolError("connection closed") }
        inflight += 1
    }

    private func end() {
        lock.lock()
        inflight -= 1
        let release = closed && inflight == 0 && !fdReleased
        if release { fdReleased = true }
        lock.unlock()
        if release { Darwin.close(fd) }
    }

    private static let growStep = 256 * 1024

    func readFully(_ count: Int) throws -> [UInt8] {
        guard count >= 0 else { throw ProtocolError("bad read length") }
        try begin(); defer { end() }
        // Allocate as data arrives so a bogus length prefix can't pin megabytes before a single byte is sent.
        var buf = [UInt8](repeating: 0, count: min(count, TCPSocket.growStep))
        var off = 0
        while off < count {
            if off == buf.count { buf.append(contentsOf: [UInt8](repeating: 0, count: min(count - off, TCPSocket.growStep))) }
            let want = buf.count - off
            let n = buf.withUnsafeMutableBytes { recv(fd, $0.baseAddress! + off, want, 0) }
            if n == 0 { throw EOFError() }
            if n < 0 {
                if errno == EINTR { continue }
                if errno == EAGAIN || errno == EWOULDBLOCK { throw ProtocolError("timed out waiting for peer") }
                throw ProtocolError("read failed: \(String(cString: strerror(errno)))")
            }
            off += n
        }
        return buf
    }

    func writeAll(_ bytes: [UInt8]) throws {
        try begin(); defer { end() }
        var off = 0
        while off < bytes.count {
            let n = bytes.withUnsafeBytes { send(fd, $0.baseAddress! + off, bytes.count - off, 0) }
            if n < 0 {
                if errno == EINTR { continue }
                if errno == EAGAIN || errno == EWOULDBLOCK { throw ProtocolError("timed out sending to peer") }
                throw ProtocolError("write failed: \(String(cString: strerror(errno)))")
            }
            off += n
        }
    }

    /// One non-blocking send attempt; never waits. Used for a parting CANCEL frame.
    func trySendNoWait(_ bytes: [UInt8]) {
        guard (try? begin()) != nil else { return }
        defer { end() }
        _ = bytes.withUnsafeBytes { send(fd, $0.baseAddress!, bytes.count, MSG_DONTWAIT) }
    }

    // MARK: teardown

    /// Unblocks any reader/writer without releasing the fd. Safe to call from any thread, any time.
    public func shutdown() {
        lock.lock(); defer { lock.unlock() }
        if closed || fdReleased { return }
        Darwin.shutdown(fd, SHUT_RDWR)
    }

    public func close() {
        lock.lock()
        if closed { lock.unlock(); return }
        closed = true
        let busy = inflight > 0
        let release = !busy && !fdReleased
        if release { fdReleased = true }
        if busy { Darwin.shutdown(fd, SHUT_RDWR) }      // wake blocked readers; the last one out closes the fd
        lock.unlock()
        if release { Darwin.close(fd) }
    }
}

/// Always-on inbound listener on an ephemeral port.
///
/// The accept thread polls (so `stop()` never relies on a close waking a blocked `accept`) and is the
/// only party that closes the listening fd — no window where the number is recycled under it.
/// Transient accept errors (aborted handshakes, fd exhaustion) never kill the listener.
public final class TCPListener {
    private final class Generation { var stop = false }

    private let lock = NSLock()
    private var gen: Generation?
    private var _port = 0

    public init() {}
    deinit { stop() }

    public var port: Int { lock.lock(); defer { lock.unlock() }; return _port }

    /// Binds a dual-stack socket and accepts on a background thread. Restarts cleanly if already running.
    public func start(onSocket: @escaping (TCPSocket, String) -> Void) throws {
        stop()
        let (s, boundPort) = try TCPListener.bind()
        let g = Generation()
        lock.lock(); gen = g; _port = boundPort; lock.unlock()
        let t = Thread { [weak self] in
            defer {
                Darwin.close(s)
                if let self { self.lock.lock(); if self.gen === g { self.gen = nil; self._port = 0 }; self.lock.unlock() }
            }
            while true {
                self?.lock.lock(); let stopped = g.stop; self?.lock.unlock()
                if stopped { return }
                var pfd = pollfd(fd: s, events: Int16(POLLIN), revents: 0)
                let pr = poll(&pfd, 1, 100)
                if pr == 0 { continue }
                if pr < 0 { if errno == EINTR { continue }; return }
                var peer = sockaddr_storage()
                var plen = socklen_t(MemoryLayout<sockaddr_storage>.size)
                let c = withUnsafeMutablePointer(to: &peer) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(s, $0, &plen) }
                }
                if c < 0 {
                    switch errno {
                    case EINTR, ECONNABORTED, EAGAIN, EPROTO, ENETDOWN, ENETUNREACH, EHOSTDOWN, EHOSTUNREACH, ENOPROTOOPT:
                        continue
                    case EMFILE, ENFILE, ENOBUFS, ENOMEM:
                        usleep(100_000); continue                      // out of resources — back off, keep listening
                    default:
                        return                                         // listener fd is broken; `port` drops to 0 so the app can rebind
                    }
                }
                self?.lock.lock(); let late = g.stop; self?.lock.unlock()
                if late { Darwin.close(c); return }
                if self == nil { Darwin.close(c); return }
                onSocket(TCPSocket(fd: c), TCPListener.describe(peer, plen))
            }
        }
        t.name = "crossdrop-accept"
        t.qualityOfService = .userInitiated
        t.start()
    }

    /// Non-blocking: the accept thread notices within ~100 ms and closes the listening socket itself.
    public func stop() {
        lock.lock()
        gen?.stop = true
        gen = nil
        _port = 0
        lock.unlock()
    }

    private static func bind() throws -> (Int32, Int) {
        var one: Int32 = 1
        let sz = socklen_t(MemoryLayout<Int32>.size)
        // Prefer a dual-stack IPv6 socket; fall back to IPv4-only where IPv6 is unavailable.
        let s6 = socket(AF_INET6, SOCK_STREAM, 0)
        if s6 >= 0 {
            setsockopt(s6, SOL_SOCKET, SO_REUSEADDR, &one, sz)
            var off: Int32 = 0
            setsockopt(s6, IPPROTO_IPV6, IPV6_V6ONLY, &off, sz)
            var addr = sockaddr_in6()
            addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            addr.sin6_family = sa_family_t(AF_INET6)
            addr.sin6_port = 0
            addr.sin6_addr = in6addr_any
            let rc = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(s6, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) }
            }
            if rc == 0, listen(s6, 32) == 0 {
                var bound = sockaddr_in6()
                var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
                _ = withUnsafeMutablePointer(to: &bound) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(s6, $0, &len) }
                }
                return (s6, Int(UInt16(bigEndian: bound.sin6_port)))
            }
            Darwin.close(s6)
        }
        let s4 = socket(AF_INET, SOCK_STREAM, 0)
        guard s4 >= 0 else { throw ProtocolError("socket() failed: \(String(cString: strerror(errno)))") }
        setsockopt(s4, SOL_SOCKET, SO_REUSEADDR, &one, sz)
        var a4 = sockaddr_in()
        a4.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        a4.sin_family = sa_family_t(AF_INET)
        a4.sin_addr.s_addr = INADDR_ANY.bigEndian
        let rc4 = withUnsafePointer(to: &a4) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(s4, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc4 == 0, listen(s4, 32) == 0 else {
            let e = String(cString: strerror(errno))
            Darwin.close(s4)
            throw ProtocolError("bind/listen failed: \(e)")
        }
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(s4, $0, &len) }
        }
        return (s4, Int(UInt16(bigEndian: bound.sin_port)))
    }

    private static func describe(_ ss: sockaddr_storage, _ len: socklen_t) -> String {
        var ss = ss
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        _ = withUnsafePointer(to: &ss) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getnameinfo($0, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
        }
        return String(cString: host)
    }
}
