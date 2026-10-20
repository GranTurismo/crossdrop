import XCTest
import CryptoKit
@testable import CrossDropCore

/// Regression tests from the pre-release security audit of the LAN-facing servers. Every test here
/// pins a bug that was real before the fix.
final class VaultAuditTests: XCTestCase {
    private var root: URL!
    private var server: VaultServer?
    private let fm = FileManager.default

    override func setUp() {
        root = fm.temporaryDirectory.appendingPathComponent("vaud-\(UUID().uuidString)")
        try! fm.createDirectory(at: root.appendingPathComponent("docs/sub"), withIntermediateDirectories: true)
        try! "keep me".write(to: root.appendingPathComponent("docs/a.txt"), atomically: true, encoding: .utf8)
        try! "deep".write(to: root.appendingPathComponent("docs/sub/b.txt"), atomically: true, encoding: .utf8)
        try! "top".write(to: root.appendingPathComponent("top.txt"), atomically: true, encoding: .utf8)
    }
    override func tearDown() {
        server?.close()
        VaultHttp.headDeadline = 15; VaultHttp.headReadTimeout = 5
        try? fm.removeItem(at: root)
    }

    @discardableResult
    private func serve(writable: Bool = true, pin: String? = nil) -> Int {
        let s = VaultServer(fs: FileVaultFs(rootDir: root), identity: SessionIdentity(), deviceName: { "AuditHost" },
                            writable: writable, pin: pin, instanceId: "audit01")
        server = s
        return try! s.start()
    }

    struct Resp { let code: Int; let headers: [String: String]; let body: [UInt8] }

    private func http(_ port: Int, _ method: String, _ path: String, _ headers: [String: String] = [:],
                      body: [UInt8]? = nil, host: String? = nil) throws -> Resp {
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        defer { s.close() }
        s.setTimeout(10)
        var req = "\(method) \(path) HTTP/1.1\r\nHost: \(host ?? "127.0.0.1:\(port)")\r\nConnection: close\r\n"
        for (k, v) in headers { req += "\(k): \(v)\r\n" }
        if let body { req += "Content-Length: \(body.count)\r\n" }
        req += "\r\n"
        try s.writeAll(Array(req.utf8) + (body ?? []))
        return try readResponse(s)
    }

    private func readResponse(_ s: TCPSocket) throws -> Resp {
        var raw: [UInt8] = []
        while true { let part = try s.recvSome(64 * 1024); if part.isEmpty { break }; raw += part }
        let sep: [UInt8] = [13, 10, 13, 10]
        var i = 0
        while i + 3 < raw.count && Array(raw[i..<i + 4]) != sep { i += 1 }
        let head = String(decoding: raw[0..<min(i, raw.count)], as: UTF8.self).components(separatedBy: "\r\n")
        var hs: [String: String] = [:]
        for l in head.dropFirst() { if let c = l.firstIndex(of: ":") { hs[l[..<c].lowercased()] = l[l.index(after: c)...].trimmingCharacters(in: .whitespaces) } }
        return Resp(code: Int(head[0].split(separator: " ").dropFirst().first ?? "0") ?? 0, headers: hs, body: Array(raw[min(i + 4, raw.count)...]))
    }

    private func exists(_ rel: String) -> Bool { fm.fileExists(atPath: root.appendingPathComponent(rel).path) }

    // MARK: MOVE / COPY must never delete the vault

    /// MOVE/COPY with `Destination: /` (overwrite defaults to T) used to `removeItem(root)` —
    /// wiping the entire shared folder — and the same for any ancestor of the source.
    func testMoveOrCopyOntoRootOrAncestorDeletesNothing() throws {
        let p = serve()
        for method in ["MOVE", "COPY"] {
            for dest in ["/", "", "http://127.0.0.1:\(p)/", "/docs", "/docs/"] {
                let src = dest.hasPrefix("/docs") ? "/docs/sub/b.txt" : "/top.txt"
                let r = try http(p, method, src, ["Destination": dest])
                XCTAssertTrue([400, 403, 409].contains(r.code), "\(method) → \(dest.debugDescription) gave \(r.code)")
                XCTAssertTrue(exists("top.txt") && exists("docs/a.txt") && exists("docs/sub/b.txt"), "\(method) → \(dest.debugDescription) destroyed data")
            }
        }
        // the legitimate case still works
        XCTAssertEqual(try http(p, "COPY", "/top.txt", ["Destination": "/docs/copy.txt"]).code, 201)
        XCTAssertTrue(exists("docs/copy.txt"))
    }

