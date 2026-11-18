import XCTest
@testable import CrossDropCore

final class LocalAddressTests: XCTestCase {
    private func ip(_ a: UInt32, _ b: UInt32, _ c: UInt32, _ d: UInt32) -> UInt32 { a << 24 | b << 16 | c << 8 | d }

    func testPrivateRangesCount() {
        XCTAssertTrue(LocalAddress.isShareableIPv4(ip(192, 168, 1, 5)))
        XCTAssertTrue(LocalAddress.isShareableIPv4(ip(10, 0, 0, 7)))
        XCTAssertTrue(LocalAddress.isShareableIPv4(ip(172, 20, 10, 1)))     // an iPhone's own Personal Hotspot
    }

    /// Some universities and ISPs give devices public addresses; that is still a network others can be on.
    func testPublicIPv4Counts() {
        XCTAssertTrue(LocalAddress.isShareableIPv4(ip(128, 32, 4, 9)))
        XCTAssertTrue(LocalAddress.isShareableIPv4(ip(100, 64, 1, 2)))      // carrier-grade NAT range
    }

    func testUnusableIPv4DoesNotCount() {
        XCTAssertFalse(LocalAddress.isShareableIPv4(0))
        XCTAssertFalse(LocalAddress.isShareableIPv4(ip(127, 0, 0, 1)))
        XCTAssertFalse(LocalAddress.isShareableIPv4(ip(169, 254, 3, 4)))    // DHCP never answered
        XCTAssertFalse(LocalAddress.isShareableIPv4(ip(224, 0, 0, 251)))
    }

    func testIPv6OnlyNetworksCount() {
        var global = [UInt8](repeating: 0, count: 16); global[0] = 0x24; global[1] = 0x01; global[15] = 5     // 2401::5
        var uniqueLocal = [UInt8](repeating: 0, count: 16); uniqueLocal[0] = 0xFD; uniqueLocal[15] = 9
        XCTAssertTrue(LocalAddress.isShareableIPv6(global))
        XCTAssertTrue(LocalAddress.isShareableIPv6(uniqueLocal))
    }

    func testUnusableIPv6DoesNotCount() {
        var linkLocal = [UInt8](repeating: 0, count: 16); linkLocal[0] = 0xFE; linkLocal[1] = 0x80; linkLocal[15] = 1
        var loopback = [UInt8](repeating: 0, count: 16); loopback[15] = 1
        var multicast = [UInt8](repeating: 0, count: 16); multicast[0] = 0xFF; multicast[1] = 0x02
        XCTAssertFalse(LocalAddress.isShareableIPv6(linkLocal))
        XCTAssertFalse(LocalAddress.isShareableIPv6(loopback))
        XCTAssertFalse(LocalAddress.isShareableIPv6([UInt8](repeating: 0, count: 16)))
        XCTAssertFalse(LocalAddress.isShareableIPv6(multicast))
        XCTAssertFalse(LocalAddress.isShareableIPv6([1, 2, 3]))
    }
}
