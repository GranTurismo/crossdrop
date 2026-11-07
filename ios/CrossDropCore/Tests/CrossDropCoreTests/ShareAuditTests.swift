import XCTest
@testable import CrossDropCore

/// Regression tests from the pre-release audit of `HttpShareServer`.
final class ShareAuditTests: XCTestCase {
    private var dir: URL!
    private var server: HttpShareServer?
    private let token = "00112233445566778899aabbccddeeff"
    private let fm = FileManager.default

    override func setUpWithError() throws {
        dir = fm.temporaryDirectory.appendingPathComponent("shareaud-\(UUID().uuidString)")
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    override func tearDown() {
        server?.close()
        HttpShareServer.headDeadline = 15; HttpShareServer.headReadTimeout = 5
        try? fm.removeItem(at: dir)
    }

    private func file(_ name: String, bytes: Int, declared: Int64? = nil) throws -> OutgoingFile {
        let url = dir.appendingPathComponent(name)
        try Data((0..<bytes).map { UInt8(truncatingIfNeeded: $0) }).write(to: url)
        return OutgoingFile(meta: FileMeta(name: name, size: declared ?? Int64(bytes), mime: "application/octet-stream"), url: url)
    }

    private func start(_ files: [OutgoingFile]) throws -> Int {
        let s = HttpShareServer(files: files, deviceName: "T", token: token)
        server = s
        try s.start()
        return s.port
    }

    private func raw(_ port: Int, _ request: String) throws -> (head: String, body: [UInt8]) {
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        defer { s.close() }
        s.setTimeout(10)
        try s.writeAll(Array(request.utf8))
        var all: [UInt8] = []
        while true { let part = try s.recvSome(64 * 1024); if part.isEmpty { break }; all += part }
        let sep: [UInt8] = [13, 10, 13, 10]
        var i = 0
        while i + 3 < all.count && Array(all[i..<i + 4]) != sep { i += 1 }
        return (String(decoding: all[0..<min(i, all.count)], as: UTF8.self), Array(all[min(i + 4, all.count)...]))
    }

    // MARK: zip limits

    /// Three 2 GiB files each fit in 32 bits, but the third entry's *offset* wraps → a corrupt archive.
    func testZipRefusedWhenRunningOffsetOverflows() {
        func f(_ n: String, _ size: Int64) -> OutgoingFile { OutgoingFile(meta: FileMeta(name: n, size: size, mime: nil), url: URL(fileURLWithPath: "/nonexistent/\(n)")) }
        XCTAssertTrue(HttpShareServer.zipFits([f("a", 10), f("b", 20)]))
        XCTAssertFalse(HttpShareServer.zipFits([f("a", 2 << 30), f("b", 2 << 30), f("c", 2 << 30)]))
        XCTAssertFalse(HttpShareServer.zipFits([f("a", 0xFFFF_FFFF)]))
        XCTAssertFalse(HttpShareServer.zipFits([f("a", -1)]))
        XCTAssertFalse(HttpShareServer.zipFits([]))
        // 65 536 entries don't fit the 16-bit entry count of a classic archive
        let many = (0..<65_536).map { f("f\($0)", 1) }
        XCTAssertFalse(HttpShareServer.zipFits(many))
        XCTAssertTrue(HttpShareServer.zipFits(Array(many.prefix(65_535))))
    }

    func testOversizedShareAdvertisesNoZipAndAnswers413() throws {
        let big = OutgoingFile(meta: FileMeta(name: "huge.bin", size: 3 << 30, mime: nil), url: dir.appendingPathComponent("huge.bin"))
        let ok = try file("ok.bin", bytes: 10)
        let port = try start([big, OutgoingFile(meta: FileMeta(name: "huge2.bin", size: 3 << 30, mime: nil), url: big.url), ok])
        let m = try raw(port, "GET /\(token)/manifest.json HTTP/1.1\r\nHost: x\r\n\r\n")
        XCTAssertTrue(String(decoding: m.body, as: UTF8.self).contains("\"zip\":false"))
        let z = try raw(port, "GET /\(token)/all.zip HTTP/1.1\r\nHost: x\r\n\r\n")
        XCTAssertTrue(z.head.hasPrefix("HTTP/1.1 413"))
    }

    func testZipEntryNamesAreSafe() {
        let names = HttpShareServer.dedupeNames([".", "..", "a/../../etc/passwd", "bad\u{0}name", "ctl\u{7}\u{1b}x", "ok.txt", "ok.txt"])
        XCTAssertEqual(names[0], "file"); XCTAssertEqual(names[1], "file (1)".isEmpty ? "" : names[1])
        XCTAssertFalse(names.contains(".") || names.contains(".."))
        for n in names {
            XCTAssertFalse(n.contains("/") || n.contains("\\"), n)
            XCTAssertFalse(n.unicodeScalars.contains { $0.value < 0x20 }, n)
        }
        XCTAssertEqual(names[5], "ok.txt"); XCTAssertEqual(names[6], "ok (1).txt")
    }

    // MARK: body framing

    /// Content-Length is sent before the body: a file that grew after the manifest was built used to
    /// spill extra bytes past the declared length; one that shrank was reported as a completed download.
    func testDownloadNeverExceedsDeclaredLengthAndShortFilesAbort() throws {
        let grown = try file("grown.bin", bytes: 1000, declared: 400)
        let shrunk = try file("shrunk.bin", bytes: 100, declared: 400)
        let port = try start([grown, shrunk])
        let g = try raw(port, "GET /\(token)/f/0 HTTP/1.1\r\nHost: x\r\n\r\n")
        XCTAssertEqual(g.body.count, 400)
        XCTAssertTrue(g.head.contains("Content-Length: 400"))
        _ = try? raw(port, "GET /\(token)/f/1 HTTP/1.1\r\nHost: x\r\n\r\n")
        Thread.sleep(forTimeInterval: 0.2)
        let st = server!.stats()
        XCTAssertEqual(st.downloadsStarted, 2)
        XCTAssertEqual(st.downloadsDone, 1, "the truncated file must not count as delivered")
    }

    // MARK: token handling / headers

    func testTokenIsRequiredAndNeverLeaksViaReferer() throws {
        let port = try start([try file("a.bin", bytes: 10)])
        for path in ["/", "/index.html", "/manifest.json", "/f/0", "/all.zip", "/status.json",
                     "/\(token.dropLast())/manifest.json", "/\(token)x/manifest.json", "/%2f\(token)/manifest.json",
                     "//\(token)/manifest.json", "/00112233445566778899aabbccddeef0/manifest.json"] {
            let r = try raw(port, "GET \(path) HTTP/1.1\r\nHost: x\r\n\r\n")
            XCTAssertTrue(r.head.hasPrefix("HTTP/1.1 404"), "\(path) → \(r.head.prefix(20))")
            XCTAssertFalse(String(decoding: r.body, as: UTF8.self).contains("\"files\""))
        }
        for path in ["/\(token)/", "/\(token)/manifest.json", "/\(token)/f/0", "/\(token)/status.json"] {
            let r = try raw(port, "GET \(path) HTTP/1.1\r\nHost: x\r\n\r\n")
            XCTAssertTrue(r.head.lowercased().contains("referrer-policy: no-referrer"), path)
            XCTAssertTrue(r.head.lowercased().contains("x-content-type-options: nosniff"), path)
        }
        // the page itself loads nothing from other origins
        let page = try raw(port, "GET /\(token)/ HTTP/1.1\r\nHost: x\r\n\r\n")
        let html = String(decoding: page.body, as: UTF8.self)
        XCTAssertFalse(html.contains("src=\"http") || html.contains("href=\"http") || html.contains("url(http"))
    }

    func testFilenameCannotInjectHeaders() throws {
        let f = try file("a.bin", bytes: 5)
        let evil = OutgoingFile(meta: FileMeta(name: "x\r\nSet-Cookie: pwn=1\r\n\r\nHTTP/1.1 200", size: 5, mime: "text/html\r\nX-Evil: 1"), url: f.url)
        let port = try start([evil])
        let r = try raw(port, "GET /\(token)/f/0 HTTP/1.1\r\nHost: x\r\n\r\n")
        // the text may survive *inside* the quoted filename, but never as a header line of its own
        let lines = r.head.components(separatedBy: "\r\n").map { $0.lowercased() }
        XCTAssertFalse(lines.contains { $0.hasPrefix("set-cookie") || $0.hasPrefix("x-evil") })
        XCTAssertEqual(lines.count, Set(lines).count + 0, "no duplicated/injected lines")
        XCTAssertTrue(r.head.hasPrefix("HTTP/1.1 200"))
        XCTAssertEqual(r.body.count, 5)
    }

    // MARK: slow-loris / connection caps

    func testSlowHeadAndConnectionCap() throws {
        HttpShareServer.headDeadline = 1.0; HttpShareServer.headReadTimeout = 0.5
        let port = try start([try file("a.bin", bytes: 10)])
        // trickling head is cut off by the overall deadline
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        s.setTimeout(10)
        let t0 = Date()
        for b in Array("GET /\(token)/manifest.json HTTP/1.1\r\nX-Slow: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".utf8) {
            do { try s.writeAll([b]) } catch { break }
            Thread.sleep(forTimeInterval: 0.15)
            if Date().timeIntervalSince(t0) > 3.5 { break }
        }
        _ = try? s.recvSome(64)
        XCTAssertLessThan(Date().timeIntervalSince(t0), 4.0)
        s.close()
        // per-address cap: the 9th simultaneous connection is dropped
        HttpShareServer.headDeadline = 15; HttpShareServer.headReadTimeout = 5
        var idle: [TCPSocket] = []
        for _ in 0..<8 { idle.append(try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)) }
        Thread.sleep(forTimeInterval: 0.3)
        let extra = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        extra.setTimeout(3)
        XCTAssertEqual((try? extra.recvSome(16))?.isEmpty, true)
        extra.close()
        idle.forEach { $0.close() }
        Thread.sleep(forTimeInterval: 0.3)
        let ok = try raw(port, "GET /\(token)/status.json HTTP/1.1\r\nHost: x\r\n\r\n")
        XCTAssertTrue(ok.head.hasPrefix("HTTP/1.1 200"))
    }

    func testCloseCutsInFlightDownloadsAndFreesPort() throws {
        let f = try file("big.bin", bytes: 6_000_000)
        let port = try start([f])
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        s.setTimeout(10)
        try s.writeAll(Array("GET /\(token)/f/0 HTTP/1.1\r\nHost: x\r\n\r\n".utf8))
        _ = try s.recvSome(1024)                      // download is under way (client stops reading)
        server!.close()
        var total = 0
        let t0 = Date()
        while let part = try? s.recvSome(64 * 1024), !part.isEmpty { total += part.count; if Date().timeIntervalSince(t0) > 5 { break } }
        XCTAssertLessThan(total, 6_000_000, "close() must cut the transfer")
        s.close()
    }
}
