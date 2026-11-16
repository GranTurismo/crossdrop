import XCTest
@testable import CrossDropCore

/// Offer kinds, inline text, switch batches and trust grants — mirrors the Kotlin `OfferExtrasTest`.
final class OfferExtrasTests: XCTestCase {
    final class NullSink: OutputSink {
        func write(_ bytes: [UInt8]) throws {}
        func close() {}
    }

    private func tmpFile(_ bytes: [UInt8]) -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("cd-\(UUID().uuidString)")
        try! Data(bytes).write(to: u)
        return u
    }

    /// One loopback send → receive; returns (sender events, the request the receiver saw).
    private func roundTrip(meta: FileMeta, bytes: [UInt8], options: SendOptions,
                           decide: @escaping (InboundRequest) -> ReceiveDecision) -> ([TransferEvent], InboundRequest?) {
        let sEng = TransferEngine(identity: SessionIdentity(), deviceName: { "sender" }, instanceId: "aaaa")
        let rEng = TransferEngine(identity: SessionIdentity(), deviceName: { "receiver" }, instanceId: "bbbb")
        let listener = TCPListener()
        var seen: InboundRequest?
        var sEvents: [TransferEvent] = []
        let lock = NSLock()
        let rDone = DispatchSemaphore(value: 0), sDone = DispatchSemaphore(value: 0)
        try! listener.start { sock, _ in
            Thread.detachNewThread {
                rEng.receive(socket: sock, decideRequest: { req in
                    lock.lock(); seen = req; lock.unlock()
                    return decide(req)
                }, emit: { e in
                    switch e { case .completed, .failed, .cancelled, .declinedByPeer: rDone.signal(); default: break }
                })
            }
        }
        let url = tmpFile(bytes)
        Thread.detachNewThread {
            sEng.send(host: "127.0.0.1", port: listener.port, files: [OutgoingFile(meta: meta, url: url)], options: options) { e in
                lock.lock(); sEvents.append(e); lock.unlock()
                switch e { case .completed, .failed, .cancelled, .declinedByPeer: sDone.signal(); default: break }
            }
        }
        XCTAssertEqual(sDone.wait(timeout: .now() + 30), .success)
        XCTAssertEqual(rDone.wait(timeout: .now() + 30), .success)
        listener.stop()
        return (sEvents, seen)
    }

    private func accept(_ grant: TrustGrant? = nil) -> ReceiveDecision {
        .accept(sinkFor: { _ in NullSink() }, delta: nil, grant: grant)
    }

    func testTextOfferCarriesText() {
        let text = "https://example.com/a?b=c"
        let (ev, req) = roundTrip(meta: FileMeta(name: "Link.txt", size: Int64(text.utf8.count)), bytes: Array(text.utf8),
                                  options: SendOptions(kind: OfferKind.text, text: text)) { _ in self.accept() }
        XCTAssertEqual(req?.offer.kind, OfferKind.text)
        XCTAssertEqual(req?.offer.text, text)
        if case .completed = ev.last {} else { XCTFail("not completed: \(ev)") }
    }

    func testCategoryTimeAndBatch() {
        let batch = BatchInfo(index: 2, count: 5, photos: 10, videos: 3, contacts: 40, totalBytes: 1234)
        let (_, req) = roundTrip(meta: FileMeta(name: "IMG_1.JPG", size: 4, cat: ItemCategory.photo, mtime: 1_700_000_000_000),
                                 bytes: [1, 2, 3, 4], options: SendOptions(kind: OfferKind.switchPhones, batch: batch)) { _ in self.accept() }
        XCTAssertEqual(req?.offer.files.first?.cat, "photo")
        XCTAssertEqual(req?.offer.files.first?.mtime, 1_700_000_000_000)
        XCTAssertEqual(req?.offer.batch, batch)
    }

    func testGrantThenProof() {
        let token = [UInt8](repeating: 9, count: 32)
        let grant = TrustGrant(id: "g1", token: Data(token).base64EncodedString(), kind: OfferKind.camera, ttlMs: 60_000)
        let (ev, _) = roundTrip(meta: FileMeta(name: "a.jpg", size: 1), bytes: [1],
                                options: SendOptions(kind: OfferKind.camera)) { _ in self.accept(grant) }
        let got = ev.compactMap { e -> TrustGrant? in if case .trustGranted(let g) = e { return g }; return nil }.first
        XCTAssertEqual(got, grant)

        var proved = false, wrong = true
        let cred = TrustCredential(got!)!
        _ = roundTrip(meta: FileMeta(name: "b.jpg", size: 1), bytes: [1],
                      options: SendOptions(kind: OfferKind.camera, trust: cred)) { req in
            proved = req.provesTrust(token: token)
            wrong = req.provesTrust(token: [UInt8](repeating: 8, count: 32))
            return self.accept()
        }
        XCTAssertTrue(proved)
        XCTAssertFalse(wrong)
    }

    func testTrustStoreAcceptsEachTransferOnce() {
        let store = TrustStore()
        let grant = store.grant(kind: OfferKind.camera, peerName: "phone", ttl: 60)
        var first: TrustStore.Granted?, replay: TrustStore.Granted?
        _ = roundTrip(meta: FileMeta(name: "a.jpg", size: 1), bytes: [1],
                      options: SendOptions(kind: OfferKind.camera, trust: TrustCredential(grant))) { req in
            first = store.match(req)
            replay = store.match(req)
            return self.accept()
        }
        XCTAssertNotNil(first)
        XCTAssertNil(replay)
        store.revoke(kind: OfferKind.camera)
    }

    /// The Kotlin side computes the same proof for the same inputs (HMAC-SHA256 over binding ‖ id).
    func testProofKnownAnswer() {
        let p = SessionCrypto.trustProof(token: [UInt8](repeating: 1, count: 32), binding: [UInt8](repeating: 2, count: 32), transferId: "t")
        XCTAssertEqual(Data(p).base64EncodedString(), OfferExtrasTests.kotlinProof)
    }
    // Filled from the Kotlin implementation (see core OfferExtrasTest `proof known answer`).
    static let kotlinProof = "gPKpMCI2GQVihgj2WHUXG16IFWN9tU/jpjsRkiYSk2U="
}