    func testFsMoveCopyGuards() throws {
        let fs = FileVaultFs(rootDir: root)
        for op in [fs.move, fs.copy] {
            XCTAssertThrowsError(try op("/docs/a.txt", "/", true))
            XCTAssertThrowsError(try op("/docs/sub/b.txt", "/docs", true))
            XCTAssertThrowsError(try op("/docs", "/docs/sub/x", true))
        }
        XCTAssertTrue(exists("docs/a.txt") && exists("docs/sub/b.txt"))
    }

    // MARK: free-space accounting

    /// `forBytes + minFree` overflowed for a hostile VAULT_PUSH_INIT size (trap in checked builds,
    /// wraparound = "plenty of room" in -Ounchecked).
    func testHasRoomIsOverflowSafeAndReservationsAccumulate() throws {
        let fs = FileVaultFs(rootDir: root)
        XCTAssertFalse(fs.hasRoom(forBytes: Int64.max))
        XCTAssertFalse(fs.hasRoom(forBytes: Int64.max - 1000))
        XCTAssertFalse(fs.hasRoom(forBytes: -1))
        XCTAssertTrue(fs.hasRoom(forBytes: 1024))
        XCTAssertFalse(fs.reserve(Int64.max))
        XCTAssertTrue(fs.reserve(1 << 20))
        fs.release(1 << 20)
        // Two uploads that each fit alone but not together: the second reserve must fail.
        var s = statfs(); XCTAssertEqual(statfs(root.path, &s), 0)
        let free = Int64(s.f_bavail) * Int64(s.f_bsize) - VaultPaths.minFree
        guard free > 4096 else { return }
        let half = free / 2 + 1024
        XCTAssertTrue(fs.reserve(half))
        XCTAssertFalse(fs.reserve(half), "second reservation must see the first")
        fs.release(half)
        XCTAssertTrue(fs.reserve(half))
        fs.release(half)
    }

    func testNativePushWithHugeSizeIsRefusedNotCrashed() throws {
        let p = serve()
        let c = VaultClient(host: "127.0.0.1", port: p, identity: SessionIdentity(), deviceName: "c", instanceId: "c1")
        try c.connect()
        defer { c.close() }
        final class Empty: VaultByteSource { func read(_ max: Int) throws -> [UInt8] { [] } }
        for size in [Int64.max, Int64.max - 5, 1 << 62] {
            XCTAssertThrowsError(try c.push("/big.bin", size: size, source: Empty())) { e in
                XCTAssertEqual((e as? VaultError)?.code, 413)
            }
        }
        XCTAssertTrue(server!.isRunning)
    }

    // MARK: cross-site / DNS rebinding

    func testHostHeaderAllowList() {
        for ok in ["127.0.0.1:8080", "192.168.1.20", "10.0.0.5:80", "localhost", "localhost:8080", "phone.local",
                   "iPhone-van.local:8080", "nas", "[::1]:8080", "[fe80::1%25en0]:8080", "router.lan", "x.home.arpa"] {
            XCTAssertTrue(VaultHttp.hostAllowed(ok), ok)
        }
        for bad in ["evil.example.com", "evil.example.com:8080", "192.168.1.20.evil.com", "attacker.io", "a.b.c", ""] {
            XCTAssertFalse(VaultHttp.hostAllowed(bad), bad)
        }
    }

    func testDnsRebindingHostIsRefusedOnEverySurface() throws {
        let p = serve()
        for (m, path) in [("GET", "/api/fs?path=/"), ("GET", "/top.txt"), ("PROPFIND", "/"), ("GET", "/raw/top.txt")] {
            let r = try http(p, m, path, host: "evil.example.com:\(p)")
            XCTAssertEqual(r.code, 403, "\(m) \(path)")
            XCTAssertFalse(String(decoding: r.body, as: UTF8.self).contains("keep me"))
        }
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/", host: "phone.local:\(p)").code, 200)
    }

