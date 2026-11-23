import XCTest
@testable import CrossDropCore

/// Exchanges that carry no files (announce, pairing frames) and the pairing proofs — mirrors the Kotlin `ControlTest`.
final class ControlTests: XCTestCase {
    final class Recorder: ControlHandler {
        private let lock = NSLock()
        private var seen: [(String, String, String, Int)] = []
        func announced(name: String, instanceId: String, host: String, port: Int) {
            lock.lock(); seen.append((name, instanceId, host, port)); lock.unlock()
        }
        var calls: [(String, String, String, Int)] { lock.lock(); defer { lock.unlock() }; return seen }
        private var shakes: [(String, String, String, Int)] = []
        func shaken(name: String, instanceId: String, host: String, port: Int) {
            lock.lock(); shakes.append((name, instanceId, host, port)); lock.unlock()
        }
        var shook: [(String, String, String, Int)] { lock.lock(); defer { lock.unlock() }; return shakes }
    }

    /// A handler that only takes announcements: SHAKE reaches the protocol's default (nothing happens) and is answered.
    final class AnnounceOnly: ControlHandler {
        func announced(name: String, instanceId: String, host: String, port: Int) {}
    }

    /// A listener whose every connection goes through `receive` with `control`; collects what each one emitted and
    /// signals when the receive call has returned.
    final class Host {
        private final class State {
            let lock = NSLock()
            var events: [TransferEvent] = []
            var decided = 0
            let finished = DispatchSemaphore(value: 0)
        }
        private let listener = TCPListener()
        private let state = State()
        var events: [TransferEvent] { state.lock.lock(); defer { state.lock.unlock() }; return state.events }
        var decided: Int { state.lock.lock(); defer { state.lock.unlock() }; return state.decided }

        init(name: String = "MacBook", id: String = "b0b0b0b0b0b0b0b0", control: ControlHandler?) throws {
            let eng = TransferEngine(identity: SessionIdentity(), deviceName: { name }, instanceId: id)
            let st = state
            try listener.start { sock, _ in
                Thread.detachNewThread {
                    eng.receive(socket: sock, control: control, decideRequest: { _ in
                        st.lock.lock(); st.decided += 1; st.lock.unlock()
                        return .reject("test")
                    }, emit: { e in
                        st.lock.lock(); st.events.append(e); st.lock.unlock()
                    })
                    st.finished.signal()
                }
            }
        }
        var port: Int { listener.port }
        func waitDone(_ file: StaticString = #filePath, _ line: UInt = #line) {
            XCTAssertEqual(state.finished.wait(timeout: .now() + 10), .success, "receive never returned", file: file, line: line)
        }
        deinit { listener.stop() }
    }

    private func phone(_ name: String = "Pixel 9", id: String = "a1a1a1a1a1a1a1a1") -> TransferEngine {
        TransferEngine(identity: SessionIdentity(), deviceName: { name }, instanceId: id)
    }

    private func onlySecured(_ events: [TransferEvent]) -> Bool {
        events.allSatisfy { if case .secured = $0 { return true }; return false }
    }

    // MARK: announce

    func testAnnounceTellsTheListenerWhereToReachTheInitiator() throws {
        let rec = Recorder()
        let host = try Host(control: rec)
        let answer = try phone().announce(host: "127.0.0.1", port: host.port, listenPort: 43210)
        host.waitDone()
        XCTAssertEqual(answer.name, "MacBook")
        XCTAssertEqual(answer.instanceId, "b0b0b0b0b0b0b0b0")
        XCTAssertEqual(rec.calls.count, 1)
        let c = rec.calls[0]
        XCTAssertEqual(c.0, "Pixel 9")
        XCTAssertEqual(c.1, "a1a1a1a1a1a1a1a1")
        XCTAssertEqual(c.2, "127.0.0.1")         // the dual-stack listener's ::ffff:127.0.0.1, as plain IPv4
        XCTAssertEqual(c.3, 43210)
        XCTAssertTrue(onlySecured(host.events), "an announce must not look like a transfer: \(host.events)")
        XCTAssertEqual(host.decided, 0)
    }

