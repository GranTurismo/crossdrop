import XCTest
@testable import CrossDropCore

final class ReviewPolicyTests: XCTestCase {
    private final class MapStore: ReviewStore {
        var m: [String: Int] = [:]
        func getInt(_ key: String) -> Int { m[key] ?? 0 }
        func setInt(_ value: Int, forKey key: String) { m[key] = value }
    }
    private let day = 24 * 60 * 60.0
    private var clock = Date(timeIntervalSince1970: 1_800_000_000)

    private func make() -> (ReviewPolicy, MapStore) {
        let s = MapStore()
        return (ReviewPolicy(store: s, now: { [unowned self] in self.clock }), s)
    }

    func testWaitsForARunOfSuccessfulTransfers() {
        let (p, _) = make()
        XCTAssertFalse(p.shouldAsk())
        p.onTransfer(success: true); p.onTransfer(success: true)
        XCTAssertFalse(p.shouldAsk())
        p.onTransfer(success: true)
        XCTAssertTrue(p.shouldAsk())
    }

    func testAFailureStartsTheRunOver() {
        let (p, _) = make()
        for _ in 0..<5 { p.onTransfer(success: true) }
        p.onTransfer(success: false)
        XCTAssertFalse(p.shouldAsk())
        p.onTransfer(success: true); p.onTransfer(success: true)
        XCTAssertFalse(p.shouldAsk())
        p.onTransfer(success: true)
        XCTAssertTrue(p.shouldAsk())
    }

    func testAskingResetsTheRunAndSpacesTheNextAskOut() {
        let (p, _) = make()
        for _ in 0..<3 { p.onTransfer(success: true) }
        p.asked()
        for _ in 0..<10 { p.onTransfer(success: true) }
        XCTAssertFalse(p.shouldAsk(), "too soon")
        clock = clock.addingTimeInterval(119 * day); XCTAssertFalse(p.shouldAsk())
        clock = clock.addingTimeInterval(2 * day); XCTAssertTrue(p.shouldAsk())
    }

    func testAsksAtMostThreeTimesEver() {
        let (p, s) = make()
        for _ in 0..<3 {
            for _ in 0..<3 { p.onTransfer(success: true) }
            XCTAssertTrue(p.shouldAsk()); p.asked()
            clock = clock.addingTimeInterval(200 * day)
        }
        for _ in 0..<10 { p.onTransfer(success: true) }
        XCTAssertFalse(p.shouldAsk())
        XCTAssertEqual(s.m["review_asks"], 3)
    }
}
