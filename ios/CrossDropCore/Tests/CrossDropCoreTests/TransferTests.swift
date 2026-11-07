import XCTest
@testable import CrossDropCore

final class TransferTests: XCTestCase {
    private func tmpDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("cd-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private func randomFile(in dir: URL, name: String, size: Int, seed: UInt64 = 7) -> URL {
        var s = seed
        let bytes = (0..<size).map { _ -> UInt8 in
            s = s &* 6364136223846793005 &+ 1442695040888963407
            return UInt8(truncatingIfNeeded: s >> 33)
        }
        let url = dir.appendingPathComponent(name)
        try! Data(bytes).write(to: url)
        return url
    }

    final class Sink: OutputSink {
        let handle: FileHandle
        init(_ url: URL) { FileManager.default.createFile(atPath: url.path, contents: nil); handle = try! FileHandle(forWritingTo: url) }
        func write(_ bytes: [UInt8]) throws { try handle.write(contentsOf: Data(bytes)) }
        func close() { try? handle.close() }
    }

    /// Runs a full loopback transfer; returns (sender events, receiver events).
    private func run(files: [URL], inbox: URL, senderVersion: Int = 3, receiverVersion: Int = 3,
                     store: FileDeltaStore? = nil, useDelta: Bool = true) -> ([TransferEvent], [TransferEvent]) {
        let sEng = TransferEngine(identity: SessionIdentity(), deviceName: { "sender" }, instanceId: "aaaa", protocolVersion: senderVersion)
        let rEng = TransferEngine(identity: SessionIdentity(), deviceName: { "receiver" }, instanceId: "bbbb", protocolVersion: receiverVersion)
        let store = store ?? FileDeltaStore(inboxDir: inbox)
        let listener = TCPListener()
        var rEvents: [TransferEvent] = [], sEvents: [TransferEvent] = []
        let lock = NSLock()
        let rDone = DispatchSemaphore(value: 0), sDone = DispatchSemaphore(value: 0)
        try! listener.start { sock, _ in
            Thread.detachNewThread {
                rEng.receive(socket: sock, decide: { _, _ in
                    .accept(sinkFor: { Sink(inbox.appendingPathComponent($0.name)) }, delta: useDelta ? store : nil)
                }, emit: { e in
                    lock.lock(); rEvents.append(e); lock.unlock()
                    switch e { case .completed, .failed, .cancelled, .declinedByPeer: rDone.signal(); default: break }
                })
            }
        }
        let outs = files.map { u -> OutgoingFile in
            let size = ((try? FileManager.default.attributesOfItem(atPath: u.path))?[.size] as? NSNumber)?.int64Value ?? 0
            return OutgoingFile(meta: FileMeta(name: u.lastPathComponent, size: size), url: u)
        }
        Thread.detachNewThread {
            sEng.send(host: "127.0.0.1", port: listener.port, files: outs, emit: { e in
                lock.lock(); sEvents.append(e); lock.unlock()
                switch e { case .completed, .failed, .cancelled, .declinedByPeer: sDone.signal(); default: break }
            })
        }
        XCTAssertEqual(sDone.wait(timeout: .now() + 60), .success)
        XCTAssertEqual(rDone.wait(timeout: .now() + 60), .success)
        listener.stop()
        return (sEvents, rEvents)
    }

    private func completed(_ ev: [TransferEvent]) -> Bool {
        if case .completed = ev.last { return true }
        return false
    }

    private func skipped(_ ev: [TransferEvent]) -> Int64 {
        for e in ev.reversed() { if case .progress(_, _, _, _, let s) = e { return s } }
        return 0
    }

    func testV3PushRoundTripMultiFile() throws {
        let src = tmpDir(), inbox = tmpDir()
        let a = randomFile(in: src, name: "a.bin", size: 3_000_000, seed: 1)
        let b = randomFile(in: src, name: "b.bin", size: 5, seed: 2)
        let empty = src.appendingPathComponent("empty.bin"); FileManager.default.createFile(atPath: empty.path, contents: nil)
        let (s, r) = run(files: [a, b, empty], inbox: inbox)
        XCTAssertTrue(completed(s)); XCTAssertTrue(completed(r))
        for f in [a, b, empty] {
            XCTAssertEqual(try Data(contentsOf: inbox.appendingPathComponent(f.lastPathComponent)), try Data(contentsOf: f))
        }
    }

    func testV2DeltaSecondSendShipsNothing() throws {
        let src = tmpDir(), inbox = tmpDir()
        let a = randomFile(in: src, name: "a.bin", size: 2_000_000)
        let store = FileDeltaStore(inboxDir: inbox)
        _ = run(files: [a], inbox: inbox, store: store)
        let (s, r) = run(files: [a], inbox: inbox, store: store)
        XCTAssertTrue(completed(s)); XCTAssertTrue(completed(r))
        XCTAssertEqual(skipped(s), 2_000_000)
    }

    func testV1Fallback() throws {
        let src = tmpDir(), inbox = tmpDir()
        let a = randomFile(in: src, name: "a.bin", size: 1_000_000)
        let (s, r) = run(files: [a], inbox: inbox, senderVersion: 1, useDelta: false)
        XCTAssertTrue(completed(s)); XCTAssertTrue(completed(r))
        XCTAssertEqual(try Data(contentsOf: inbox.appendingPathComponent("a.bin")), try Data(contentsOf: a))
    }

    func testV2NegotiatedWhenOnePeerOlder() throws {
        let src = tmpDir(), inbox = tmpDir()
        let a = randomFile(in: src, name: "a.bin", size: 1_000_000)
        let (s, r) = run(files: [a], inbox: inbox, senderVersion: 3, receiverVersion: 2)
        XCTAssertTrue(completed(s)); XCTAssertTrue(completed(r))
        XCTAssertEqual(try Data(contentsOf: inbox.appendingPathComponent("a.bin")), try Data(contentsOf: a))
    }
}