    func testAnnouncedPortMustBeAPort() throws {
        let rec = Recorder()
        let host = try Host(control: rec)
        XCTAssertThrowsError(try phone().announce(host: "127.0.0.1", port: host.port, listenPort: 0))
        XCTAssertThrowsError(try phone().announce(host: "127.0.0.1", port: host.port, listenPort: 70_000))
        host.waitDone(); host.waitDone()
        XCTAssertTrue(rec.calls.isEmpty)
    }

    /// Without a handler (an app that doesn't take part) the frame fails like it did before the frames existed.
    func testWithoutAHandlerAnAnnounceIsRefused() throws {
        let host = try Host(control: nil)
        XCTAssertThrowsError(try phone().announce(host: "127.0.0.1", port: host.port, listenPort: 4000))
        host.waitDone()
        guard case .failed(let msg, _)? = host.events.last else { return XCTFail("expected a failure: \(host.events)") }
        XCTAssertTrue(msg.contains("expected OFFER"), msg)
    }

    func testAnnounceToNobodyFailsFast() {
        // A port nothing listens on: refused at once, well inside the connect timeout.
        let l = TCPListener()
        try? l.start { s, _ in s.close() }
        let dead = l.port
        l.stop()
        usleep(300_000)
        let t0 = Date()
        XCTAssertThrowsError(try phone().announce(host: "127.0.0.1", port: dead, listenPort: 4000))
        XCTAssertLessThan(Date().timeIntervalSince(t0), TransferEngine.controlConnectTimeout + 1)
    }

    // MARK: shake

    func testShakeReachesTheHandlerAndIsAnswered() throws {
        let rec = Recorder()
        let host = try Host(control: rec)
        let answer = try phone().shake(host: "127.0.0.1", port: host.port, listenPort: 43210)
        host.waitDone()
        XCTAssertEqual(answer.name, "MacBook")
        XCTAssertEqual(answer.instanceId, "b0b0b0b0b0b0b0b0")
        XCTAssertEqual(rec.shook.count, 1)
        let c = rec.shook[0]
        XCTAssertEqual(c.0, "Pixel 9")
        XCTAssertEqual(c.1, "a1a1a1a1a1a1a1a1")
        XCTAssertEqual(c.2, "127.0.0.1")
        XCTAssertEqual(c.3, 43210)
        XCTAssertTrue(rec.calls.isEmpty, "a shake is not an announcement")
        XCTAssertTrue(onlySecured(host.events), "a shake must not look like a transfer: \(host.events)")
        XCTAssertEqual(host.decided, 0)
    }

    /// Unlike ANNOUNCE, a shake without a usable port still counts: the device just can't be called back by it.
    func testShakeWithoutAUsablePortStillCounts() throws {
        let rec = Recorder()
        let host = try Host(control: rec)
        XCTAssertNoThrow(try phone().shake(host: "127.0.0.1", port: host.port, listenPort: 0))
        host.waitDone()
        XCTAssertNoThrow(try phone().shake(host: "127.0.0.1", port: host.port, listenPort: 70_000))
        host.waitDone()
        XCTAssertEqual(rec.shook.map(\.3), [0, 0])
        // A SHAKE that says nothing at all (`{}`) reads as port 0 too.
        try phone().control(host: "127.0.0.1", port: host.port) { ch -> Void in
            try ch.io.write(.shake, Array("{}".utf8))
            let (t, p) = try ch.io.read()
            XCTAssertEqual(t, .shake)
            XCTAssertEqual(try p.decodeJSON(Shake.self).port, 0)
        }
        host.waitDone()
        XCTAssertEqual(rec.shook.map(\.3), [0, 0, 0])
    }

    func testAHandlerWithoutShakeStillAnswersIt() throws {
        let host = try Host(control: AnnounceOnly())
        XCTAssertNoThrow(try phone().shake(host: "127.0.0.1", port: host.port, listenPort: 4000))
        host.waitDone()
        XCTAssertTrue(onlySecured(host.events), "\(host.events)")
    }

