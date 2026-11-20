import XCTest
@testable import CrossDropCore

/// The private link's QR code: still a plain Wi-Fi code for phone cameras, plus where CrossDrop connects.
/// Mirrors the Kotlin `JoinCodeTest` (same inputs, same expectations).
final class WifiQrTests: XCTestCase {
    private let code = WifiQr.payload(ssid: "AndroidShare_4821", password: "k9Qm-x2Lp", security: "WPA",
                                      host: "192.168.49.1", port: 40123, instanceId: "0123456789abcdef")

    func testTheAddressRidesInItsOwnFieldBeforeTheEnd() {
        XCTAssertEqual(code, "WIFI:T:WPA;S:AndroidShare_4821;P:k9Qm-x2Lp;CD:192.168.49.1/40123/0123456789abcdef;;")
    }

    func testCrossDropReadsEverythingBack() throws {
        let j = try XCTUnwrap(WifiQr.parse(code))
        XCTAssertEqual(j.ssid, "AndroidShare_4821")
        XCTAssertEqual(j.password, "k9Qm-x2Lp")
        XCTAssertEqual(j.security, "WPA")
        XCTAssertEqual(j.host, "192.168.49.1")
        XCTAssertEqual(j.port, 40123)
        XCTAssertEqual(j.instanceId, "0123456789abcdef")
        XCTAssertFalse(j.hidden)
    }

    func testRouterCodesParseWithoutAnAddress() throws {
        let j = try XCTUnwrap(WifiQr.parse("WIFI:S:Home Net;T:WPA;P:secret 123;H:true;;"))
        XCTAssertEqual(j.ssid, "Home Net")
        XCTAssertEqual(j.password, "secret 123")
        XCTAssertTrue(j.hidden)
        XCTAssertNil(j.host)
        XCTAssertEqual(j.port, 0)
        let open = try XCTUnwrap(WifiQr.parse("WIFI:S:Cafe;T:nopass;;"))
        XCTAssertEqual(open.security, "nopass")
        XCTAssertNil(open.password)
        XCTAssertEqual(WifiQr.parse("wifi:S:x;P:abcdefgh;;")?.security, "WPA")     // no type, but a password
        XCTAssertEqual(WifiQr.parse("WIFI:S:x;;")?.security, "nopass")              // no type, no password
        XCTAssertNil(WifiQr.parse("WIFI:S:x;T:nopass;P:ignored;;")?.password)       // an open network has none
    }

    func testSecurityNames() {
        XCTAssertEqual(WifiQr.parse("WIFI:T:WPA2;S:a;P:pppppppp;;")?.security, "WPA")
        XCTAssertEqual(WifiQr.parse("WIFI:T:wpa/wpa2;S:a;P:pppppppp;;")?.security, "WPA")
        XCTAssertEqual(WifiQr.parse("WIFI:T:SAE;S:a;P:pppppppp;;")?.security, "SAE")
        XCTAssertEqual(WifiQr.parse("WIFI:T:WPA3;S:a;P:pppppppp;;")?.security, "SAE")
        XCTAssertEqual(WifiQr.parse("WIFI:T:WEP;S:a;P:12345;;")?.security, "WEP")
        XCTAssertEqual(WifiQr.parse("WIFI:T:WPA2-EAP;S:a;P:pppppppp;;")?.security, "WPA")   // unknown: try WPA
        XCTAssertEqual(WifiQr.parse("WIFI:t:SAE;s:Lower;p:pppppppp;h:TRUE;;")?.ssid, "Lower")  // keys are case-insensitive
        XCTAssertEqual(WifiQr.parse("WIFI:t:SAE;s:Lower;p:pppppppp;h:TRUE;;")?.hidden, true)
        XCTAssertEqual(WifiQr.parse("WIFI:S:first;S:second;;")?.ssid, "first")                 // the first of a key wins
        XCTAssertEqual(WifiQr.parse("  \nWIFI:S:padded;;\n")?.ssid, "padded")
    }

