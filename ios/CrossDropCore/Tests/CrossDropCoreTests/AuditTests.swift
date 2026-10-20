import XCTest
import CryptoKit
import Darwin
@testable import CrossDropCore

// MARK: - helpers

final class Recorder {
    private let lock = NSLock()
    private(set) var events: [TransferEvent] = []
    let finished = DispatchSemaphore(value: 0)
    func record(_ e: TransferEvent) {
        lock.lock(); events.append(e); lock.unlock()
        switch e { case .completed, .failed, .cancelled, .declinedByPeer: finished.signal(); default: break }
    }
    var terminal: TransferEvent? {
        lock.lock(); defer { lock.unlock() }
        return events.last { switch $0 { case .completed, .failed, .cancelled, .declinedByPeer: return true; default: return false } }
    }
    var failure: String? { if case .failed(let m) = terminal { return m }; return nil }
    var completed: Bool { if case .completed = terminal { return true }; return false }
    var cancelled: Bool { if case .cancelled = terminal { return true }; return false }
    func wait(_ seconds: TimeInterval = 20) -> Bool { finished.wait(timeout: .now() + seconds) == .success }
}

func tmpDir() -> URL {
    let d = FileManager.default.temporaryDirectory.appendingPathComponent("cd-audit-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
    return d
}

func randomBytes(_ n: Int, seed: UInt64 = 5) -> [UInt8] {
    var s = seed
    return (0..<n).map { _ in s = s &* 6364136223846793005 &+ 1442695040888963407; return UInt8(truncatingIfNeeded: s >> 33) }
}

final class FileSink: OutputSink {
    let handle: FileHandle
    var written = 0
    init(_ url: URL) { FileManager.default.createFile(atPath: url.path, contents: nil); handle = try! FileHandle(forWritingTo: url) }
    func write(_ bytes: [UInt8]) throws { written += bytes.count; try handle.write(contentsOf: Data(bytes)) }
    func close() { try? handle.close() }
}

/// A raw protocol-speaking peer (initiator side) that can send arbitrary, malicious frames.
final class EvilPeer {
    let socket: TCPSocket
    let io: FrameIO
    let ackVersion: Int

    init(port: Int, version: Int = 3, helloVersion: Int? = nil) throws {
        socket = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        socket.setTimeout(10)
        io = FrameIO(socket: socket)
        let id = SessionIdentity()
        try io.write(.hello, Hello(version: helloVersion ?? version, deviceName: "evil", publicKeyB64: b64(id.publicKey), instanceId: "dead").encodeBytes())
        let (t, p) = try io.read()
        XCTAssertEqual(t, .helloAck)
        let ack = try p.decodeJSON(HelloAck.self)
        ackVersion = ack.version
        let keys = try SessionCrypto.deriveSession(identity: id, peerPublicKey: try unb64(ack.publicKeyB64), isInitiator: true)
        io.writeCipher = RecordCipher(key: keys.sendKey)
        io.readCipher = RecordCipher(key: keys.recvKey)
    }

    /// Send an OFFER and return the receiver's Accept (nil if it declined / hung up).
    func offer(_ files: [FileMeta]) throws -> Accept? {
        try io.write(.offer, Offer(transferId: "t1", files: files).encodeBytes())
        let (t, p) = try io.read()
        return t == .accept ? try p.decodeJSON(Accept.self) : nil
    }

    func close() { socket.close() }
}

/// Starts an engine-backed receiver on loopback.
final class TestReceiver {
    let listener = TCPListener()
    let rec = Recorder()
    let inbox: URL
    let store: FileDeltaStore
    var decided = 0
    var sinks: [FileSink] = []
    let engine: TransferEngine
    private let lock = NSLock()

    init(inbox: URL = tmpDir(), version: Int = 3, useDelta: Bool = true, decision: @escaping (Offer) -> Bool = { _ in true }) {
        self.inbox = inbox
        store = FileDeltaStore(inboxDir: inbox)
        engine = TransferEngine(identity: SessionIdentity(), deviceName: { "recv" }, instanceId: "bbbb", protocolVersion: version)
        try! listener.start { [self] sock, _ in
            Thread.detachNewThread {
                engine.receive(socket: sock, decide: { _, offer in
                    lock.lock(); decided += 1; lock.unlock()
                    guard decision(offer) else { return .reject("no") }
                    return .accept(sinkFor: { meta in
                        // same collision handling as the app's InboxStore.sinkFor (v1 path)
                        let safe = FileDeltaStore.sanitizedFileName(meta.name)
                        let ext = (safe as NSString).pathExtension, stem = (safe as NSString).deletingPathExtension
                        var target = inbox.appendingPathComponent(safe); var i = 1
                        while FileManager.default.fileExists(atPath: target.path) {
                            target = inbox.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)"); i += 1
                        }
                        let s = FileSink(target)
                        lock.lock(); sinks.append(s); lock.unlock()
                        return s
                    }, delta: useDelta ? store : nil)
                }, emit: { [self] e in rec.record(e) })
            }
        }
    }
    deinit { listener.stop() }
    var port: Int { listener.port }
}