    /// Builds without the frame (no handler here) fail it before any session exists, so the shaker just moves on.
    func testWithoutAHandlerAShakeIsRefused() throws {
        let host = try Host(control: nil)
        XCTAssertThrowsError(try phone().shake(host: "127.0.0.1", port: host.port, listenPort: 4000))
        host.waitDone()
        guard case .failed(let msg, _)? = host.events.last else { return XCTFail("expected a failure: \(host.events)") }
        XCTAssertTrue(msg.contains("expected OFFER"), msg)
    }

    // MARK: pairing frames on a listener that holds no pairings (and agrees to none)

    func testPairRequestIsDeclined() throws {
        let host = try Host(control: Recorder())
        let reply: PairReply = try phone().control(host: "127.0.0.1", port: host.port) { ch in
            try ch.io.write(.pair, PairRequest(pairId: "00112233445566778899aabbccddeeff").encodeBytes())
            let (t, p) = try ch.io.read()
            XCTAssertEqual(t, .pair)
            return try p.decodeJSON(PairReply.self)
        }
        host.waitDone()
        XCTAssertFalse(reply.accepted)
        XCTAssertTrue(onlySecured(host.events), "\(host.events)")
    }

    func testPairCheckIsAnsweredWithTheSameNonceAndNoProofs() throws {
        let host = try Host(control: Recorder())
        let nonce = Data((0..<16).map { UInt8($0) }).base64EncodedString()
        let proof = Data(repeating: 7, count: 32).base64EncodedString()
        let answer: PairCheck = try phone().control(host: "127.0.0.1", port: host.port) { ch in
            try ch.io.write(.pairCheck, PairCheck(nonce: nonce, proofs: [proof]).encodeBytes())
            let (t, p) = try ch.io.read()
            XCTAssertEqual(t, .pairCheck)
            return try p.decodeJSON(PairCheck.self)
        }
        host.waitDone()
        XCTAssertEqual(answer.nonce, nonce)
        XCTAssertEqual(answer.proofs, [])
        XCTAssertTrue(onlySecured(host.events), "\(host.events)")
    }

