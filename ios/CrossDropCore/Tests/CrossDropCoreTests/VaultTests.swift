import XCTest
import CryptoKit
@testable import CrossDropCore

/// Swift-side coverage of the Wireless Drive core: jail, HTTP/WebDAV surface, Digest + PIN lockout,
/// and the native AEAD session (Swift client ↔ Swift server). Kotlin interop lives in
/// core/…/SwiftVaultInteropTest.kt.
final class VaultFsTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("vfs-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: root.appendingPathComponent("docs"), withIntermediateDirectories: true)
        try! "hello".write(to: root.appendingPathComponent("docs/readme.txt"), atomically: true, encoding: .utf8)
        try! "hidden".write(to: root.appendingPathComponent(".secret"), atomically: true, encoding: .utf8)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: root) }

    func testSanitizeRejectsTraversalAbsoluteAndHidden() {
        for bad in ["..", "../x", "a/../../b", "..\\..\\win", "C:\\Windows", ".staging/x", ".hidden", "a/../b", "//..//x", "x\u{0}y"] {
            XCTAssertThrowsError(try VaultPaths.sanitize(bad), "accepted \(bad)") { e in
                XCTAssertTrue((400...403).contains((e as! VaultError).code), "\(bad) → \(e)")
            }
        }
        XCTAssertEqual(try VaultPaths.sanitize(""), "")
        XCTAssertEqual(try VaultPaths.sanitize("/"), "")
        XCTAssertEqual(try VaultPaths.sanitize("."), "")
        XCTAssertEqual(try VaultPaths.sanitize("//a//b//"), "a/b")
        XCTAssertEqual(try VaultPaths.sanitize("a/./b"), "a/b")
        XCTAssertEqual(try VaultPaths.sanitize("/etc/passwd"), "etc/passwd")      // leading slashes stripped, stays in jail
        // NFC: decomposed "é" normalises to the precomposed form
        XCTAssertEqual(try VaultPaths.sanitize("caf\u{65}\u{301}"), "caf\u{E9}")
    }

    func testResolveStaysInsideJailEvenThroughSymlinks() throws {
        let fs = FileVaultFs(rootDir: root)
        XCTAssertEqual(try fs.resolve(""), fs.root)
        XCTAssertEqual(try fs.resolve("/docs"), fs.root + "/docs")
        // a symlink pointing outside the jail must not be followable
        let outside = FileManager.default.temporaryDirectory.appendingPathComponent("outside-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: outside) }
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("escape"), withDestinationURL: outside)
        XCTAssertThrowsError(try fs.resolve("escape")) { XCTAssertEqual(($0 as! VaultError).code, 403) }
        XCTAssertThrowsError(try fs.resolve("escape/file.txt")) { XCTAssertEqual(($0 as! VaultError).code, 403) }
    }

    func testListHidesDotfilesAndSortsDirsFirst() throws {
        let fs = FileVaultFs(rootDir: root)
        try fs.purgeStaging()   // creates .staging
        try Data([1]).write(to: root.appendingPathComponent("a.bin"))
        let names = try fs.list("/").map(\.name)
        XCTAssertEqual(names, ["docs", "a.bin"])
        XCTAssertThrowsError(try fs.stat(".secret"))
    }

    func testCommitStagedDedupesAndCreatesParents() throws {
        let fs = FileVaultFs(rootDir: root)
        for i in 0..<3 {
            let stage = fs.stageFile(id: Int64(i))
            try Data("v\(i)".utf8).write(to: stage)
            let rel = try fs.commitStaged(stage, "up/new/pic.png")
            XCTAssertEqual(rel, i == 0 ? "up/new/pic.png" : "up/new/pic (\(i + 1)).png")
        }
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("up/new/pic.png")), "v0")
    }

    private final class Bytes: VaultByteSource {
        var data: [UInt8]; init(_ d: [UInt8]) { data = d }
        func read(_ max: Int) throws -> [UInt8] { let n = min(max, data.count); defer { data.removeFirst(n) }; return Array(data[0..<n]) }
    }

    func testWriteSurfaceRules() throws {
        let fs = FileVaultFs(rootDir: root)
        XCTAssertEqual(try fs.mkdir("fresh"), "fresh")
        XCTAssertThrowsError(try fs.mkdir("fresh")) { XCTAssertEqual(($0 as! VaultError).code, 405) }
        XCTAssertThrowsError(try fs.mkdir("ghost/sub")) { XCTAssertEqual(($0 as! VaultError).code, 409) }
        XCTAssertEqual(try fs.putStream("fresh/a.txt", input: Bytes(Array("abc".utf8)), length: 3), "fresh/a.txt")
        XCTAssertThrowsError(try fs.putStream("fresh/b.txt", input: Bytes([1]), length: 5)) { XCTAssertEqual(($0 as! VaultError).code, 400) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("fresh/b.txt").path))
        XCTAssertThrowsError(try fs.putStream("fresh", input: Bytes([]), length: 0)) { XCTAssertEqual(($0 as! VaultError).code, 409) }
        XCTAssertThrowsError(try fs.move("docs", "docs/inner", overwrite: true)) { XCTAssertEqual(($0 as! VaultError).code, 409) }
        XCTAssertThrowsError(try fs.move("docs", "docs", overwrite: true)) { XCTAssertEqual(($0 as! VaultError).code, 400) }
        try fs.copy("fresh/a.txt", "fresh/c.txt", overwrite: false)
        XCTAssertThrowsError(try fs.copy("fresh/a.txt", "fresh/c.txt", overwrite: false)) { XCTAssertEqual(($0 as! VaultError).code, 412) }
        try fs.delete("fresh")
        XCTAssertThrowsError(try fs.delete("fresh")) { XCTAssertEqual(($0 as! VaultError).code, 404) }
        XCTAssertThrowsError(try fs.delete("")) { XCTAssertEqual(($0 as! VaultError).code, 403) }
    }
}

