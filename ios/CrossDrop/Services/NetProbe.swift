import Foundation
@preconcurrency import CrossDropCore

enum NetProbe {
    /// True if something accepts connections on this device's loopback `port` (used after a suspension
    /// to find out whether iOS revoked one of our listening sockets).
    static func isListening(port: Int) -> Bool {
        guard port > 0, let s = try? TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 0.7) else { return false }
        s.close()
        return true
    }
}
