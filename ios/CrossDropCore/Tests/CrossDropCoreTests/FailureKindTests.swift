import XCTest
@testable import CrossDropCore

final class FailureKindTests: XCTestCase {
    func testNothingAnsweringBeforeTheChannelIsUpMeansUnreachable() {
        XCTAssertEqual(FailureKind.classify(ProtocolError("connect to 10.0.0.5:4000 failed: Connection refused"), connected: false, sending: true), .unreachable)
        XCTAssertEqual(FailureKind.classify(ProtocolError("cannot resolve nope.local: nodename nor servname provided"), connected: false, sending: true), .unreachable)
        XCTAssertEqual(FailureKind.classify(ProtocolError("timed out waiting for peer"), connected: false, sending: true), .unreachable)
        XCTAssertEqual(FailureKind.classify(EOFError(), connected: false, sending: true), .unreachable)
    }

    func testASocketDyingAfterTheChannelWasUpMeansTheConnectionWasLost() {
        XCTAssertEqual(FailureKind.classify(ProtocolError("read failed: Connection reset by peer"), connected: true, sending: true), .connectionLost)
        XCTAssertEqual(FailureKind.classify(ProtocolError("write failed: Broken pipe"), connected: true, sending: false), .connectionLost)
        XCTAssertEqual(FailureKind.classify(ProtocolError("timed out waiting for peer"), connected: true, sending: true), .connectionLost)
        XCTAssertEqual(FailureKind.classify(ProtocolError("connection closed"), connected: true, sending: true), .connectionLost)
        XCTAssertEqual(FailureKind.classify(EOFError(), connected: true, sending: false), .connectionLost)
    }

    func testFullDiskUnreadableSourceForeignProtocolAndIntegrity() {
        XCTAssertEqual(FailureKind.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError), connected: true, sending: false), .diskFull)
        XCTAssertEqual(FailureKind.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC)), connected: true, sending: false), .diskFull)
        XCTAssertEqual(FailureKind.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoSuchFileError), connected: true, sending: true), .sourceUnreadable)
        // on the receiving side a missing file is not "couldn't read what you picked"
        XCTAssertEqual(FailureKind.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoSuchFileError), connected: true, sending: false), .other)
        XCTAssertEqual(FailureKind.classify(ProtocolError("expected HELLO_ACK, got error"), connected: false, sending: true), .incompatible)
        XCTAssertEqual(FailureKind.classify(ProtocolError("chunk 3 failed hash check"), connected: true, sending: false), .integrity)
        XCTAssertEqual(FailureKind.classify(ProtocolError("something odd"), connected: true, sending: true), .other)
        // after the handshake a protocol error is not a version mismatch, and a changed source is the sender's file
        XCTAssertEqual(FailureKind.classify(ProtocolError("expected CHUNK_REQ, got error"), connected: true, sending: true), .other)
        XCTAssertEqual(FailureKind.classify(ProtocolError("a.pdf changed mid-transfer"), connected: true, sending: true), .sourceUnreadable)
        XCTAssertEqual(FailureKind.classify(ProtocolError("a.pdf changed mid-transfer"), connected: true, sending: false), .other)
        // a file name the other device chose can contain any of the words: only how a message ends counts
        XCTAssertEqual(FailureKind.classify(ProtocolError("no space left on device.txt changed mid-transfer"), connected: true, sending: true), .sourceUnreadable)
        XCTAssertEqual(FailureKind.classify(ProtocolError("integrity report.pdf changed mid-transfer"), connected: true, sending: true), .sourceUnreadable)
        XCTAssertEqual(FailureKind.classify(ProtocolError("disk full.txt overruns its offered size"), connected: true, sending: false), .other)
    }

    func testHelloCarriesThePlatformAndOlderPeersWithoutItStillDecode() throws {
        let hello = Hello(version: 3, deviceName: "iPhone", publicKeyB64: "k", instanceId: "id", os: "ios")
        XCTAssertEqual(try JSONDecoder().decode(Hello.self, from: JSONEncoder().encode(hello)).os, "ios")
        let old = #"{"version":3,"deviceName":"Pixel","publicKeyB64":"k","instanceId":"id"}"#.data(using: .utf8)!
        XCTAssertNil(try JSONDecoder().decode(Hello.self, from: old).os)
        let oldAck = #"{"version":3,"deviceName":"Pixel","publicKeyB64":"k","instanceId":"id"}"#.data(using: .utf8)!
        XCTAssertNil(try JSONDecoder().decode(HelloAck.self, from: oldAck).os)
    }
}
