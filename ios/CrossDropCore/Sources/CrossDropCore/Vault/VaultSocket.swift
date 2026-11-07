import Foundation
import Darwin

extension TCPSocket {
    /// Peek the next byte without consuming it. nil on clean EOF.
    func peekByte() throws -> UInt8? {
        var b: UInt8 = 0
        while true {
            let n = recv(fd, &b, 1, MSG_PEEK)
            if n == 1 { return b }
            if n == 0 { return nil }
            if errno == EINTR { continue }
            if errno == EAGAIN || errno == EWOULDBLOCK { throw ProtocolError("timed out waiting for peer") }
            throw ProtocolError("read failed: \(String(cString: strerror(errno)))")
        }
    }

    /// One recv(): up to `max` bytes, empty on EOF.
    func recvSome(_ max: Int) throws -> [UInt8] {
        var buf = [UInt8](repeating: 0, count: max)
        while true {
            let n = buf.withUnsafeMutableBytes { recv(fd, $0.baseAddress!, max, 0) }
            if n == 0 { return [] }
            if n > 0 { return Array(buf[0..<n]) }
            if errno == EINTR { continue }
            if errno == EAGAIN || errno == EWOULDBLOCK { throw ProtocolError("timed out waiting for peer") }
            throw ProtocolError("read failed: \(String(cString: strerror(errno)))")
        }
    }

    /// Numeric remote address, "?" when unknown.
    var peerAddress: String {
        var ss = sockaddr_storage()
        var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
        let rc = withUnsafeMutablePointer(to: &ss) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getpeername(fd, $0, &len) }
        }
        guard rc == 0 else { return "?" }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let rc2 = withUnsafePointer(to: &ss) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getnameinfo($0, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
        }
        return rc2 == 0 ? String(cString: host) : "?"
    }
}

/// Buffered, pushback-capable reader/writer over a socket — the HTTP handler's `InputStream`.
final class HttpConn: VaultByteSource {
    let socket: TCPSocket
    private var buf: [UInt8] = []
    private var pos = 0

    init(socket: TCPSocket) { self.socket = socket }

    private func fill() throws -> Bool {
        let chunk = try socket.recvSome(64 * 1024)
        if chunk.isEmpty { return false }
        buf = chunk; pos = 0
        return true
    }

    /// -1 on EOF.
    func readByte() throws -> Int {
        if pos >= buf.count, !(try fill()) { return -1 }
        let b = buf[pos]; pos += 1
        return Int(b)
    }

    /// Up to `max` bytes; whatever is buffered first, else one recv. Empty = EOF.
    func read(_ max: Int) throws -> [UInt8] {
        if pos >= buf.count, !(try fill()) { return [] }
        let n = min(max, buf.count - pos)
        let out = Array(buf[pos..<pos + n]); pos += n
        return out
    }

    func unread(_ data: [UInt8]) {
        if data.isEmpty { return }
        buf = data + Array(buf[pos...]); pos = 0
    }

    func write(_ bytes: [UInt8]) throws { try socket.writeAll(bytes) }
}
