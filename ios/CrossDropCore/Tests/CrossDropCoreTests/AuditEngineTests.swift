import XCTest
import Darwin
@testable import CrossDropCore

/// Hostile-peer behaviour of the receiving/sending engine and the destination-name handling.
final class EngineHardeningTests: XCTestCase {

    private func meta(_ name: String, _ size: Int64) -> FileMeta { FileMeta(name: name, size: size) }

    // MARK: liveness probes

    /// Peers check we're still listening by connecting and hanging up. That must not look like a transfer.
    func testBareConnectAndCloseIsNotASession() throws {
        let r = TestReceiver()
        let probe = try TCPSocket.connect(host: "127.0.0.1", port: r.port, timeout: 2)
        probe.close()
        XCTAssertFalse(r.rec.wait(1.5), "a liveness probe surfaced as \(r.rec.events)")
        XCTAssertTrue(r.rec.events.isEmpty)
        // ...and the listener is still serving afterwards.
        let again = try TCPSocket.connect(host: "127.0.0.1", port: r.port, timeout: 2)
        again.close()
    }

    // MARK: offer validation

    func testReceiverRefusesInvalidOffersWithoutAskingTheUser() throws {
        let bad: [[FileMeta]] = [
            [meta("neg", -1)],
            [meta("huge", Int64.max)],
            [meta("a", Int64.max / 2 + 1), meta("b", Int64.max / 2 + 1)],                         // sum overflows
            [meta("a", 1 << 59), meta("b", 1 << 59), meta("c", 1 << 59)],                       // sum above the cap
            (0...Limits.maxOfferFiles).map { meta("f\($0)", 0) },                                // 100 001 files
        ]
        for (i, files) in bad.enumerated() {
            let r = TestReceiver()
            let peer = try EvilPeer(port: r.port)
            _ = try? peer.offer(files)
            XCTAssertTrue(r.rec.wait(), "offer #\(i) not rejected")
            XCTAssertNotNil(r.rec.failure, "offer #\(i)")
            XCTAssertEqual(r.decided, 0, "user was asked about an invalid offer #\(i)")
            peer.close()
        }
    }

    // MARK: v1 overrun

    func testV1PeerCannotStreamMoreThanOffered() throws {
        let r = TestReceiver(useDelta: false)
        let peer = try EvilPeer(port: r.port, version: 1)
        XCTAssertNotNil(try peer.offer([meta("a.bin", 10)]))
        try peer.io.write(.data, be32(0) + [UInt8](repeating: 1, count: 6))
        try peer.io.write(.data, be32(0) + [UInt8](repeating: 1, count: 6))      // 12 > 10
        XCTAssertTrue(r.rec.wait())
        XCTAssertTrue(r.rec.failure?.contains("overruns") == true, "got \(String(describing: r.rec.failure))")
        XCTAssertLessThanOrEqual(r.sinks.first?.written ?? 0, 10, "bytes beyond the offer were written")
    }

    // MARK: v3 push

    func testPushRejectsUndersizedMidFileChunks() throws {
        let r = TestReceiver()
        let peer = try EvilPeer(port: r.port)
        let a = try peer.offer([meta("a.bin", 1_000)])
        XCTAssertEqual(a?.push, true)
        for _ in 0..<2 {
            let chunk: [UInt8] = [9]
            try peer.io.write(.pushChunk, ChunkCodec.encodePush(fileIndex: 0, hash: Blake3.hash(chunk), payload: chunk))
        }
        XCTAssertTrue(r.rec.wait())
        XCTAssertTrue(r.rec.failure?.contains("undersized") == true, "got \(String(describing: r.rec.failure))")
    }