final class VaultServerTests: XCTestCase {
    private var root: URL!
    private var server: VaultServer?
    private let blob = (0..<300_000).map { UInt8(truncatingIfNeeded: ($0 &* 31) ^ ($0 >> 3)) }

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("vsrv-\(UUID().uuidString)")
        let fm = FileManager.default
        try! fm.createDirectory(at: root.appendingPathComponent("docs"), withIntermediateDirectories: true)
        try! fm.createDirectory(at: root.appendingPathComponent("media"), withIntermediateDirectories: true)
        try! Data("hello vault".utf8).write(to: root.appendingPathComponent("docs/readme.txt"))
        try! Data(blob).write(to: root.appendingPathComponent("media/clip.bin"))
        try! Data((0..<1024).map { UInt8($0 & 0xFF) }).write(to: root.appendingPathComponent("pic.png"))
        try! Data("hidden".utf8).write(to: root.appendingPathComponent(".secret"))
    }
    override func tearDown() { server?.close(); try? FileManager.default.removeItem(at: root) }

    @discardableResult
    private func serve(writable: Bool = true, pin: String? = nil, preferred: Int = 0) -> Int {
        let s = VaultServer(fs: FileVaultFs(rootDir: root), identity: SessionIdentity(), deviceName: { "SwiftHost" },
                            writable: writable, pin: pin, instanceId: "swifthost01")
        server = s
        return try! s.start(preferredPort: preferred)
    }

    struct Resp { let code: Int; let headers: [String: String]; let body: [UInt8] }

    private func http(_ port: Int, _ method: String, _ path: String, _ headers: [String: String] = [:], body: [UInt8]? = nil) throws -> Resp {
        let s = try TCPSocket.connect(host: "127.0.0.1", port: port, timeout: 5)
        defer { s.close() }
        s.setTimeout(10)
        var req = "\(method) \(path) HTTP/1.1\r\nHost: x\r\nConnection: close\r\n"
        for (k, v) in headers { req += "\(k): \(v)\r\n" }
        if let body { req += "Content-Length: \(body.count)\r\n" }
        req += "\r\n"
        try s.writeAll(Array(req.utf8) + (body ?? []))
        var raw: [UInt8] = []
        while true { let part = try s.recvSome(64 * 1024); if part.isEmpty { break }; raw += part }
        let sep: [UInt8] = [13, 10, 13, 10]
        var i = 0
        while i + 3 < raw.count && Array(raw[i..<i + 4]) != sep { i += 1 }
        let head = String(decoding: raw[0..<i], as: UTF8.self).components(separatedBy: "\r\n")
        var hs: [String: String] = [:]
        for l in head.dropFirst() { if let c = l.firstIndex(of: ":") { hs[l[..<c].lowercased()] = l[l.index(after: c)...].trimmingCharacters(in: .whitespaces) } }
        return Resp(code: Int(head[0].split(separator: " ")[1])!, headers: hs, body: Array(raw[min(i + 4, raw.count)...]))
    }

    private func md5(_ s: String) -> String {
        Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func testPreferredPortAndFallback() throws {
        let a = serve(preferred: 0)
        XCTAssertGreaterThan(a, 0)
        // second server asking for the first one's port falls back to an ephemeral one
        let s2 = VaultServer(fs: FileVaultFs(rootDir: root), identity: SessionIdentity(), deviceName: { "x" }, instanceId: "i2")
        defer { s2.close() }
        let b = try s2.start(preferredPort: a)
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(try http(b, "GET", "/api/info").code, 200)
    }

    func testPageInfoFsAndRange() throws {
        let p = serve()
        let page = try http(p, "GET", "/")
        XCTAssertEqual(page.code, 200)
        XCTAssertTrue(String(decoding: page.body, as: UTF8.self).contains("Vault"))
        XCTAssertEqual(page.body, Array(VaultPage.html.utf8))
        let info = String(decoding: try http(p, "GET", "/api/info").body, as: UTF8.self)
        XCTAssertEqual(info, "{\"name\":\"SwiftHost\",\"writable\":true,\"pin\":false}")
        let fs = String(decoding: try http(p, "GET", "/api/fs?path=%2Fdocs").body, as: UTF8.self)
        XCTAssertTrue(fs.contains("readme.txt")); XCTAssertTrue(fs.hasPrefix("{\"path\":\"/docs\""))
        XCTAssertFalse(String(decoding: try http(p, "GET", "/api/fs?path=/").body, as: UTF8.self).contains(".secret"))

        XCTAssertEqual(try http(p, "GET", "/raw/media/clip.bin").body, blob)
        let r = try http(p, "GET", "/raw/media/clip.bin", ["Range": "bytes=100-199"])
        XCTAssertEqual(r.code, 206)
        XCTAssertEqual(r.headers["content-range"], "bytes 100-199/\(blob.count)")
        XCTAssertEqual(r.headers["accept-ranges"], "bytes")
        XCTAssertEqual(r.body, Array(blob[100..<200]))
        let suf = try http(p, "GET", "/raw/media/clip.bin", ["Range": "bytes=-10"])
        XCTAssertEqual(suf.code, 206); XCTAssertEqual(suf.body, Array(blob[(blob.count - 10)...]))
        let open = try http(p, "GET", "/raw/media/clip.bin", ["Range": "bytes=299990-"])
        XCTAssertEqual(open.code, 206); XCTAssertEqual(open.body, Array(blob[299_990...]))
        XCTAssertEqual(try http(p, "GET", "/raw/media/clip.bin", ["Range": "bytes=999999999-"]).code, 416)
        // multi-range is unsupported → the server ignores Range (RFC 9110 §14.2) and sends the whole file
        let multi = try http(p, "GET", "/raw/media/clip.bin", ["Range": "bytes=1-2,5-6"])
        XCTAssertEqual(multi.code, 200); XCTAssertEqual(multi.body.count, blob.count)
        XCTAssertEqual(try http(p, "HEAD", "/raw/media/clip.bin").body.count, 0)
        XCTAssertEqual(try http(p, "GET", "/raw/..%2f..%2fetc%2fpasswd").code, 403)
        XCTAssertEqual(try http(p, "GET", "/nope").code, 404)
        XCTAssertEqual(try http(p, "GET", "/raw/%2esecret").code, 403)
    }

    func testPropfindShapes() throws {
        let p = serve()
        let r1 = try http(p, "PROPFIND", "/", ["Depth": "1"])
        XCTAssertEqual(r1.code, 207)
        let x1 = String(decoding: r1.body, as: UTF8.self)
        for want in ["multistatus", "/docs/", "/media/", "<D:collection/>", "pic.png", "<D:getcontenttype>image/png</D:getcontenttype>"] {
            XCTAssertTrue(x1.contains(want), want)
        }
        for notWant in [".secret", "readme.txt", "clip.bin"] { XCTAssertFalse(x1.contains(notWant), notWant) }
        let xm = String(decoding: try http(p, "PROPFIND", "/media/", ["Depth": "1"]).body, as: UTF8.self)
        XCTAssertTrue(xm.contains("<D:getcontentlength>\(blob.count)</D:getcontentlength>"))
        XCTAssertTrue(xm.contains("<D:getlastmodified>")); XCTAssertTrue(xm.contains(" GMT</D:getlastmodified>"))
        XCTAssertFalse(String(decoding: try http(p, "PROPFIND", "/docs", ["Depth": "0"]).body, as: UTF8.self).contains("readme.txt"))
        XCTAssertEqual(try http(p, "PROPFIND", "/", ["Depth": "infinity"]).code, 403)
        XCTAssertEqual(try http(p, "PROPFIND", "/nope").code, 404)
        XCTAssertEqual(try http(p, "PROPFIND", "/..%2f..%2fetc").code, 403)
        // percent-encoding + XML escaping of unusual names
        try Data([1]).write(to: root.appendingPathComponent("a b&c.txt"))
        let xs = String(decoding: try http(p, "PROPFIND", "/", ["Depth": "1"]).body, as: UTF8.self)
        XCTAssertTrue(xs.contains("<D:href>/a%20b%26c.txt</D:href>")); XCTAssertTrue(xs.contains("a b&amp;c.txt"))
        XCTAssertEqual(try http(p, "OPTIONS", "/").headers["dav"], "1,2")
    }

    func testReadOnlyBlocksWritesButNotReads() throws {
        let p = serve(writable: false)
        XCTAssertEqual(try http(p, "PUT", "/x.txt", body: [1]).code, 403)
        XCTAssertEqual(try http(p, "MKCOL", "/d").code, 403)
        XCTAssertEqual(try http(p, "DELETE", "/pic.png").code, 403)
        XCTAssertEqual(try http(p, "MOVE", "/pic.png", ["Destination": "/m.png"]).code, 403)
        XCTAssertEqual(try http(p, "COPY", "/pic.png", ["Destination": "/c.png"]).code, 403)
        XCTAssertEqual(try http(p, "LOCK", "/pic.png").code, 403)
        XCTAssertEqual(try http(p, "POST", "/upload", ["Content-Type": "multipart/form-data; boundary=b"], body: Array("--b--\r\n".utf8)).code, 403)
        XCTAssertEqual(try http(p, "PROPFIND", "/", ["Depth": "1"]).code, 207)
        XCTAssertEqual(try http(p, "GET", "/pic.png").code, 200)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("pic.png").path))
    }

    func testWritableVerbsAndUpload() throws {
        let p = serve()
        XCTAssertEqual(try http(p, "PUT", "/new.txt", body: Array("v1".utf8)).code, 201)
        XCTAssertEqual(try http(p, "PUT", "/new.txt", body: Array("v2".utf8)).code, 204)
        XCTAssertEqual(try http(p, "GET", "/new.txt").body, Array("v2".utf8))
        XCTAssertEqual(try http(p, "PUT", "/ghost/f.txt", body: [1]).code, 409)
        XCTAssertEqual(try http(p, "MKCOL", "/fresh").code, 201)
        XCTAssertEqual(try http(p, "MKCOL", "/fresh").code, 405)
        XCTAssertEqual(try http(p, "MOVE", "/new.txt", ["Destination": "http://h:9/fresh/moved%20x.txt"]).code, 201)
        XCTAssertEqual(try http(p, "GET", "/fresh/moved%20x.txt").body, Array("v2".utf8))
        XCTAssertEqual(try http(p, "COPY", "/fresh/moved%20x.txt", ["Destination": "/c.txt", "Overwrite": "F"]).code, 201)
        XCTAssertEqual(try http(p, "COPY", "/fresh/moved%20x.txt", ["Destination": "/c.txt", "Overwrite": "F"]).code, 412)
        XCTAssertEqual(try http(p, "COPY", "/fresh/moved%20x.txt", ["Destination": "/c.txt"]).code, 204)
        XCTAssertEqual(try http(p, "DELETE", "/fresh/").code, 204)
        XCTAssertEqual(try http(p, "DELETE", "/fresh/").code, 404)
        let lock = try http(p, "LOCK", "/pic.png", body: [])
        XCTAssertEqual(lock.code, 200); XCTAssertTrue(lock.headers["lock-token"]!.contains("opaquelocktoken"))
        XCTAssertEqual(try http(p, "UNLOCK", "/pic.png").code, 204)

        // multipart upload, two parts, binary data containing the boundary-ish bytes
        let boundary = "----xdboundary"
        let data1 = (0..<150_000).map { UInt8($0 % 61) }
        let data2 = Array("\r\n--not-the-boundary\r\n".utf8) + [0, 1, 2]
        var body = [UInt8]()
        func w(_ s: String) { body += Array(s.utf8) }
        w("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"); body += data1
        w("\r\n--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename*=UTF-8''%E1%83%A4%20x.bin\r\n\r\n"); body += data2
        w("\r\n--\(boundary)--\r\n")
        let up = try http(p, "POST", "/upload?path=%2Fdocs", ["Content-Type": "multipart/form-data; boundary=\(boundary)"], body: body)
        XCTAssertEqual(up.code, 200, String(decoding: up.body, as: UTF8.self))
        XCTAssertTrue(String(decoding: up.body, as: UTF8.self).hasPrefix("{\"ok\":true,\"saved\":["))
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("docs/a.bin")), Data(data1))
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("docs/ფ x.bin")), Data(data2))
    }

    func testDigestAuthAndPinHeader() throws {
        let p = serve(pin: "4821")
        let chal = try http(p, "PROPFIND", "/", ["Depth": "1"])
        XCTAssertEqual(chal.code, 401)
        let wa = chal.headers["www-authenticate"]!
        XCTAssertTrue(wa.hasPrefix("Digest realm=\"crossdrop\"")); XCTAssertTrue(wa.contains("qop=\"auth\""))
        let nonce = wa.components(separatedBy: "nonce=\"")[1].components(separatedBy: "\"")[0]
        func digest(_ method: String, _ uri: String, pin: String, nc: String = "00000001") -> String {
            let cnonce = "abcdef0123456789"
            let resp = md5("\(md5("drive:crossdrop:\(pin)")):\(nonce):\(nc):\(cnonce):auth:\(md5("\(method):\(uri)"))")
            return "Digest username=\"drive\", realm=\"crossdrop\", uri=\"\(uri)\", nonce=\"\(nonce)\", nc=\(nc), cnonce=\"\(cnonce)\", qop=auth, response=\"\(resp)\""
        }
        XCTAssertEqual(try http(p, "PROPFIND", "/", ["Depth": "1", "Authorization": digest("PROPFIND", "/", pin: "4821")]).code, 207)
        XCTAssertEqual(try http(p, "PROPFIND", "/", ["Depth": "1", "Authorization": digest("PROPFIND", "/", pin: "9999")]).code, 401)
        XCTAssertEqual(try http(p, "GET", "/pic.png", ["Authorization": digest("GET", "/pic.png", pin: "4821")]).code, 200)
        XCTAssertEqual(try http(p, "MKCOL", "/anon").code, 401)
        XCTAssertEqual(try http(p, "MKCOL", "/dd", ["Authorization": digest("MKCOL", "/dd", pin: "4821")]).code, 201)
        XCTAssertEqual(try http(p, "GET", "/pic.png", ["X-Vault-Pin": "4821"]).code, 200)
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/").code, 401)
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=4821").code, 200)
        XCTAssertEqual(try http(p, "GET", "/api/info").code, 200)
    }

    func testPinLockoutAfterFiveMisses() throws {
        let p = serve(pin: "7777")
        for i in 0..<5 { XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=000\(i)").code, 401, "miss \(i)") }
        // locked: even the correct PIN is refused with 429, across surfaces (header and Digest paths)
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=7777").code, 429)
        XCTAssertEqual(try http(p, "GET", "/pic.png", ["X-Vault-Pin": "7777"]).code, 429)
    }

    func testPinSuccessResetsStrikeCounter() throws {
        let p = serve(pin: "7777")
        for _ in 0..<4 { XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=0000").code, 401) }
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=7777").code, 200)    // resets
        for _ in 0..<4 { XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=0000").code, 401) }
        XCTAssertEqual(try http(p, "GET", "/api/fs?path=/&pin=7777").code, 200)
    }

    // MARK: native session (Swift ↔ Swift)

    private func client(_ port: Int, pin: String? = nil) throws -> VaultClient {
        let c = VaultClient(host: "127.0.0.1", port: port, identity: SessionIdentity(), deviceName: "SwiftClient", instanceId: "swc01")
        try c.connect(pin: pin)
        return c
    }

    func testNativeLsStatPullPush() throws {
        let p = serve()
        let c = try client(p)
        defer { c.close() }
        XCTAssertEqual(c.peerName, "SwiftHost")
        XCTAssertEqual(Set(try c.ls("/").map(\.name)), ["docs", "media", "pic.png"])
        let attr = try c.stat("media/clip.bin")
        XCTAssertEqual(attr.size, Int64(blob.count))
        XCTAssertEqual(attr.hash, Blake3.hex(Blake3.hash(blob)))
        var got = [UInt8]()
        let done = try c.pull("media/clip.bin", out: { got += $0 })
        XCTAssertEqual(got, blob); XCTAssertEqual(done.hash, attr.hash)
        var piece = [UInt8]()
        try c.pull("media/clip.bin", offset: 100_000, length: 123_456, out: { piece += $0 })
        XCTAssertEqual(piece, Array(blob[100_000..<223_456]))

        let src = FileManager.default.temporaryDirectory.appendingPathComponent("push-\(UUID().uuidString).bin")
        defer { try? FileManager.default.removeItem(at: src) }
        let up = (0..<700_000).map { UInt8($0 % 97) }
        try Data(up).write(to: src)
        let d1 = try c.push("uploads/photo.bin", fileURL: src)
        XCTAssertEqual(d1.path, "uploads/photo.bin")
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("uploads/photo.bin")), Data(up))
        XCTAssertEqual(try c.push("uploads/photo.bin", fileURL: src).path, "uploads/photo (2).bin")
        // staging is clean afterwards
        let staging = (try? FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent(".staging").path)) ?? []
        XCTAssertEqual(staging, [])
    }

    func testNativeJailAndReadOnlyAndPin() throws {
        let p = serve(writable: false)
        let c = try client(p)
        defer { c.close() }
        for bad in ["../x", "..\\..\\escape", ".staging/x", "/etc/passwd", ".secret"] {
            XCTAssertThrowsError(try c.ls(bad), bad); XCTAssertThrowsError(try c.stat(bad), bad)
        }
        let src = FileManager.default.temporaryDirectory.appendingPathComponent("ro-\(UUID().uuidString).bin")
        try Data([1, 2, 3]).write(to: src); defer { try? FileManager.default.removeItem(at: src) }
        XCTAssertThrowsError(try c.push("x.bin", fileURL: src)) { XCTAssertEqual(($0 as! VaultError).code, 403) }
        XCTAssertFalse(try c.ls("/").isEmpty)                          // session still usable after an error

        server?.close()
        let p2 = serve(pin: "4321")
        XCTAssertThrowsError(try VaultClient(host: "127.0.0.1", port: p2, identity: SessionIdentity(), deviceName: "c", instanceId: "i").connect()) {
            XCTAssertEqual(($0 as! VaultError).code, 401)
        }
        XCTAssertThrowsError(try VaultClient(host: "127.0.0.1", port: p2, identity: SessionIdentity(), deviceName: "c", instanceId: "i").connect(pin: "0000")) {
            XCTAssertEqual(($0 as! VaultError).code, 401)
        }
        let good = try client(p2, pin: "4321")
        XCTAssertTrue(good.requiresPin)
        XCTAssertFalse(try good.ls("/").isEmpty)
        good.close()
    }

    func testNativeAndHttpShareOnePortAndCountersMove() throws {
        let p = serve()
        let c = try client(p)
        defer { c.close() }
        XCTAssertEqual(try http(p, "GET", "/api/info").code, 200)
        XCTAssertEqual(server?.activeClients, 1)
        XCTAssertEqual(server?.connectedPeers, ["SwiftClient"])
        _ = try c.pull("pic.png", out: { _ in })
        XCTAssertGreaterThanOrEqual(server?.bytesOut ?? 0, 1024)
        XCTAssertEqual(try http(p, "GET", "/").code, 200)              // still alive after a native session
    }
}