    /// A page on any website can fire `POST http://<phone>:8080/upload` as a simple cross-origin
    /// request (no preflight). Same-origin pages (our own explorer) and native clients (no Origin)
    /// must keep working.
    func testCrossOriginWritesAreRefused() throws {
        let p = serve()
        let boundary = "----xsite"
        let part = "--\(boundary)\r\nContent-Disposition: form-data; name=\"f\"; filename=\"pwn.txt\"\r\n\r\nowned\r\n--\(boundary)--\r\n"
        let ct = "multipart/form-data; boundary=\(boundary)"
        var r = try http(p, "POST", "/upload?path=/", ["Content-Type": ct, "Origin": "http://evil.example.com"], body: Array(part.utf8))
        XCTAssertEqual(r.code, 403)
        r = try http(p, "POST", "/upload?path=/", ["Content-Type": ct, "Origin": "null"], body: Array(part.utf8))
        XCTAssertEqual(r.code, 403)
        r = try http(p, "PUT", "/pwn2.txt", ["Origin": "http://evil.example.com"], body: Array("x".utf8))
        XCTAssertEqual(r.code, 403)
        r = try http(p, "DELETE", "/top.txt", ["Origin": "http://192.168.1.99:\(p)"])
        XCTAssertEqual(r.code, 403)
        XCTAssertTrue(exists("top.txt")); XCTAssertFalse(exists("pwn.txt")); XCTAssertFalse(exists("pwn2.txt"))
        // same-origin page + origin-less native client
        r = try http(p, "POST", "/upload?path=/", ["Content-Type": ct, "Origin": "http://127.0.0.1:\(p)"], body: Array(part.utf8))
        XCTAssertEqual(r.code, 200); XCTAssertTrue(exists("pwn.txt"))
        XCTAssertEqual(try http(p, "PUT", "/native.txt", body: Array("ok".utf8)).code, 201)
    }

    // MARK: Digest