// MARK: - crypto

final class CryptoHardeningTests: XCTestCase {
    func testLowOrderAndMalformedPeerKeysAreRejected() throws {
        let id = SessionIdentity()
        let zero = [UInt8](repeating: 0, count: 32)
        var one = zero; one[0] = 1                         // the identity point
        // order-8 point from RFC 7748 §6 (small-subgroup)
        let order8 = [UInt8]("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800".chunked(2).map { UInt8($0, radix: 16)! })
        for bad in [zero, one, order8] {
            XCTAssertThrowsError(try SessionCrypto.deriveSession(identity: id, peerPublicKey: bad, isInitiator: true), "low-order key accepted")
        }
        XCTAssertThrowsError(try SessionCrypto.deriveSession(identity: id, peerPublicKey: [1, 2, 3], isInitiator: true))
        XCTAssertThrowsError(try SessionCrypto.deriveSession(identity: id, peerPublicKey: [], isInitiator: false))
    }

    func testBothSidesDeriveMirroredKeysAndSameSas() throws {
        let a = SessionIdentity(), b = SessionIdentity()
        let ka = try SessionCrypto.deriveSession(identity: a, peerPublicKey: b.publicKey, isInitiator: true)
        let kb = try SessionCrypto.deriveSession(identity: b, peerPublicKey: a.publicKey, isInitiator: false)
        XCTAssertEqual(ka.sendKey, kb.recvKey); XCTAssertEqual(ka.recvKey, kb.sendKey)
        XCTAssertNotEqual(ka.sendKey, ka.recvKey)
        XCTAssertEqual(ka.sas, kb.sas)
        XCTAssertEqual(ka.sas.count, 6)
    }

    func testRecordCipherRejectsTamperReorderAndReplay() throws {
        let key = [UInt8](repeating: 7, count: 32)
        let enc = RecordCipher(key: key), dec = RecordCipher(key: key)
        let r1 = try enc.seal([1, 2, 3]), r2 = try enc.seal([4, 5, 6])
        XCTAssertThrowsError(try dec.open(r2))              // out of order (nonce 0 expected)
        let dec2 = RecordCipher(key: key)
        var bad = r1; bad[0] ^= 1
        XCTAssertThrowsError(try dec2.open(bad))
        let dec3 = RecordCipher(key: key)
        XCTAssertEqual(try dec3.open(r1), [1, 2, 3])
        XCTAssertEqual(try dec3.open(r2), [4, 5, 6])
        XCTAssertThrowsError(try dec3.open(r1))             // replay
        XCTAssertThrowsError(try dec3.open([1, 2, 3]))      // shorter than a tag
    }
}

private extension String {
    func chunked(_ n: Int) -> [String] {
        stride(from: 0, to: count, by: n).map { String(self[index(startIndex, offsetBy: $0)..<index(startIndex, offsetBy: min($0 + n, count))]) }
    }
}

// MARK: - sockets

final class SocketLifecycleTests: XCTestCase {
    private func pair() -> (Int32, Int32) {
        var fds: [Int32] = [0, 0]
        XCTAssertEqual(socketpair(AF_UNIX, SOCK_STREAM, 0, &fds), 0)
        return (fds[0], fds[1])
    }

    func testCloseWakesBlockedReaderAndReleasesFdExactlyOnce() {
        let (a, b) = pair()
        let sock = TCPSocket(fd: a)
        let done = DispatchSemaphore(value: 0)
        var threw = false
        Thread.detachNewThread { threw = (try? sock.readFully(10)) == nil; done.signal() }
        usleep(150_000)
        sock.close()
        XCTAssertEqual(done.wait(timeout: .now() + 3), .success, "blocked read was not woken by close()")
        XCTAssertTrue(threw)
        usleep(50_000)
        XCTAssertEqual(fcntl(a, F_GETFD), -1, "fd should be released after the last in-flight op")
        XCTAssertEqual(errno, EBADF)
        sock.close(); sock.close()                          // idempotent
        Darwin.close(b)
    }

    func testStaleSocketNeverTouchesARecycledFd() {
        let (a, b) = pair()
        let stale = TCPSocket(fd: a)
        stale.close()
        // The kernel hands the same numbers out again.
        var reused: (Int32, Int32) = (-1, -1)
        for _ in 0..<8 { let p = pair(); if p.0 == a || p.1 == a { reused = p; break }; Darwin.close(p.0); Darwin.close(p.1) }
        XCTAssertTrue(reused.0 == a || reused.1 == a, "test needs fd reuse")
        // Late callers (e.g. a CancelToken fired after the session ended) must be inert.
        stale.shutdown()
        stale.trySendNoWait([1, 2, 3])
        stale.setTimeout(1)
        XCTAssertThrowsError(try stale.writeAll([9]))
        XCTAssertThrowsError(try stale.readFully(1))
        let other = reused.0 == a ? reused.1 : reused.0
        // the recycled pair must be fully intact: nothing was injected, and it is not shut down
        _ = fcntl(other, F_SETFL, O_NONBLOCK)
        var byte: UInt8 = 0
        XCTAssertEqual(recv(other, &byte, 1, 0), -1)
        XCTAssertEqual(errno, EAGAIN)
        XCTAssertEqual(send(a, "x", 1, 0), 1)
        Darwin.close(reused.0); Darwin.close(reused.1); Darwin.close(b)
    }