    func testPushRejectsOverrunAndBadHash() throws {
        for scenario in ["overrun", "hash"] {
            let r = TestReceiver()
            let peer = try EvilPeer(port: r.port)
            XCTAssertNotNil(try peer.offer([meta("a.bin", 100)]))
            let chunk = [UInt8](repeating: 3, count: scenario == "overrun" ? 101 : 100)
            let hash = scenario == "hash" ? [UInt8](repeating: 0, count: 32) : Blake3.hash(chunk)
            try peer.io.write(.pushChunk, ChunkCodec.encodePush(fileIndex: 0, hash: hash, payload: chunk))
            XCTAssertTrue(r.rec.wait())
            XCTAssertNotNil(r.rec.failure, scenario)
            // nothing half-written may survive in the inbox
            let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: r.inbox.path))?.filter { !$0.hasPrefix(".") } ?? []
            XCTAssertEqual(leftovers, [], "\(scenario): stray files \(leftovers)")
        }
    }

    func testDoneWithoutRootsIsNotAccepted() throws {
        // receiver side: a v3 sender that finishes without per-file roots must fail the transfer
        let r = TestReceiver()
        let peer = try EvilPeer(port: r.port)
        XCTAssertNotNil(try peer.offer([meta("a.bin", 0)]))
        let root = Blake3.hash([UInt8]())
        try peer.io.write(.pushEnd, ChunkCodec.encodePushEnd(fileIndex: 0, count: 0, root: root))
        try peer.io.write(.done, Done(transferId: "t1", sha256: "", roots: nil).encodeBytes())
        XCTAssertTrue(r.rec.wait())
        XCTAssertEqual(r.rec.failure, "integrity check failed")
    }

    func testSenderRejectsAReceiverThatEchoesNoRoots() throws {
        // evil receiver: handshake, accept (push), swallow the data, answer DONE without roots
        let listener = TCPListener()
        try listener.start { sock, _ in
            Thread.detachNewThread {
                let io = FrameIO(socket: sock)
                defer { sock.close() }
                guard let (_, hp) = try? io.read(), let hello = try? hp.decodeJSON(Hello.self) else { return }
                let id = SessionIdentity()
                try? io.write(.helloAck, HelloAck(version: 3, deviceName: "evil", publicKeyB64: b64(id.publicKey), instanceId: "x").encodeBytes())
                guard let keys = try? SessionCrypto.deriveSession(identity: id, peerPublicKey: unb64(hello.publicKeyB64), isInitiator: false) else { return }
                io.writeCipher = RecordCipher(key: keys.sendKey); io.readCipher = RecordCipher(key: keys.recvKey)
                _ = try? io.read()                                                   // OFFER
                try? io.write(.accept, Accept(transferId: "t", delta: true, push: true).encodeBytes())
                while let (t, _) = try? io.read() {
                    if t == .done { try? io.write(.done, Done(transferId: "t", sha256: "", roots: nil).encodeBytes()); break }
                }
            }
        }
        defer { listener.stop() }
        let dir = tmpDir(); let f = dir.appendingPathComponent("x.bin"); try Data(randomBytes(50_000)).write(to: f)
        let rec = Recorder()
        TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
            .send(host: "127.0.0.1", port: listener.port, files: [OutgoingFile(meta: meta("x.bin", 50_000), url: f)], emit: rec.record)
        XCTAssertEqual(rec.failure, "integrity check failed")
    }

    // MARK: v2 manifest

    private func manifestPath(_ send: (EvilPeer) throws -> Void, expect: String) throws {
        let inbox = tmpDir()
        // a staged chunk makes canPush() false, so the receiver asks for the manifest (v2) path
        let store = FileDeltaStore(inboxDir: inbox)
        let pool = store.stagingPool()
        try Data([1]).write(to: pool.appendingPathComponent(String(repeating: "a", count: 64) + ".bin"))
        let r = TestReceiver(inbox: inbox)
        let peer = try EvilPeer(port: r.port)
        let accept = try peer.offer([meta("a.bin", 3_000_000)])
        XCTAssertEqual(accept?.delta, true); XCTAssertEqual(accept?.push, false)
        try send(peer)
        XCTAssertTrue(r.rec.wait())
        XCTAssertTrue(r.rec.failure?.contains(expect) == true, "expected '\(expect)', got \(String(describing: r.rec.failure))")
    }

    func testManifestWithTooManyPartsIsRejected() throws {
        try manifestPath({ peer in
            // size 3 MB ⇒ at most one part; claim a million
            let hdr = be32(0) + be32(0) + be32(1_000_000) + be32(0)
            try peer.io.write(.chunkMap, hdr)
        }, expect: "too many chunk-map parts")
    }

    func testManifestWithUndersizedMiddleChunkIsRejected() throws {
        try manifestPath({ peer in
            let h = [UInt8](repeating: 1, count: 32)
            // 100 + 11 × 256 KiB + 116 316 = 3 000 000, but the first (non-final) chunk is far below minSize
            let entries = [ChunkEntry(len: 100, hash: h)] + Array(repeating: ChunkEntry(len: FastCdc.maxSize, hash: h), count: 11)
                + [ChunkEntry(len: 3_000_000 - 100 - 11 * FastCdc.maxSize, hash: h)]
            try peer.io.write(.chunkMap, ChunkCodec.encodeMapPart(fileIndex: 0, part: 0, parts: 1, entries: entries[...]))
        }, expect: "undersized chunk")
    }

    func testManifestWithOversizedChunkIsRejected() throws {
        try manifestPath({ peer in
            let h = [UInt8](repeating: 1, count: 32)
            try peer.io.write(.chunkMap, ChunkCodec.encodeMapPart(fileIndex: 0, part: 0, parts: 1,
                                                                   entries: [ChunkEntry(len: 3_000_000, hash: h)][...]))
        }, expect: "bad chunk len")
    }

    func testManifestSizeMismatchIsRejected() throws {
        try manifestPath({ peer in
            let h = [UInt8](repeating: 1, count: 32)
            try peer.io.write(.chunkMap, ChunkCodec.encodeMapPart(fileIndex: 0, part: 0, parts: 1,
                                                                   entries: [ChunkEntry(len: 200_000, hash: h)][...]))
        }, expect: "manifest size mismatch")
    }

    // MARK: destinations

    func testDuplicateAndCaseCollidingNamesInOneOfferDoNotOverwrite() throws {
        for (label, version) in [("v3 push", 3), ("v2 delta", 2), ("v1", 1)] {
            let src = tmpDir()
            let a1 = src.appendingPathComponent("one"); try FileManager.default.createDirectory(at: a1, withIntermediateDirectories: true)
            let a2 = src.appendingPathComponent("two"); try FileManager.default.createDirectory(at: a2, withIntermediateDirectories: true)
            let a3 = src.appendingPathComponent("three"); try FileManager.default.createDirectory(at: a3, withIntermediateDirectories: true)
            let d1 = randomBytes(300_000, seed: 1), d2 = randomBytes(200_000, seed: 2), d3 = randomBytes(70_000, seed: 3)
            try Data(d1).write(to: a1.appendingPathComponent("photo.jpg"))
            try Data(d2).write(to: a2.appendingPathComponent("photo.jpg"))                 // same name, other folder
            try Data(d3).write(to: a3.appendingPathComponent("PHOTO.JPG"))                 // differs only by case
            let r = TestReceiver(version: version, useDelta: version >= 2)
            let rec = Recorder()
            let outs = [a1.appendingPathComponent("photo.jpg"), a2.appendingPathComponent("photo.jpg"), a3.appendingPathComponent("PHOTO.JPG")]
                .map { OutgoingFile(meta: FileMeta(name: $0.lastPathComponent, size: Int64((try! Data(contentsOf: $0)).count)), url: $0) }
            TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa", protocolVersion: version)
                .send(host: "127.0.0.1", port: r.port, files: outs, emit: rec.record)
            XCTAssertTrue(rec.completed, "\(label): \(String(describing: rec.terminal))")
            XCTAssertTrue(r.rec.wait()); XCTAssertTrue(r.rec.completed, label)
            let stored = ((try? FileManager.default.contentsOfDirectory(atPath: r.inbox.path)) ?? []).filter { !$0.hasPrefix(".") }
            XCTAssertEqual(stored.count, 3, "\(label): files overwrote each other: \(stored)")
            var contents = stored.map { try! Data(contentsOf: r.inbox.appendingPathComponent($0)) }
            for want in [d1, d2, d3] {
                if let i = contents.firstIndex(of: Data(want)) { contents.remove(at: i) } else { XCTFail("\(label): lost a file's content") }
            }
        }
    }

    func testHostileFileNamesStayInsideTheInbox() throws {
        let nasty = ["../../escape.txt", "..", ".", "/etc/passwd", "a/b/../../c.txt", ".staging", ".idx", "x\u{0}y.txt",
                     "tab\tname\n.txt", "C:\\win\\sys.txt", String(repeating: "ა", count: 300) + ".pdf", "", " ", "...hidden"]
        let parent = tmpDir()
        let inbox = parent.appendingPathComponent("inbox"); try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let src = tmpDir()
        var outs: [OutgoingFile] = []
        for (i, name) in nasty.enumerated() {
            let f = src.appendingPathComponent("src\(i)"); try Data(randomBytes(1_000 + i, seed: UInt64(i))).write(to: f)
            outs.append(OutgoingFile(meta: FileMeta(name: name, size: Int64(1_000 + i)), url: f))
        }
        let r = TestReceiver(inbox: inbox)
        let rec = Recorder()
        TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
            .send(host: "127.0.0.1", port: r.port, files: outs, emit: rec.record)
        XCTAssertTrue(rec.completed, "\(String(describing: rec.terminal))")
        XCTAssertTrue(r.rec.wait())
        // nothing outside the inbox, nothing named like our state dirs, every name short enough for APFS
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: parent.path).sorted(), ["inbox"])
        let names = try FileManager.default.contentsOfDirectory(atPath: inbox.path).filter { $0 != ".staging" && $0 != ".idx" }
        XCTAssertEqual(names.count, nasty.count, "a file was dropped or merged: \(names)")
        for n in names {
            XCTAssertLessThanOrEqual(n.utf8.count, 200, n)
            XCTAssertFalse(n.hasPrefix(".") && n != "_", n)
        }
        XCTAssertTrue(names.contains { $0.hasSuffix(".pdf") }, "long name lost its extension")
    }

    func testSanitizerAndTargetContainment() {
        XCTAssertEqual(FileDeltaStore.sanitizedFileName("../../x/y.txt"), "y.txt")
        XCTAssertEqual(FileDeltaStore.sanitizedFileName(".."), "file")
        XCTAssertEqual(FileDeltaStore.sanitizedFileName(".staging"), "staging")
        XCTAssertEqual(FileDeltaStore.sanitizedFileName(""), "file")
        XCTAssertEqual(FileDeltaStore.sanitizedFileName("a\u{0}b"), "a_b")
        let long = FileDeltaStore.sanitizedFileName(String(repeating: "é", count: 400) + ".mp4")
        XCTAssertLessThanOrEqual(long.utf8.count, 180); XCTAssertTrue(long.hasSuffix(".mp4"))
        // a careless custom sanitiser must not be able to escape or shadow our state dirs
        let inbox = tmpDir()
        let store = FileDeltaStore(inboxDir: inbox, nameFor: { $0.name })
        for n in ["..", ".", "../x", "a/b", ".staging", ".idx", ""] {
            let t = store.targetFor(FileMeta(name: n, size: 1))
            XCTAssertEqual(t.deletingLastPathComponent().standardizedFileURL.path, inbox.standardizedFileURL.path, n)
            XCTAssertFalse(t.lastPathComponent.hasPrefix("."), n)
        }
    }

    // MARK: connection limits & cancellation

    func testExcessConcurrentConnectionsAreRefused() throws {
        let r = TestReceiver()
        var stalled: [TCPSocket] = []
        for _ in 0..<Limits.maxConcurrentReceives {
            stalled.append(try TCPSocket.connect(host: "127.0.0.1", port: r.port, timeout: 2))   // connect, say nothing
        }
        usleep(300_000)
        let ninth = try TCPSocket.connect(host: "127.0.0.1", port: r.port, timeout: 2)
        ninth.setTimeout(3)
        let t0 = Date()
        XCTAssertThrowsError(try ninth.readFully(1))                 // must be closed on us, not held for the 15 s hello timeout
        XCTAssertLessThan(Date().timeIntervalSince(t0), 2.5)
        XCTAssertFalse(r.rec.events.contains { if case .failed = $0 { return true }; return false },
                       "refused connections must not surface as session events")
        stalled.forEach { $0.close() }; ninth.close()
        // slots come back once those sessions end (the stalled hellos hit EOF and finish quietly)
        var ok: EvilPeer?
        for _ in 0..<50 where ok == nil {
            ok = try? EvilPeer(port: r.port)
            if ok == nil { usleep(100_000) }
        }
        XCTAssertNotNil(ok, "receive slots never came back")
        ok?.close()
    }

    func testCancelReturnsInstantlyAndEndsASenderBlockedOnAStalledReceiver() throws {
        // receiver completes the handshake, accepts, then never reads again → sender blocks in send()
        let listener = TCPListener()
        let hold = DispatchSemaphore(value: 0)
        try listener.start { sock, _ in
            Thread.detachNewThread {
                let io = FrameIO(socket: sock)
                guard let (_, hp) = try? io.read(), let hello = try? hp.decodeJSON(Hello.self) else { return }
                let id = SessionIdentity()
                try? io.write(.helloAck, HelloAck(version: 1, deviceName: "slow", publicKeyB64: b64(id.publicKey), instanceId: "x").encodeBytes())
                guard let keys = try? SessionCrypto.deriveSession(identity: id, peerPublicKey: unb64(hello.publicKeyB64), isInitiator: false) else { return }
                io.writeCipher = RecordCipher(key: keys.sendKey); io.readCipher = RecordCipher(key: keys.recvKey)
                _ = try? io.read()
                try? io.write(.accept, Accept(transferId: "t").encodeBytes())
                _ = hold.wait(timeout: .now() + 15)                                  // stop reading
                sock.close()
            }
        }
        defer { listener.stop(); hold.signal() }
        let dir = tmpDir(); let f = dir.appendingPathComponent("big.bin")
        try Data(randomBytes(40_000_000)).write(to: f)
        let token = CancelToken(); let rec = Recorder()
        Thread.detachNewThread {
            TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
                .send(host: "127.0.0.1", port: listener.port, files: [OutgoingFile(meta: FileMeta(name: "big.bin", size: 40_000_000), url: f)],
                      cancel: token, emit: rec.record)
        }
        sleep(2)                                                                      // sender is wedged in send() by now
        let t0 = Date()
        token.cancel()
        XCTAssertLessThan(Date().timeIntervalSince(t0), 0.5, "cancel() blocked the calling (UI) thread")
        XCTAssertTrue(rec.wait(5), "wedged sender never finished after cancel")
        XCTAssertTrue(rec.cancelled, "\(String(describing: rec.terminal))")
    }

    func testLateCancelAfterCompletionIsHarmless() throws {
        let src = tmpDir(); let f = src.appendingPathComponent("a.bin"); try Data(randomBytes(100_000)).write(to: f)
        let r = TestReceiver()
        let token = CancelToken(); let rec = Recorder()
        TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
            .send(host: "127.0.0.1", port: r.port, files: [OutgoingFile(meta: FileMeta(name: "a.bin", size: 100_000), url: f)], cancel: token, emit: rec.record)
        XCTAssertTrue(rec.completed)
        XCTAssertTrue(r.rec.wait())
        token.cancel(); token.cancel()                                                // must not crash or touch recycled fds
        XCTAssertTrue(rec.completed)
    }

    func testCancelDuringConnectEndsQuickly() {
        let token = CancelToken(); let rec = Recorder()
        Thread.detachNewThread {
            TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
                .send(host: "192.0.2.1", port: 9, files: [], cancel: token, emit: rec.record)
        }
        usleep(300_000)
        token.cancel()
        XCTAssertTrue(rec.wait(3), "connect phase ignored cancel")
        // either cancelled or (when the network answers 'unreachable' instantly) failed — never a hang
        XCTAssertNotNil(rec.terminal)
    }

    func testSenderGivesAClearErrorWhenTheOfferWouldExceedOneFrame() throws {
        let r = TestReceiver()
        let files = (0..<80_000).map { OutgoingFile(meta: FileMeta(name: "a-fairly-long-holiday-photo-name-\($0).jpeg", size: 1), url: URL(fileURLWithPath: "/nonexistent")) }
        let rec = Recorder()
        TransferEngine(identity: SessionIdentity(), deviceName: { "s" }, instanceId: "aaaa")
            .send(host: "127.0.0.1", port: r.port, files: files, emit: rec.record)
        XCTAssertTrue(rec.failure?.contains("smaller batches") == true, "\(String(describing: rec.failure))")
    }

    // MARK: housekeeping

    func testPrunePoolDropsStaleChunksPartsAndEnforcesSizeCap() throws {
        let inbox = tmpDir(); let store = FileDeltaStore(inboxDir: inbox)
        let pool = store.stagingPool()
        let fm = FileManager.default
        func make(_ url: URL, bytes: Int, age: TimeInterval) {
            try! Data(count: bytes).write(to: url)
            try! fm.setAttributes([.modificationDate: Date().addingTimeInterval(-age)], ofItemAtPath: url.path)
        }
        let old = pool.appendingPathComponent(String(repeating: "1", count: 64) + ".bin")
        let fresh1 = pool.appendingPathComponent(String(repeating: "2", count: 64) + ".bin")
        let fresh2 = pool.appendingPathComponent(String(repeating: "3", count: 64) + ".bin")
        let tmp = pool.appendingPathComponent("abc.123.tmp")
        make(old, bytes: 100, age: 9 * 86_400); make(fresh1, bytes: 600, age: 3_600); make(fresh2, bytes: 600, age: 60); make(tmp, bytes: 5, age: 0)
        let part = inbox.appendingPathComponent("movie.mp4.99.part"); make(part, bytes: 10, age: 2 * 86_400)
        let livePart = inbox.appendingPathComponent("other.mp4.98.part"); make(livePart, bytes: 10, age: 10)
        store.prunePool(maxBytes: 1_000)
        XCTAssertFalse(fm.fileExists(atPath: old.path), "stale chunk kept")
        XCTAssertFalse(fm.fileExists(atPath: tmp.path), ".tmp leftover kept")
        XCTAssertFalse(fm.fileExists(atPath: fresh1.path), "size cap not enforced (oldest should go first)")
        XCTAssertTrue(fm.fileExists(atPath: fresh2.path), "newest chunk must survive")
        XCTAssertFalse(fm.fileExists(atPath: part.path), "day-old .part kept")
        XCTAssertTrue(fm.fileExists(atPath: livePart.path), "in-progress .part deleted")
    }

    func testCorruptIndexSidecarIsRebuiltNotTrusted() throws {
        let inbox = tmpDir(); let store = FileDeltaStore(inboxDir: inbox)
        let data = randomBytes(300_000, seed: 8)
        let file = inbox.appendingPathComponent("doc.bin"); try Data(data).write(to: file)
        let m = FileMeta(name: "doc.bin", size: Int64(data.count))
        let good = try XCTUnwrap(store.baselineFor(m))
        XCTAssertEqual(good.chunks.reduce(0) { $0 + $1.len }, data.count)
        // forge a sidecar whose entries have a negative length but sum to the file size
        var forged = be32(0x43445833) + [UInt8](repeating: 0, count: 32)
        let st = try FileManager.default.attributesOfItem(atPath: file.path)
        let size = Int64(data.count); let mtime = Int64(((st[.modificationDate] as! Date).timeIntervalSince1970 * 1000).rounded(.down))
        forged += be32(UInt32(truncatingIfNeeded: size >> 32)) + be32(UInt32(truncatingIfNeeded: size))
        forged += be32(UInt32(truncatingIfNeeded: mtime >> 32)) + be32(UInt32(truncatingIfNeeded: mtime))
        forged += be32(2) + be32(UInt32(bitPattern: -5)) + [UInt8](repeating: 1, count: 32) + be32(UInt32(data.count + 5)) + [UInt8](repeating: 2, count: 32)
        try Data(forged).write(to: inbox.appendingPathComponent(".idx/doc.bin.idx"))
        let rebuilt = try XCTUnwrap(store.baselineFor(m))
        XCTAssertEqual(rebuilt.chunks, good.chunks, "forged sidecar was trusted")
    }

    func testPeerNamesAreCleanedForDisplay() {
        XCTAssertEqual(TransferEngine.displayName("Alice\u{202E}gpj.exe\n\t"), "Alicegpj.exe")
        XCTAssertEqual(TransferEngine.displayName(String(repeating: "x", count: 5_000)).count, Limits.maxDisplayName)
        XCTAssertEqual(TransferEngine.displayName("\u{0}\u{1}  "), "Unknown device")
    }
}
