import XCTest
@testable import CrossDropCore

/// Shake to connect: matching our shake with SHAKE messages, and spotting a shake in accelerometer samples. Same rules
/// and numbers as Kotlin `ShakeMatcher` / `ShakeGesture`.
final class ShakeTests: XCTestCase {
    private final class Clock: @unchecked Sendable {
        var now: Int64 = 1_000_000
        func at(_ ms: Int64) { now = 1_000_000 + ms }
    }

    private func matcher(_ clock: Clock) -> ShakeMatcher { ShakeMatcher(clock: { clock.now }) }

    // MARK: matcher

    func testOurShakeThenTheirMessageMatches() {
        let c = Clock(), m = matcher(c)
        XCTAssertNil(m.local())
        XCTAssertEqual(m.waitingSince(), c.now)
        c.at(1_200)
        XCTAssertEqual(m.remote(instanceId: "a1a1a1a1", name: "Pixel 9"),
                       ShakeMatcher.Match(instanceId: "a1a1a1a1", name: "Pixel 9", atMs: c.now))
        XCTAssertNil(m.waitingSince(), "a matched shake no longer waits")
    }

    func testTheirMessageThenOurShakeMatches() {
        let c = Clock(), m = matcher(c)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "Pixel 9"))
        XCTAssertNil(m.waitingSince(), "their message alone doesn't make us wait")
        c.at(2_000)
        XCTAssertEqual(m.local()?.instanceId, "a1a1a1a1")
        XCTAssertNil(m.waitingSince())
    }

    func testTheWindowIsTwoAndAHalfSecondsEitherWay() {
        XCTAssertEqual(ShakeMatcher.windowMs, 2_500)
        var c = Clock(), m = matcher(c)
        XCTAssertNil(m.local())
        c.at(2_500)
        XCTAssertNotNil(m.remote(instanceId: "a1a1a1a1", name: "A"), "exactly at the edge still counts")

        c = Clock(); m = matcher(c)
        XCTAssertNil(m.local())
        c.at(2_501)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "A"))
        XCTAssertNil(m.waitingSince(), "our old shake has expired")

        c = Clock(); m = matcher(c)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "A"))
        c.at(2_501)
        XCTAssertNil(m.local(), "their old message has expired")
        XCTAssertNotNil(m.waitingSince(), "so this shake waits for a new one")
    }

    func testEachShakeAndEachMessageIsUsedOnce() {
        let c = Clock(), m = matcher(c)
        // One shake of ours opens one device: the second message finds no shake to pair with.
        XCTAssertNil(m.local())
        c.at(100)
        XCTAssertEqual(m.remote(instanceId: "a1a1a1a1", name: "A")?.instanceId, "a1a1a1a1")
        c.at(200)
        XCTAssertNil(m.remote(instanceId: "b2b2b2b2", name: "B"))
        // That message waits for our next shake...
        c.at(300)
        XCTAssertEqual(m.local()?.instanceId, "b2b2b2b2")
        // ...and is gone once used.
        c.at(400)
        XCTAssertNil(m.local())
    }

    func testTheClosestMessageWins() {
        let c = Clock(), m = matcher(c)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "A"))
        c.at(1_000)
        XCTAssertNil(m.remote(instanceId: "b2b2b2b2", name: "B"))
        c.at(1_500)
        XCTAssertEqual(m.local()?.instanceId, "b2b2b2b2")
        c.at(1_600)
        XCTAssertEqual(m.local()?.instanceId, "a1a1a1a1", "the other one is still there for the next shake")
    }

    func testARepeatedMessageCountsFromItsLatestArrival() {
        let c = Clock(), m = matcher(c)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "A"))
        c.at(2_000)
        XCTAssertNil(m.remote(instanceId: "a1a1a1a1", name: "A again"))
        c.at(4_000)
        XCTAssertEqual(m.local(), ShakeMatcher.Match(instanceId: "a1a1a1a1", name: "A again", atMs: c.now))
        XCTAssertNil(m.local(), "one entry per device, not one per message")
    }

    func testRemembersAtMost32Devices() {
        let c = Clock(), m = matcher(c)
        for i in 0..<40 { XCTAssertNil(m.remote(instanceId: String(format: "%08x", i), name: "D\(i)")) }
        var matched: [String] = []
        while let hit = m.local() { matched.append(hit.instanceId) }
        XCTAssertEqual(matched.count, 32)
        XCTAssertFalse(matched.contains(String(format: "%08x", 0)), "the oldest were dropped")
        XCTAssertTrue(matched.contains(String(format: "%08x", 39)))
    }

    // MARK: gesture

    private let g = 9.81

    /// Feeds `seconds` of 50 Hz samples from `start`; returns the times (ms from `start`) a shake was seen.
    @discardableResult
    private func feed(_ gesture: ShakeGesture, from start: Int64 = 0, seconds: Double,
                      _ sample: (Int) -> (Double, Double, Double)) -> [Int64] {
        var hits: [Int64] = []
        let n = Int(seconds * 50)
        for i in 0..<n {
            let t = start + Int64(i) * 20_000_000
            let (x, y, z) = sample(i)
            if gesture.sample(timeNs: t, x: x, y: y, z: z) { hits.append((t - start) / 1_000_000) }
        }
        return hits
    }

    /// A hand shake: hard swings left and right with gravity underneath.
    private func shaking(_ i: Int) -> (Double, Double, Double) { (i % 2 == 0 ? 22 : -22, 3, g) }

    func testABurstShakesOnce() {
        let hits = feed(ShakeGesture(), seconds: 1.2, shaking)
        XCTAssertEqual(hits.count, 1, "\(hits)")
        XCTAssertGreaterThanOrEqual(hits[0], 250, "needs at least half the span")
        XCTAssertLessThanOrEqual(hits[0], 500)
    }

    func testGravityAloneNeverShakes() {
        let gesture = ShakeGesture()
        XCTAssertTrue(feed(gesture, seconds: 5) { _ in (0, 0, self.g) }.isEmpty)                 // flat on a table
        XCTAssertTrue(feed(gesture, from: 5_000_000_000, seconds: 5) { _ in (5.6, 5.6, 5.6) }.isEmpty)   // tilted, still ≈ 9.7
        XCTAssertTrue(feed(gesture, from: 10_000_000_000, seconds: 5) { i in (0, 0, i % 2 == 0 ? 12 : 8) }.isEmpty)   // walking
    }

    func testASingleBumpDoesNotShake() {
        // A knock on the table: three hard samples (60 ms) in a second of stillness.
        XCTAssertTrue(feed(ShakeGesture(), seconds: 2) { i in (25...27).contains(i) ? (30, 0, self.g) : (0, 0, self.g) }.isEmpty)
        // A short jolt, a quarter of the span: still not a shake.
        XCTAssertTrue(feed(ShakeGesture(), seconds: 2) { i in (25..<31).contains(i) ? (30, 0, self.g) : (0, 0, self.g) }.isEmpty)
    }

    func testTheCooldownHolds() {
        // Shaking on and on: one shake per 1.5 s, not one per half second.
        let hits = feed(ShakeGesture(), seconds: 3.2, shaking)
        XCTAssertEqual(hits.count, 2, "\(hits)")
        XCTAssertGreaterThanOrEqual(hits[1] - hits[0], 1_500)
        // A second burst right after the first one is ignored; one after the cooldown counts.
        let gesture = ShakeGesture()
        XCTAssertEqual(feed(gesture, seconds: 0.6, shaking).count, 1)
        XCTAssertTrue(feed(gesture, from: 700_000_000, seconds: 0.6, shaking).isEmpty)
        XCTAssertEqual(feed(gesture, from: 2_200_000_000, seconds: 0.6, shaking).count, 1)
    }
}