    func testListenerSurvivesAbortedConnectionsAndStopsCleanly() throws {
        let l = TCPListener()
        var accepted = 0
        let lock = NSLock()
        try l.start { s, _ in lock.lock(); accepted += 1; lock.unlock(); s.close() }
        let port = l.port
        XCTAssertGreaterThan(port, 0)
        // clients that reset the connection before accept() runs (RST via SO_LINGER 0)
        for _ in 0..<20 {
            let c = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 2)
            var lg = linger(l_onoff: 1, l_linger: 0)
            setsockopt(c.fd, SOL_SOCKET, SO_LINGER, &lg, socklen_t(MemoryLayout<linger>.size))
            c.close()
        }
        usleep(300_000)
        // a normal client must still get through afterwards
        lock.lock(); let before = accepted; lock.unlock()
        let ok = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 2)
        usleep(300_000)
        lock.lock(); let after = accepted; lock.unlock()
        XCTAssertGreaterThan(after, before, "listener died after aborted handshakes")
        ok.close()
        l.stop()
        XCTAssertEqual(l.port, 0)
        usleep(400_000)                                    // accept thread notices and closes the listening fd
        XCTAssertThrowsError(try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 1), "port still open after stop()")
    }

    func testListenerRestartGivesFreshWorkingPort() throws {
        let l = TCPListener()
        var n = 0
        let lock = NSLock()
        try l.start { s, _ in lock.lock(); n += 1; lock.unlock(); s.close() }
        let p1 = l.port
        try l.start { s, _ in lock.lock(); n += 10; lock.unlock(); s.close() }     // start() twice == restart
        let p2 = l.port
        XCTAssertGreaterThan(p2, 0)
        _ = p1
        let c = try TCPSocket.connect(host: "127.0.0.1", port: p2, timeout: 2)
        usleep(300_000)
        c.close()
        lock.lock(); XCTAssertEqual(n, 10); lock.unlock()
        l.stop()
    }

    func testConnectHonoursCancellation() {
        let start = Date()
        let cancelAt = start.addingTimeInterval(0.3)
        // TEST-NET-1: packets are dropped, so a plain connect would sit out the whole timeout.
        XCTAssertThrowsError(try TCPSocket.connect(host: "192.0.2.1", port: 9, timeout: 10, isCancelled: { Date() > cancelAt }))
        XCTAssertLessThan(Date().timeIntervalSince(start), 3, "connect ignored cancellation")
    }

    func testOversizedAndZeroFrameLengthsAreRejected() throws {
        let (a, b) = pair()
        let rx = FrameIO(socket: TCPSocket(fd: a))
        let tx = TCPSocket(fd: b)
        try tx.writeAll(be32(UInt32(FrameIO.maxFrame + 1)))
        XCTAssertThrowsError(try rx.read())
        let (c, d) = pair()
        let rx2 = FrameIO(socket: TCPSocket(fd: c))
        try TCPSocket(fd: d).writeAll(be32(0))
        XCTAssertThrowsError(try rx2.read())
    }

    func testCancelAndShutdownNeverBlocksOnAStalledWriter() throws {
        // A writer blocked on a full socket buffer holds the write lock; cancel (UI thread!) must still return at once.
        let (a, b) = pair()
        var buf: Int32 = 4096
        setsockopt(a, SOL_SOCKET, SO_SNDBUF, &buf, socklen_t(4))
        let sock = TCPSocket(fd: a)
        let io = FrameIO(socket: sock)
        let key = [UInt8](repeating: 1, count: 32)
        io.writeCipher = RecordCipher(key: key)
        let writerDone = DispatchSemaphore(value: 0)
        Thread.detachNewThread { _ = try? io.write(.data, [UInt8](repeating: 0, count: 3_000_000)); writerDone.signal() }
        usleep(300_000)                                    // writer is now blocked in send()
        let t0 = Date()
        io.cancelAndShutdown(reason: "x")
        XCTAssertLessThan(Date().timeIntervalSince(t0), 0.5, "cancel blocked behind a stalled write")
        XCTAssertEqual(writerDone.wait(timeout: .now() + 3), .success, "stalled writer not unblocked by shutdown")
        sock.close(); Darwin.close(b)
    }
}
