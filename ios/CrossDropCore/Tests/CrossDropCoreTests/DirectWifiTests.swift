import XCTest
@testable import CrossDropCore

/// The CrossDrop Wi-Fi: one name and password on every phone. Mirrors the Kotlin `DirectWifiTest` (same values, same
/// expectations), so an iPhone and an Android phone agree on what to join and where to announce.
final class DirectWifiTests: XCTestCase {
    func testTheValuesMatchKotlin() {
        XCTAssertEqual(DirectWifi.ssid, "DIRECT-CD-CrossDrop")
        XCTAssertEqual(DirectWifi.password, "crossdrop123")
        XCTAssertEqual(DirectWifi.host, "192.168.49.1")
        XCTAssertEqual(DirectWifi.port, 47474)
        XCTAssertTrue((9...32).contains(DirectWifi.ssid.utf8.count))
        XCTAssertTrue((8...63).contains(DirectWifi.password.count))
    }

    func testAOneTapJoinKnowsWhereThePhoneListens() {
        let code = DirectWifi.joinCode()
        XCTAssertEqual(code.ssid, DirectWifi.ssid)
        XCTAssertEqual(code.password, DirectWifi.password)
        XCTAssertEqual(code.security, "WPA")
        XCTAssertEqual(code.host, "192.168.49.1")
        XCTAssertEqual(code.port, DirectWifi.port)
        XCTAssertEqual(DirectWifi.joinCode(host: "192.168.43.1").host, "192.168.43.1")
    }

    func testTheHostIsTheFirstAddressOfTheJoinersSubnet() {
        XCTAssertEqual(DirectWifi.hostFor("192.168.49.57"), "192.168.49.1")
        XCTAssertEqual(DirectWifi.hostFor("10.20.30.4"), "10.20.30.1")
        XCTAssertEqual(DirectWifi.hostFor("172.20.1.9"), "172.20.1.1")
        XCTAssertEqual(DirectWifi.hostFor(nil), DirectWifi.host)
        XCTAssertEqual(DirectWifi.hostFor("8.8.8.8"), DirectWifi.host)
        XCTAssertEqual(DirectWifi.hostFor("192.168.1"), DirectWifi.host)
        XCTAssertEqual(DirectWifi.hostFor("192.168.1.300"), DirectWifi.host)
        XCTAssertEqual(DirectWifi.hostFor("192.168.5.1"), DirectWifi.host)
    }

    func testOnlyTheCrossDropWifiCounts() {
        XCTAssertTrue(DirectWifi.isCrossDropWifi("DIRECT-CD-CrossDrop"))
        XCTAssertTrue(DirectWifi.isCrossDropWifi("\"DIRECT-CD-CrossDrop\""))
        XCTAssertFalse(DirectWifi.isCrossDropWifi("DIRECT-CD-CrossDrop2"))
        XCTAssertFalse(DirectWifi.isCrossDropWifi(nil))
        XCTAssertTrue(DirectWifi.onGroupSubnet("192.168.49.23"))
        XCTAssertFalse(DirectWifi.onGroupSubnet("192.168.49.1"))
        XCTAssertFalse(DirectWifi.onGroupSubnet("192.168.1.23"))
    }

    /// The Android phone's code for the CrossDrop Wi-Fi reads the same here: the network plus where it listens.
    func testItsCodeReadsBackWithTheAddress() throws {
        let qr = WifiQr.payload(ssid: DirectWifi.ssid, password: DirectWifi.password, security: "WPA",
                                host: "192.168.49.1", port: 40123, instanceId: "0123456789abcdef")
        let j = try XCTUnwrap(WifiQr.parse(qr))
        XCTAssertTrue(DirectWifi.isCrossDropWifi(j.ssid))
        XCTAssertEqual(j.password, DirectWifi.password)
        XCTAssertEqual(j.host, "192.168.49.1")
        XCTAssertEqual(j.port, 40123)
    }
}
