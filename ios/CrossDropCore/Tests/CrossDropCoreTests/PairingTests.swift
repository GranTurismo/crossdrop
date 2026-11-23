import XCTest
@testable import CrossDropCore

/// "My devices" on the Swift engine: pairing, recognising a pairing, the clipboard after a proven pairing, and files that
/// prove the pairing both ways. Mirrors the Kotlin `ControlTest` / `TrustedDevicesTest` rules.
final class PairingTests: XCTestCase {
    /// A device that holds `held`, agrees to pairing requests when `agree`, and takes clipboard copies when `takeClips`.
    final class Device: ControlHandler {
        let lock = NSLock()
        var held: [PairSecret]
        var agree: Bool
        var takeClips: Bool
        private(set) var asked: [(name: String, sas: String, purpose: String?)] = []
        private(set) var pairedKeys: [String: [UInt8]] = [:]
        private(set) var clips: [(pairId: String, clipId: String, text: String)] = []
        private var seenClipIds = Set<String>()
        init(held: [PairSecret] = [], agree: Bool = true, takeClips: Bool = true) {
            self.held = held; self.agree = agree; self.takeClips = takeClips
        }
        func announced(name: String, instanceId: String, host: String, port: Int) {}
        func pairRequested(name: String, sas: String, purpose: String?) -> Bool {
            lock.lock(); defer { lock.unlock() }
            asked.append((name, sas, purpose))
            return agree
        }
        func paired(pairId: String, name: String, key: [UInt8], purpose: String?) {
            lock.lock(); defer { lock.unlock() }
            pairedKeys[pairId] = key
            held.append(PairSecret(id: pairId, key: key))
        }
        func pairings() -> [PairSecret] { lock.lock(); defer { lock.unlock() }; return held }
        func clipboard(pairId: String, clipId: String, text: String) -> Bool {
            lock.lock(); defer { lock.unlock() }
            guard takeClips, seenClipIds.insert(clipId).inserted else { return false }
            clips.append((pairId, clipId, text))
            return true
        }
    }

    /// A listener: control frames go to `control`, offers to `decide`. Counts finished receives.
    final class Host {
        private let listener = TCPListener()
        let finished = DispatchSemaphore(value: 0)
        let events = Locked<[TransferEvent]>([])
        let engine: TransferEngine
        init(control: ControlHandler, name: String = "MacBook",
             decide: @escaping (InboundRequest) -> ReceiveDecision = { _ in .reject("test") }) throws {
            let eng = TransferEngine(identity: SessionIdentity(), deviceName: { name }, instanceId: "b0b0b0b0b0b0b0b0")
            engine = eng
            let fin = finished, ev = events
            try listener.start { sock, _ in
                Thread.detachNewThread {
                    eng.receive(socket: sock, control: control, decideRequest: decide, emit: { e in ev.mutate { $0.append(e) } })
                    fin.signal()
                }
            }
        }
        var port: Int { listener.port }
        func waitDone(_ file: StaticString = #filePath, _ line: UInt = #line) {
            XCTAssertEqual(finished.wait(timeout: .now() + 15), .success, "receive never returned", file: file, line: line)
        }
        deinit { listener.stop() }
    }

    final class Locked<T> {
        private let l = NSLock()
        private var v: T
        init(_ v: T) { self.v = v }
        var value: T { l.lock(); defer { l.unlock() }; return v }
        func mutate(_ f: (inout T) -> Void) { l.lock(); f(&v); l.unlock() }
    }

    private func phone(_ name: String = "Pixel 9") -> TransferEngine {
        TransferEngine(identity: SessionIdentity(), deviceName: { name }, instanceId: "a1a1a1a1a1a1a1a1")
    }

    private func secret(_ byte: UInt8, id: String? = nil) -> PairSecret {
        PairSecret(id: id ?? String(repeating: String(format: "%02x", byte), count: 16), key: [UInt8](repeating: byte, count: 32))
    }

    // MARK: pairing

    func testAgreedPairingGivesBothSidesTheSameKeyAndShowsTheCode() throws {
        let mac = Device(agree: true)
        let host = try Host(control: mac)
        let id = ControlChannel.newPairId()
        var code = ""
        let key: [UInt8]? = try phone().control(host: "127.0.0.1", port: host.port) { ch in
            code = ch.sas
            return try ch.pair(pairId: id)
        }
        host.waitDone()
        XCTAssertNotNil(key)
        XCTAssertEqual(mac.pairedKeys[id], key, "both ends derive the same key; it never crosses the wire")
        XCTAssertEqual(mac.asked.count, 1)
        XCTAssertEqual(mac.asked[0].name, "Pixel 9")
        XCTAssertEqual(mac.asked[0].sas, code, "both screens show the same code")
        XCTAssertEqual(mac.asked[0].purpose, PairPurpose.devices)
        XCTAssertEqual(code.count, 6)
    }