    func testBadPairIdAndNonceAreRefused() throws {
        let host = try Host(control: Recorder())
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { ch -> Void in
            try ch.io.write(.pair, PairRequest(pairId: "not-an-id").encodeBytes())
            _ = try ch.io.read()
        })
        host.waitDone()
        XCTAssertThrowsError(try phone().control(host: "127.0.0.1", port: host.port) { ch -> Void in
            try ch.io.write(.pairCheck, PairCheck(nonce: Data([1, 2, 3]).base64EncodedString()).encodeBytes())
            _ = try ch.io.read()
        })
        host.waitDone()
    }

    /// A transfer still works on a listener that takes control frames.
    func testOffersStillReachTheDecision() throws {
        let host = try Host(control: Recorder())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cd-\(UUID().uuidString)")
        try Data([1, 2, 3]).write(to: url)
        let done = DispatchSemaphore(value: 0)
        var last: TransferEvent?
        Thread.detachNewThread {
            self.phone().send(host: "127.0.0.1", port: host.port, files: [OutgoingFile(meta: FileMeta(name: "a.bin", size: 3), url: url)]) { e in
                switch e { case .completed, .failed, .declinedByPeer, .cancelled: last = e; done.signal(); default: break }
            }
        }
        XCTAssertEqual(done.wait(timeout: .now() + 10), .success)
        host.waitDone()
        if case .declinedByPeer? = last {} else { XCTFail("expected the test decision (reject): \(String(describing: last))") }
        XCTAssertEqual(host.decided, 1)
    }

    // MARK: messages

    func testNewFieldsDecodeAndOldOnesDefault() throws {
        let offer = try Array(#"{"transferId":"t","files":[{"name":"a.jpg","size":1,"key":"IMG_1"}],"kind":"backup","pairProof":"cA=="}"#.utf8)
            .decodeJSON(Offer.self)
        XCTAssertEqual(offer.files[0].key, "IMG_1")
        XCTAssertEqual(offer.kind, OfferKind.backup)
        XCTAssertEqual(offer.pairProof, "cA==")
        let accept = try Array(#"{"transferId":"t","pairProof":"cQ=="}"#.utf8).decodeJSON(Accept.self)
        XCTAssertEqual(accept.pairProof, "cQ==")
        XCTAssertFalse(accept.delta)
        let old = try Array(#"{"transferId":"t","files":[{"name":"a","size":1}]}"#.utf8).decodeJSON(Offer.self)
        XCTAssertNil(old.pairProof)
        XCTAssertNil(old.files[0].key)
        XCTAssertEqual(try Array(#"{"nonce":"AAAA"}"#.utf8).decodeJSON(PairCheck.self).proofs, [])
        XCTAssertEqual(try Array(#"{"port":4000}"#.utf8).decodeJSON(Announce.self).port, 4000)
        XCTAssertEqual(try Array(#"{"items":[{"key":"k","root":"r","size":9}]}"#.utf8).decodeJSON(VerifyRequest.self).items,
                       [VerifyItem(key: "k", root: "r", size: 9)])
        XCTAssertEqual(try Array(#"{"ok":["k"]}"#.utf8).decodeJSON(VerifyReply.self).ok, ["k"])
        XCTAssertEqual(try Array(#"{"accepted":true}"#.utf8).decodeJSON(PairReply.self).accepted, true)
        XCTAssertThrowsError(try Array(#"{}"#.utf8).decodeJSON(Announce.self))
        // Absent optionals stay off the wire (Kotlin reads them as null); the new frames encode as Kotlin does.
        XCTAssertEqual(String(decoding: try Announce(port: 0).encodeBytes(), as: UTF8.self), #"{"port":0}"#)
        let acc = String(decoding: try Accept(transferId: "t").encodeBytes(), as: UTF8.self)
        XCTAssertFalse(acc.contains("pairProof"))
        XCTAssertEqual(FrameType.announce.rawValue, 0x20)
        XCTAssertEqual(FrameType.pair.rawValue, 0x21)
        XCTAssertEqual(FrameType.pairCheck.rawValue, 0x22)
        XCTAssertEqual(FrameType.backupVerify.rawValue, 0x23)
        XCTAssertEqual(FrameType.shake.rawValue, 0x24)
        XCTAssertEqual(try Array(#"{"port":4000}"#.utf8).decodeJSON(Shake.self).port, 4000)
        XCTAssertEqual(try Array(#"{}"#.utf8).decodeJSON(Shake.self).port, 0)
        XCTAssertEqual(String(decoding: try Shake(port: 0).encodeBytes(), as: UTF8.self), #"{"port":0}"#)   // Kotlin encodes defaults
    }

    // MARK: crypto

    /// Expected values computed outside CryptoKit (Python hmac/hashlib, HKDF written out by hand) from the layout
    /// the Kotlin core uses: key = HKDF-SHA256(shared, salt = binding, info = "crossdrop/v1/pair/<id>", 32);
    /// proof = HMAC-SHA256(key, utf8(label) ‖ 0x00 ‖ binding ‖ extra).
    func testPairKeyAndProofsKnownAnswer() {
        let shared = [UInt8](repeating: 1, count: 32), binding = [UInt8](repeating: 2, count: 32)
        let key = SessionCrypto.pairKey(shared: shared, binding: binding, pairId: "00112233445566778899aabbccddeeff")
        XCTAssertEqual(hex(key), "4d7ccd3a1fd662a0e6f02377524fbdfdddbae10cd5e8c254a9ae2055d2d926b5")
        let nonce = [UInt8](repeating: 3, count: 16)
        func b(_ label: String, _ extra: [UInt8]) -> String {
            Data(SessionCrypto.pairProof(key: key, label: label, binding: binding, extra: extra)).base64EncodedString()
        }
        XCTAssertEqual(b("check-i", nonce), "Oi0e6eHKHf6CUMONQQ8YTfNRWSBrQQqxY3/XfulxJjs=")
        XCTAssertEqual(b("check-r", nonce), "JufWx+DtVNCQZ/FIA1eUpbstbw82nHMc/5ASpw6ct7Q=")
        XCTAssertEqual(b("offer", Array("t-1".utf8)), "hZydxgsJMTOUgyUJ1twn9qY5JLOT/WlCYokDKlole9g=")
        XCTAssertEqual(b("accept", Array("t-1".utf8)), "X2Naw4p6by/a+2u10dzm1SYY+PIvt4lJZ7PpfxZMz0k=")
        XCTAssertEqual(hex(SessionCrypto.pairProof(key: key, label: "x", binding: binding)),
                       "0e45ca830daecb2fffef6f62d7d36463ffe21d78a92e9ae4b0e458eed9e7565b")
    }

    /// The same inputs and expected bytes as Kotlin `ControlTest` ("pairing key and proofs match the known answers"):
    /// Swift and Kotlin must agree byte for byte, or a phone and a computer could never recognise each other.
    func testPairKeyMatchesTheKotlinVectors() {
        let shared = (0..<32).map { UInt8($0) }
        let binding = (0..<32).map { UInt8(($0 * 3) & 0xff) }
        let key = SessionCrypto.pairKey(shared: shared, binding: binding, pairId: "00112233445566778899aabbccddeeff")
        XCTAssertEqual(hex(key), "2502acdf9b6803cbc7d2eb7b18d21a906d4a025fc08adf09609ad607b1d758d4")
        XCTAssertEqual(hex(SessionCrypto.pairProof(key: key, label: "check-i", binding: binding, extra: (0..<16).map { UInt8($0 + 7) })),
                       "bf47ea8b92d32d4ce616304e07a0c406cde37909528b4a716eed6d8a28a3eb97")
        XCTAssertEqual(hex(SessionCrypto.pairProof(key: key, label: "offer", binding: binding, extra: Array("tid-1".utf8))),
                       "cc23aac790b0dc5ec2c724562a90ea143b4607793eda9cce736de6ae44ecb2b5")
    }

    /// Both ends of one connection derive the same pairing key (it never crosses the wire).
    func testBothEndsDeriveTheSamePairKey() throws {
        let a = SessionIdentity(), b = SessionIdentity()
        let ka = try SessionCrypto.deriveSession(identity: a, peerPublicKey: b.publicKey, isInitiator: true)
        let kb = try SessionCrypto.deriveSession(identity: b, peerPublicKey: a.publicKey, isInitiator: false)
        let id = "ffeeddccbbaa99887766554433221100"
        XCTAssertEqual(SessionCrypto.pairKey(shared: ka.shared, binding: ka.binding, pairId: id),
                       SessionCrypto.pairKey(shared: kb.shared, binding: kb.binding, pairId: id))
        XCTAssertNotEqual(SessionCrypto.pairKey(shared: ka.shared, binding: ka.binding, pairId: id),
                          SessionCrypto.pairKey(shared: ka.shared, binding: ka.binding, pairId: "00112233445566778899aabbccddeeff"))
    }

    func testRemoteAddressNormalisesMappedIPv4() {
        var ss = sockaddr_storage()
        withUnsafeMutablePointer(to: &ss) {
            $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { p in
                p.pointee.sin6_family = sa_family_t(AF_INET6)
                withUnsafeMutableBytes(of: &p.pointee.sin6_addr) { b in
                    for i in 0..<16 { b[i] = 0 }
                    b[10] = 0xFF; b[11] = 0xFF; b[12] = 192; b[13] = 168; b[14] = 49; b[15] = 1
                }
            }
        }
        XCTAssertEqual(TCPSocket.numericHost(ss), "192.168.49.1")
        withUnsafeMutablePointer(to: &ss) {
            $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { p in
                withUnsafeMutableBytes(of: &p.pointee.sin6_addr) { b in
                    for i in 0..<16 { b[i] = 0 }
                    b[0] = 0xFE; b[1] = 0x80; b[15] = 1
                }
            }
        }
        XCTAssertEqual(TCPSocket.numericHost(ss), "fe80::1")
    }
}