    func testEscapesAndQuotedHexValuesComeBackAsTyped() throws {
        let tricky = WifiQr.payload(ssid: #"a;b,c:d\e"f"#, password: "p;w")
        let j = try XCTUnwrap(WifiQr.parse(tricky))
        XCTAssertEqual(j.ssid, #"a;b,c:d\e"f"#)
        XCTAssertEqual(j.password, "p;w")
        XCTAssertEqual(WifiQr.payload(ssid: "Home", password: "deadbeef12"), #"WIFI:T:WPA;S:Home;P:"deadbeef12";;"#)
        XCTAssertEqual(WifiQr.parse(WifiQr.payload(ssid: "Home", password: "deadbeef12"))?.password, "deadbeef12")
        XCTAssertEqual(WifiQr.parse(WifiQr.payload(ssid: "CAFE", password: "xyz12345"))?.ssid, "CAFE")
        // Router-made quoted values; a quote inside a value is kept.
        let r = try XCTUnwrap(WifiQr.parse(#"WIFI:S:"abcdef12";T:WPA;P:"0123456789";;"#))
        XCTAssertEqual(r.ssid, "abcdef12")
        XCTAssertEqual(r.password, "0123456789")
        XCTAssertEqual(WifiQr.parse(#"WIFI:S:say "hi";;"#)?.ssid, #"say "hi""#)
        XCTAssertEqual(WifiQr.parse(#"WIFI:S:\"x\";;"#)?.ssid, #""x""#)            // escaped quotes are text
        XCTAssertEqual(WifiQr.parse("WIFI:S:ünï;P:pässwört;;")?.password, "pässwört")
    }

    func testAnythingThatIsntAWifiCodeIsRefusedABrokenAddressIsIgnored() throws {
        XCTAssertNil(WifiQr.parse("http://192.168.1.4:8080/abc/"))
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;P:x;;"))              // no network name
        XCTAssertNil(WifiQr.parse("WIFI:S:;;"))
        XCTAssertNil(WifiQr.parse(""))
        XCTAssertNil(WifiQr.parse("WIFI"))
        let bad = try XCTUnwrap(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:999.1.1.1/80/abcdef12;;"))
        XCTAssertNil(bad.host)
        XCTAssertEqual(bad.ssid, "n")
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/0;;")?.host)
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/65536;;")?.host)
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1;;")?.host)
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:010.0.0.1/80;;")?.host)
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:١.٢.٣.٤/80;;")?.host)    // ASCII digits only
        // An address without an id, or with a broken one, still connects; only the id is dropped.
        let noId = try XCTUnwrap(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/4000;;"))
        XCTAssertEqual(noId.host, "10.0.0.1"); XCTAssertEqual(noId.port, 4000); XCTAssertNil(noId.instanceId)
        XCTAssertNil(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/4000/xyz;;")?.instanceId)
        XCTAssertEqual(WifiQr.parse("WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/4000/xyz;;")?.host, "10.0.0.1")
        // Junk is never written into a code either.
        XCTAssertFalse(WifiQr.payload(ssid: "n", password: "pppppppp", host: "evil;host", port: 80).contains("CD:"))
        XCTAssertFalse(WifiQr.payload(ssid: "n", password: "pppppppp", host: "10.0.0.1", port: 0).contains("CD:"))
        XCTAssertEqual(WifiQr.payload(ssid: "n", password: "pppppppp", host: "10.0.0.1", port: 80, instanceId: "zz"),
                       "WIFI:T:WPA;S:n;P:pppppppp;CD:10.0.0.1/80;;")
        XCTAssertEqual(WifiQr.payload(ssid: "Open", password: nil), "WIFI:T:nopass;S:Open;;")
        XCTAssertEqual(WifiQr.payload(ssid: "Open", password: "x", security: "nopass"), "WIFI:T:nopass;S:Open;;")
    }

    func testRoundTripsAcrossSecurityAndAddress() throws {
        for sec in ["WPA", "SAE"] {
            let p = WifiQr.payload(ssid: "Link 7", password: "a:b;c", security: sec, host: "10.1.2.3", port: 1, instanceId: "ABCDEF01")
            let j = try XCTUnwrap(WifiQr.parse(p))
            XCTAssertEqual(j, JoinCode(ssid: "Link 7", password: "a:b;c", security: sec, host: "10.1.2.3", port: 1, instanceId: "ABCDEF01"))
        }
    }
}