    func testADeclinedPairingGivesNoKeyAndStoresNothing() throws {
        let mac = Device(agree: false)
        let host = try Host(control: mac)
        let key = try phone().control(host: "127.0.0.1", port: host.port) { try $0.pair(pairId: ControlChannel.newPairId()) }
        host.waitDone()
        XCTAssertNil(key)
        XCTAssertTrue(mac.pairedKeys.isEmpty)
    }

    func testTheOldBackupRequestCarriesNoPurpose() throws {
        let mac = Device(agree: false)
        let host = try Host(control: mac)
        _ = try phone().control(host: "127.0.0.1", port: host.port) { try $0.pair(pairId: ControlChannel.newPairId(), purpose: nil) }
        host.waitDone()
        XCTAssertNil(mac.asked.first?.purpose)
        // On the wire it is the old message exactly: no "purpose" key at all.
        XCTAssertEqual(String(decoding: try PairRequest(pairId: "p").encodeBytes(), as: UTF8.self), #"{"pairId":"p"}"#)
        XCTAssertEqual(try Array(#"{"pairId":"p","purpose":"devices"}"#.utf8).decodeJSON(PairRequest.self).purpose, "devices")
    }

    // MARK: recognising a pairing

    func testPairCheckFindsOnlyTheSharedPairing() throws {
        let a = secret(1), b = secret(2), c = secret(3)
        let host = try Host(control: Device(held: [b]))
        let found = try phone().control(host: "127.0.0.1", port: host.port) { try $0.pairCheck([a, b]) }
        host.waitDone()
        XCTAssertEqual(found, b)
        let none = try phone().control(host: "127.0.0.1", port: host.port) { try $0.pairCheck([a, c]) }
        host.waitDone()
        XCTAssertNil(none)
        // Same id, wrong key (a device that learnt the id but not the key): not recognised.
        let forged = try phone().control(host: "127.0.0.1", port: host.port) { try $0.pairCheck([PairSecret(id: b.id, key: c.key)]) }
        host.waitDone()
        XCTAssertNil(forged)
    }

    // MARK: clipboard

    func testAClipboardCopyGoesOnlyOverAProvenPairingWithAFreshIdEachTime() throws {
        let shared = secret(7)
        let mac = Device(held: [shared])
        let host = try Host(control: mac)
        let texts = ["https://example.com/a?b=c", "გამარჯობა 👋", "line one\nline two"]
        let taken: [Bool] = try phone().control(host: "127.0.0.1", port: host.port) { ch in
            XCTAssertNotNil(try ch.pairCheck([shared]))
            return try texts.map { try ch.clipboard($0) }
        }
        host.waitDone()
        XCTAssertEqual(taken, [true, true, true])
        XCTAssertEqual(mac.clips.map(\.text), texts)
        XCTAssertEqual(Set(mac.clips.map(\.clipId)).count, 3, "every copy has its own id")
        XCTAssertTrue(mac.clips.allSatisfy { $0.pairId == shared.id && $0.clipId.count == 32 })

        // Sharing off over there: refused, nothing taken.
        mac.takeClips = false
        let refused = try phone().control(host: "127.0.0.1", port: host.port) { ch -> Bool in
            XCTAssertNotNil(try ch.pairCheck([shared]))
            return try ch.clipboard("not now")
        }
        host.waitDone()
        XCTAssertFalse(refused)
        XCTAssertEqual(mac.clips.count, 3)
    }

    func testNoClipboardWithoutAProvenPairing() throws {
        let mac = Device(held: [secret(7)])
        let host = try Host(control: mac)
        // Never asked to prove anything: refused before a byte of the copy leaves.
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { try $0.clipboard("secret") })
        host.waitDone()
        // A device with the wrong key: the check fails, so the copy can't be sent.
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { ch -> Bool in
            XCTAssertNil(try ch.pairCheck([secret(8)]))
            return try ch.clipboard("secret")
        })
        host.waitDone()
        // A CLIPBOARD frame forced onto a connection whose check failed is not served (the listener hangs up).
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { ch -> Void in
            XCTAssertNil(try ch.pairCheck([secret(8)]))
            try ch.io.write(.clipboard, ClipboardPush(id: String(repeating: "a", count: 32), text: "secret").encodeBytes())
            _ = try ch.io.read()
        })
        host.waitDone()
        XCTAssertTrue(mac.clips.isEmpty)
        // Too large never leaves this device.
        let shared = secret(7)
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { ch -> Bool in
            XCTAssertNotNil(try ch.pairCheck([shared]))
            return try ch.clipboard(String(repeating: "x", count: maxClipboardBytes + 1))
        })
        host.waitDone()
        XCTAssertTrue(mac.clips.isEmpty)
        XCTAssertEqual(FrameType.clipboard.rawValue, 0x25)
    }

    func testAListenerWithoutClipboardSupportRefusesTheCopy() throws {
        // A handler that holds the pairing but never implemented `clipboard` (the protocol default): "no".
        final class Minimal: ControlHandler {
            let held: [PairSecret]
            init(_ held: [PairSecret]) { self.held = held }
            func announced(name: String, instanceId: String, host: String, port: Int) {}
            func pairings() -> [PairSecret] { held }
        }
        let shared = secret(9)
        let host = try Host(control: Minimal([shared]))
        let taken = try phone().control(host: "127.0.0.1", port: host.port) { ch -> Bool in
            XCTAssertNotNil(try ch.pairCheck([shared]))
            return try ch.clipboard("hello")
        }
        host.waitDone()
        XCTAssertFalse(taken)
    }

    // MARK: files between paired devices

    private func file(_ bytes: Int) -> OutgoingFile {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cd-\(UUID().uuidString).bin")
        try! Data((0..<bytes).map { UInt8($0 & 0xff) }).write(to: url)
        return OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: Int64(bytes)), url: url)
    }

    final class Collect: OutputSink {
        let got = Locked<Int>(0)
        func write(_ bytes: [UInt8]) throws { got.mutate { $0 += bytes.count } }
        func close() {}
    }

    private func send(_ f: OutgoingFile, to port: Int, pairKey: [UInt8]?) -> TransferEvent? {
        let done = DispatchSemaphore(value: 0)
        let last = Locked<TransferEvent?>(nil)
        Thread.detachNewThread {
            self.phone().send(host: "127.0.0.1", port: port, files: [f], options: SendOptions(pairKey: pairKey)) { e in
                switch e { case .completed, .failed, .declinedByPeer, .cancelled: last.mutate { $0 = e }; done.signal(); default: break }
            }
        }
        XCTAssertEqual(done.wait(timeout: .now() + 15), .success)
        return last.value
    }

    func testFilesProveThePairingBothWays() throws {
        let shared = secret(5)
        let sink = Collect()
        let proved = Locked<String?>(nil)
        let host = try Host(control: Device(held: [shared])) { req in
            let match = req.pairedWith([shared])
            proved.mutate { $0 = match?.id }
            return .accept(sinkFor: { _ in sink }, delta: nil, pairKey: match?.key)
        }
        guard case .completed? = send(file(5000), to: host.port, pairKey: shared.key) else { return XCTFail("expected completion") }
        host.waitDone()
        XCTAssertEqual(proved.value, shared.id, "the receiver recognised the paired sender from the offer alone")
        XCTAssertEqual(sink.got.value, 5000)
    }

    func testAReceiverThatCantProveThePairingGetsNothing() throws {
        let shared = secret(5)
        let sink = Collect()
        // Whoever answers here doesn't hold the pairing (it borrowed the name or the radar id): it can't prove it back.
        let host = try Host(control: Device()) { req in
            XCTAssertNil(req.pairedWith([self.secret(6)]))
            return .accept(sinkFor: { _ in sink }, delta: nil, pairKey: nil)
        }
        guard case .failed(let msg, _)? = send(file(5000), to: host.port, pairKey: shared.key) else { return XCTFail("expected a failure") }
        host.waitDone()
        XCTAssertEqual(msg, TransferEngine.notThePairedDevice)
        XCTAssertEqual(sink.got.value, 0, "not a byte was sent")
    }

    func testAnOfferWithoutAProofIsNotFromAPairedDevice() throws {
        let host = try Host(control: Device()) { req in
            XCTAssertNil(req.offer.pairProof)
            XCTAssertNil(req.pairedWith([self.secret(5)]))
            return .reject("test")
        }
        guard case .declinedByPeer? = send(file(10), to: host.port, pairKey: nil) else { return XCTFail("expected a decline") }
        host.waitDone()
    }
}