    private func digestHeader(user: String = "drive", pin: String, method: String, uri: String, nonce: String) -> String {
        func md5(_ s: String) -> String { Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
        let cnonce = "abc123", nc = "00000001"
        let ha1 = md5("\(user):crossdrop:\(pin)"), ha2 = md5("\(method):\(uri)")
        let resp = md5("\(ha1):\(nonce):\(nc):\(cnonce):auth:\(ha2)")
        return "Digest username=\"\(user)\", realm=\"crossdrop\", nonce=\"\(nonce)\", uri=\"\(uri)\", qop=auth, nc=\(nc), cnonce=\"\(cnonce)\", response=\"\(resp)\", algorithm=MD5"
    }

    private func challengeNonce(_ p: Int, _ path: String = "/top.txt") throws -> String {
        let r = try http(p, "GET", path)
        XCTAssertEqual(r.code, 401)
        let www = try XCTUnwrap(r.headers["www-authenticate"])
        let m = try XCTUnwrap(www.range(of: #"nonce="([0-9a-f]+)""#, options: .regularExpression))
        return String(www[m]).replacingOccurrences(of: "nonce=\"", with: "").replacingOccurrences(of: "\"", with: "")
    }

    func testDigestNonceBindsToUriAndIsNotForgeable() throws {
        let p = serve(pin: "4821")
        let n1 = try challengeNonce(p)
        // valid digest for the requested resource
        XCTAssertEqual(try http(p, "GET", "/top.txt", ["Authorization": digestHeader(pin: "4821", method: "GET", uri: "/top.txt", nonce: n1)]).code, 200)
        // a header captured for /top.txt must not unlock another path
        XCTAssertEqual(try http(p, "GET", "/docs/a.txt", ["Authorization": digestHeader(pin: "4821", method: "GET", uri: "/top.txt", nonce: n1)]).code, 401)
        // forged / made-up nonce (the old static nonce design accepted any replayed response forever)
        XCTAssertEqual(try http(p, "GET", "/top.txt", ["Authorization": digestHeader(pin: "4821", method: "GET", uri: "/top.txt", nonce: String(repeating: "0", count: 48))]).code, 401)
    }

    func testExpiredNonceIsStaleAndRefused() throws {
        let h = VaultHttp(fs: FileVaultFs(rootDir: root), writable: false, pin: "4821", deviceName: { "x" })
        XCTAssertEqual(h.checkNonce(h.makeNonce()), .valid)
        XCTAssertEqual(h.checkNonce(h.makeNonce(at: Date().addingTimeInterval(-13 * 3600))), .stale)
        XCTAssertEqual(h.checkNonce(h.makeNonce(at: Date().addingTimeInterval(3600))), .stale)
        XCTAssertEqual(h.checkNonce("zz"), .forged)
        var tampered = Array(h.makeNonce()); tampered[20] = tampered[20] == "0" ? "1" : "0"
        XCTAssertEqual(h.checkNonce(String(tampered)), .forged)
        // another server instance (different key) can't mint nonces for this one
        let other = VaultHttp(fs: FileVaultFs(rootDir: root), writable: false, pin: "4821", deviceName: { "x" })
        XCTAssertEqual(h.checkNonce(other.makeNonce()), .forged)
    }

    // MARK: PIN throttle

    func testThrottleKeysNormaliseAddresses() {
        XCTAssertEqual(PinThrottle.key(for: "192.168.1.5"), "192.168.1.5")
        XCTAssertEqual(PinThrottle.key(for: "::ffff:192.168.1.5"), "192.168.1.5")
        XCTAssertEqual(PinThrottle.key(for: "fe80::1%en0"), PinThrottle.key(for: "fe80::2%en0"), "same /64")
        XCTAssertEqual(PinThrottle.key(for: "2001:db8:1:2:aaaa:bbbb:cccc:dddd"), PinThrottle.key(for: "2001:db8:1:2:1::9"), "privacy-address rotation stays in one bucket")
        XCTAssertNotEqual(PinThrottle.key(for: "2001:db8:1:3::1"), PinThrottle.key(for: "2001:db8:1:2::1"))
    }

    func testThrottlePerClientAndGlobalLockout() throws {
        let t = PinThrottle()
        for _ in 0..<PinThrottle.maxStrikes { XCTAssertFalse(try t.record("10.0.0.1", ok: false)) }
        XCTAssertThrowsError(try t.record("10.0.0.1", ok: true))          // locked even with the right PIN
        XCTAssertThrowsError(try t.checkLocked("::ffff:10.0.0.1"))        // same client via the mapped form
        XCTAssertNoThrow(try t.checkLocked("10.0.0.2"))
        // many clients, a few guesses each: the global budget trips
        let g = PinThrottle()
        var tripped = false
        for i in 0..<(PinThrottle.globalMaxStrikes + 5) {
            do { _ = try g.record("10.1.\(i / 250).\(i % 250)", ok: false) } catch { tripped = true; break }
        }
        XCTAssertTrue(tripped, "distributed guessing must hit the global limit")
        XCTAssertThrowsError(try g.checkLocked("10.9.9.9"))
    }

    /// The native (AEAD) PIN check had no lockout at all — only a 300 ms sleep per connection.
    func testNativePinGuessingIsLockedOutAndSharesBudgetWithHttp() throws {
        let p = serve(pin: "7777")
        func nativeTry(_ pin: String) -> Int? {
            let c = VaultClient(host: "127.0.0.1", port: p, identity: SessionIdentity(), deviceName: "g", instanceId: "g1")
            defer { c.close() }
            do { try c.connect(pin: pin); return 200 } catch let e as VaultError { return e.code } catch { return nil }
        }
        // 2 misses over HTTP + 3 over the native protocol = 5 strikes from this client
        for _ in 0..<2 { XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=0000").code, 401) }
        for _ in 0..<3 { XCTAssertEqual(nativeTry("1111"), 401) }
        XCTAssertEqual(nativeTry("7777"), 429, "locked out — even the right PIN is refused")
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=7777").code, 429)
    }

    // MARK: slow-loris / connection caps

    func testSlowHeadIsCutOff() throws {
        VaultHttp.headDeadline = 1.0; VaultHttp.headReadTimeout = 0.5
        let p = serve()
        let s = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
        defer { s.close() }
        s.setTimeout(10)
        let t0 = Date()
        // trickle a request head one byte at a time: the overall deadline (not the per-read
        // timeout) must end it
        var closed = false
        for b in Array("GET /api/info HTTP/1.1\r\nHost: x\r\nX-Slow: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".utf8) {
            do { try s.writeAll([b]) } catch { closed = true; break }
            Thread.sleep(forTimeInterval: 0.15)
            if Date().timeIntervalSince(t0) > 4 { break }
        }
        let resp = try? s.recvSome(4096)          // server answers 400 then hangs up
        XCTAssertLessThan(Date().timeIntervalSince(t0), 4.5, "slow head must not hold a slot")
        _ = closed
        XCTAssertTrue(resp == nil || resp!.isEmpty || String(decoding: resp!, as: UTF8.self).contains("400"))
    }

    func testPerClientConnectionCapLeavesRoomForOthers() throws {
        let p = serve()
        var idle: [TCPSocket] = []
        for _ in 0..<6 { idle.append(try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)) }
        Thread.sleep(forTimeInterval: 0.3)
        // the 7th connection from the same address is dropped immediately instead of queueing
        let extra = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
        extra.setTimeout(3)
        let got = try? extra.recvSome(16)
        XCTAssertEqual(got?.isEmpty, true, "7th connection from one IP must be closed")
        extra.close()
        idle.forEach { $0.close() }
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(try http(p, "GET", "/api/info").code, 200, "slots are released when idle clients leave")
    }

    // MARK: multipart parser limits

    func testMultipartHeaderFloodIsRejected() throws {
        let p = serve()
        let boundary = "----flood"
        // one endless header line (100 KB) — used to be buffered without bound
        var body = Array("--\(boundary)\r\nX-Evil: ".utf8)
        body += [UInt8](repeating: 0x41, count: 100_000)
        body += Array("\r\n\r\nx\r\n--\(boundary)--\r\n".utf8)
        let r = try http(p, "POST", "/upload?path=/", ["Content-Type": "multipart/form-data; boundary=\(boundary)"], body: body)
        XCTAssertEqual(r.code, 400)
        // 100 small headers
        var many = Array("--\(boundary)\r\n".utf8)
        for i in 0..<100 { many += Array("X-\(i): v\r\n".utf8) }
        many += Array("\r\nx\r\n--\(boundary)--\r\n".utf8)
        XCTAssertEqual(try http(p, "POST", "/upload?path=/", ["Content-Type": "multipart/form-data; boundary=\(boundary)"], body: many).code, 400)
        XCTAssertTrue(server!.isRunning)
    }

    func testUploadFilenameTraversalAndStagingCleanup() throws {
        let p = serve()
        let boundary = "----fn"
        for name in ["../../evil.txt", "..\\..\\evil2.txt", "/etc/passwd", "a/../../evil3.txt"] {
            let part = "--\(boundary)\r\nContent-Disposition: form-data; name=\"f\"; filename=\"\(name)\"\r\n\r\ndata\r\n--\(boundary)--\r\n"
            _ = try http(p, "POST", "/upload?path=/docs", ["Content-Type": "multipart/form-data; boundary=\(boundary)"], body: Array(part.utf8))
        }
        let outside = root.deletingLastPathComponent()
        for n in ["evil.txt", "evil2.txt", "evil3.txt"] { XCTAssertFalse(fm.fileExists(atPath: outside.appendingPathComponent(n).path)) }
        XCTAssertTrue(exists("docs/evil.txt") || exists("docs/evil2.txt") || exists("docs/passwd"), "basename is kept inside the target dir")
        XCTAssertFalse(exists("evil.txt"))
        // a connection that dies mid-upload leaves no staging debris
        let s = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
        try s.writeAll(Array("POST /upload?path=/ HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=\(boundary)\r\nContent-Length: 5000000\r\n\r\n--\(boundary)\r\nContent-Disposition: form-data; name=\"f\"; filename=\"half.bin\"\r\n\r\nPARTIAL".utf8))
        Thread.sleep(forTimeInterval: 0.3)
        s.close()
        Thread.sleep(forTimeInterval: 0.5)
        let staging = (try? fm.contentsOfDirectory(atPath: root.appendingPathComponent(".staging").path)) ?? []
        XCTAssertTrue(staging.isEmpty, "staging leftovers: \(staging)")
        XCTAssertFalse(exists("half.bin"))
    }

    // MARK: symlinks, listing, response hardening

    func testSymlinksThatLeaveTheVaultAreInvisibleAndUnreadable() throws {
        let outside = fm.temporaryDirectory.appendingPathComponent("outside-\(UUID().uuidString)")
        try fm.createDirectory(at: outside, withIntermediateDirectories: true)
        try "TOP SECRET".write(to: outside.appendingPathComponent("secret.txt"), atomically: true, encoding: .utf8)
        defer { try? fm.removeItem(at: outside) }
        try fm.createSymbolicLink(at: root.appendingPathComponent("leak.txt"), withDestinationURL: outside.appendingPathComponent("secret.txt"))
        try fm.createSymbolicLink(at: root.appendingPathComponent("leakdir"), withDestinationURL: outside)
        let p = serve()
        let ls = try http(p, "GET", "/api/fs?path=/")
        let text = String(decoding: ls.body, as: UTF8.self)
        XCTAssertFalse(text.contains("leak"), "escaping symlinks must not even be listed: \(text)")
        for path in ["/leak.txt", "/raw/leak.txt", "/leakdir/secret.txt", "/raw/leakdir/secret.txt"] {
            let r = try http(p, "GET", path)
            XCTAssertTrue([403, 404].contains(r.code), "\(path) → \(r.code)")
            XCTAssertFalse(String(decoding: r.body, as: UTF8.self).contains("TOP SECRET"))
        }
        XCTAssertTrue([403, 404].contains(try http(p, "PUT", "/leakdir/new.txt", body: Array("x".utf8)).code))
        XCTAssertFalse(fm.fileExists(atPath: outside.appendingPathComponent("new.txt").path))
        XCTAssertTrue([403, 404].contains(try http(p, "DELETE", "/leakdir/secret.txt").code))
        XCTAssertTrue(fm.fileExists(atPath: outside.appendingPathComponent("secret.txt").path))
        XCTAssertTrue([403, 404].contains(try http(p, "COPY", "/top.txt", ["Destination": "/leakdir/c.txt"]).code))
        XCTAssertFalse(fm.fileExists(atPath: outside.appendingPathComponent("c.txt").path))
    }

    func testEncodedTraversalVariants() throws {
        let p = serve()
        for path in ["/raw/%2e%2e/%2e%2e/etc/passwd", "/raw/..%2f..%2fetc/passwd", "/raw/%252e%252e/x", "/raw/..%5c..%5cetc",
                     "/%2e%2e/%2e%2e/etc/passwd", "/raw/docs/..%00/x", "/raw/%c0%ae%c0%ae/x", "/raw/\(String(repeating: "a/", count: 4000))x"] {
            let r = try http(p, "GET", path)
            XCTAssertTrue([400, 403, 404, 414, 431].contains(r.code), "\(path.prefix(40)) → \(r.code)")
            XCTAssertFalse(String(decoding: r.body, as: UTF8.self).contains("root:"))
        }
        XCTAssertTrue(server!.isRunning)
    }

    func testActiveContentIsSandboxedAndHeadersHardened() throws {
        try "<script>alert(1)</script>".write(to: root.appendingPathComponent("x.html"), atomically: true, encoding: .utf8)
        try "<svg xmlns='http://www.w3.org/2000/svg'/>".write(to: root.appendingPathComponent("x.svg"), atomically: true, encoding: .utf8)
        let p = serve()
        for f in ["x.html", "x.svg"] {
            let r = try http(p, "GET", "/raw/\(f)")
            XCTAssertEqual(r.code, 200)
            XCTAssertEqual(r.headers["content-security-policy"], "sandbox", f)
            XCTAssertEqual(r.headers["x-content-type-options"], "nosniff")
        }
        XCTAssertNil(try http(p, "GET", "/raw/top.txt").headers["content-security-policy"])
        let page = try http(p, "GET", "/")
        XCTAssertEqual(page.headers["x-frame-options"], "DENY")
        XCTAssertEqual(page.headers["referrer-policy"], "no-referrer")
    }

    func testPropfindStaysWellFormedWithHostileNames() throws {
        for name in ["a\u{1}b.txt", "x<y>&\"'z.txt", "tab\there.txt", "emoji🙂.txt"] {
            try? "x".write(to: root.appendingPathComponent(name), atomically: true, encoding: .utf8)
        }
        let p = serve()
        let r = try http(p, "PROPFIND", "/", ["Depth": "1"])
        XCTAssertEqual(r.code, 207)
        final class D: NSObject, XMLParserDelegate { var err: Error?; func parser(_ p: XMLParser, parseErrorOccurred e: Error) { err = e } }
        let d = D(); let parser = XMLParser(data: Data(r.body)); parser.delegate = d
        XCTAssertTrue(parser.parse(), "PROPFIND XML must parse: \(String(describing: d.err)) — \(String(decoding: r.body, as: UTF8.self).prefix(400))")
    }

    // MARK: chunked PUT (Finder)

    private func chunked(_ parts: [String], trailers: String = "") -> [UInt8] {
        var out = ""
        for p in parts { out += String(p.utf8.count, radix: 16) + "\r\n" + p + "\r\n" }
        return Array((out + "0\r\n" + trailers + "\r\n").utf8)
    }

    /// macOS Finder uploads large files with `Transfer-Encoding: chunked` (no Content-Length); the
    /// server used to answer 411, so writes from Finder failed.
    func testChunkedPutIsAcceptedAndBounded() throws {
        let p = serve()
        func put(_ path: String, _ body: [UInt8], extra: [String: String] = [:]) throws -> Resp {
            let s = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
            defer { s.close() }
            s.setTimeout(10)
            var req = "PUT \(path) HTTP/1.1\r\nHost: 127.0.0.1:\(p)\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n"
            for (k, v) in extra { req += "\(k): \(v)\r\n" }
            try s.writeAll(Array((req + "\r\n").utf8) + body)
            return try readResponse(s)
        }
        var r = try put("/chunked.txt", chunked(["hello ", "chunked ", "world"]))
        XCTAssertEqual(r.code, 201)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("chunked.txt")), "hello chunked world")
        r = try put("/chunked.txt", chunked(["replaced"], trailers: "X-Trailer: 1\r\n"), extra: ["X-Expected-Entity-Length": "8"])
        XCTAssertEqual(r.code, 204)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("chunked.txt")), "replaced")
        // chunk extensions are ignored
        r = try put("/ext.txt", Array("5;name=val\r\nabcde\r\n0\r\n\r\n".utf8))
        XCTAssertEqual(r.code, 201)
        // malformed framing → 400, and nothing is committed
        for bad in ["zz\r\nabc\r\n0\r\n\r\n", "FFFFFFFFFF\r\nabc", "3\r\nabcXX0\r\n\r\n", String(repeating: "A", count: 400) + "\r\n"] {
            r = try put("/bad.txt", Array(bad.utf8))
            XCTAssertEqual(r.code, 400, bad.prefix(20).debugDescription)
        }
        XCTAssertFalse(exists("bad.txt"))
        // outside the jail / into a symlink-free but read-only server
        r = try put("/../escape.txt", chunked(["x"]))
        XCTAssertTrue([400, 403].contains(r.code))
        // an absurd size hint is refused before any byte is read
        r = try put("/huge.bin", chunked(["x"]), extra: ["X-Expected-Entity-Length": "9000000000000000000"])
        XCTAssertEqual(r.code, 413)
        // truncated body (client vanishes mid-chunk) leaves no file and no staging debris
        let s = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
        try s.writeAll(Array("PUT /torn.bin HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n10\r\nonly-part".utf8))
        Thread.sleep(forTimeInterval: 0.2); s.close(); Thread.sleep(forTimeInterval: 0.4)
        XCTAssertFalse(exists("torn.bin"))
        XCTAssertTrue(((try? fm.contentsOfDirectory(atPath: root.appendingPathComponent(".staging").path)) ?? []).isEmpty)
        // negative Content-Length is a 400, not a crash
        XCTAssertEqual(try http(p, "PUT", "/neg.txt", ["Content-Length": "-5"]).code, 400)
        XCTAssertTrue(server!.isRunning)
    }

    // MARK: native protocol edge cases

    private func rawNative(_ port: Int) throws -> (FrameIO, TCPSocket) {
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        s.setTimeout(10)
        let io = FrameIO(socket: s)
        let id = SessionIdentity()
        try io.write(.hello, Hello(version: protocolVersion, deviceName: "raw", publicKeyB64: b64(id.publicKey), instanceId: "raw1").encodeBytes())
        let (t, p) = try io.read()
        XCTAssertEqual(t, .helloAck)
        let ack = try p.decodeJSON(HelloAck.self)
        let keys = try SessionCrypto.deriveSession(identity: id, peerPublicKey: try unb64(ack.publicKeyB64), isInitiator: true)
        io.readCipher = RecordCipher(key: keys.recvKey); io.writeCipher = RecordCipher(key: keys.sendKey)
        return (io, s)
    }

    func testNativeCommandsBeforeAuthAreRejected() throws {
        let p = serve(pin: "1234")
        let (io, s) = try rawNative(p)
        defer { s.close() }
        try io.write(.vaultLs, VaultLs(path: "/").encodeBytes())
        // the server hangs up (or errors) — it must never answer with a directory listing
        let reply = try? io.read()
        if let (t, _) = reply { XCTAssertNotEqual(t, .vaultDir) }
    }

    func testNativePushCannotExceedDeclaredSize() throws {
        let p = serve()
        let (io, s) = try rawNative(p)
        defer { s.close() }
        try io.write(.vaultPushInit, VaultPushInit(path: "/liar.bin", size: 10, mime: nil).encodeBytes())
        let (t, pl) = try io.read()
        XCTAssertEqual(t, .vaultPushAck)
        let ack = try pl.decodeJSON(VaultAck.self)
        try io.write(.vaultData, VaultWire.header(tid: ack.transferId, offset: 0) + [UInt8](repeating: 7, count: 5000))
        let (et, ep) = try io.read()
        XCTAssertEqual(et, .vaultErr)
        XCTAssertEqual(try ep.decodeJSON(VaultErr.self).code, 400)
        XCTAssertFalse(exists("liar.bin"))
    }

    func testNativeFrameWithGarbageDoesNotKillServer() throws {
        let p = serve()
        for payload in [[UInt8](repeating: 0xFF, count: 64), [0, 0, 0, 5, 1, 2, 3, 4, 5], [0x00, 0x00, 0x00, 0x00], []] {
            let s = try TCPSocket.connect(host: "127.0.0.1", port: p, timeout: 5)
            s.setTimeout(2)
            try? s.writeAll(payload)
            _ = try? s.recvSome(64)
            s.close()
        }
        XCTAssertEqual(try http(p, "GET", "/api/info").code, 200)
    }

    // MARK: client against a hostile host

    /// A malicious "vault" that streams more than the requested range must not be able to fill the
    /// client's disk.
    func testClientRefusesMoreDataThanRequested() throws {
        let listener = TCPListener()
        let id = SessionIdentity()
        try listener.start { sock, _ in
            Thread.detachNewThread {
                let io = FrameIO(socket: sock)
                defer { sock.close() }
                guard let (_, hp) = try? io.read(), let hello = try? hp.decodeJSON(Hello.self) else { return }
                try? io.write(.helloAck, HelloAck(version: protocolVersion, deviceName: "evil", publicKeyB64: b64(id.publicKey), instanceId: "e", vaultAuth: "open").encodeBytes())
                guard let keys = try? SessionCrypto.deriveSession(identity: id, peerPublicKey: try unb64(hello.publicKeyB64), isInitiator: false) else { return }
                io.readCipher = RecordCipher(key: keys.recvKey); io.writeCipher = RecordCipher(key: keys.sendKey)
                guard let (_, pp) = try? io.read(), let req = try? pp.decodeJSON(VaultPull.self) else { return }
                for i in 0..<8 {
                    try? io.write(.vaultData, VaultWire.header(tid: req.transferId, offset: Int64(i) * 100_000) + [UInt8](repeating: 1, count: 100_000))
                }
            }
        }
        defer { listener.stop() }
        let c = VaultClient(host: "127.0.0.1", port: listener.port, identity: SessionIdentity(), deviceName: "c", instanceId: "c2")
        try c.connect()
        defer { c.close() }
        var written = 0
        XCTAssertThrowsError(try c.pull("/x.bin", offset: 0, length: 150_000, out: { written += $0.count }))
        XCTAssertLessThanOrEqual(written, 150_000)
    }
}
