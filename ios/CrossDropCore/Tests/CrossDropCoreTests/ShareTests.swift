import XCTest
@testable import CrossDropCore

final class ShareTests: XCTestCase {
    private var dir: URL!
    private var server: HttpShareServer!
    private let token = "00112233445566778899aabbccddeeff"
    private var a: Data!, b: Data!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var s: UInt64 = 42
        func rnd(_ n: Int) -> Data { Data((0..<n).map { _ in s = s &* 6364136223846793005 &+ 1; return UInt8(truncatingIfNeeded: s >> 33) }) }
        a = rnd(700_000); b = Data("héllo wörld\n".utf8)
        try a.write(to: dir.appendingPathComponent("a.bin"))
        try b.write(to: dir.appendingPathComponent("note \"q\".txt"))
        let files = [
            OutgoingFile(meta: FileMeta(name: "a.bin", size: Int64(a.count), mime: "application/octet-stream"), url: dir.appendingPathComponent("a.bin")),
            OutgoingFile(meta: FileMeta(name: "note \"q\".txt", size: Int64(b.count), mime: nil), url: dir.appendingPathComponent("note \"q\".txt")),
        ]
        server = HttpShareServer(files: files, deviceName: "Test Phone", token: token)
        try server.start()
    }

    override func tearDown() { server.close() }

    private func get(_ path: String, method: String = "GET") throws -> (Int, [String: String], Data) {
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(server.port)\(path)")!)
        req.httpMethod = method
        let sem = DispatchSemaphore(value: 0)
        var out: (Int, [String: String], Data)?
        // No redirect following: the 301 itself is under test.
        final class NoRedirect: NSObject, URLSessionTaskDelegate {
            func urlSession(_ s: URLSession, task: URLSessionTask, willPerformHTTPRedirection r: HTTPURLResponse, newRequest: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
        }
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        session.dataTask(with: req) { data, resp, _ in
            if let h = resp as? HTTPURLResponse {
                var headers: [String: String] = [:]
                for (k, v) in h.allHeaderFields { headers["\(k)".lowercased()] = "\(v)" }
                out = (h.statusCode, headers, data ?? Data())
            }
            sem.signal()
        }.resume()
        XCTAssertEqual(sem.wait(timeout: .now() + 30), .success)
        return try XCTUnwrap(out)
    }

    func testHtmlPageAndRedirect() throws {
        let (code, h, body) = try get("/\(token)/")
        XCTAssertEqual(code, 200)
        XCTAssertTrue(h["content-type"]?.hasPrefix("text/html") == true)
        XCTAssertEqual(body, Data(ShareHtml.page.utf8))
        let (rc, rh, _) = try get("/\(token)")
        XCTAssertEqual(rc, 301); XCTAssertEqual(rh["location"], "/\(token)/")
    }

    func testManifestMatchesKotlinShape() throws {
        let (code, _, body) = try get("/\(token)/manifest.json")
        XCTAssertEqual(code, 200)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(obj["device"] as? String, "Test Phone")
        XCTAssertEqual(obj["zip"] as? Bool, true)
        let files = try XCTUnwrap(obj["files"] as? [[String: Any]])
        XCTAssertEqual(files.count, 2)
        XCTAssertEqual(files[0]["name"] as? String, "a.bin")
        XCTAssertEqual(files[0]["size"] as? Int, a.count)
        XCTAssertEqual(files[0]["i"] as? Int, 0)
        XCTAssertTrue(files[1]["mime"] is NSNull)          // explicit null, as kotlinx.serialization emits
    }

    func testFileDownloadHeadersAndBytes() throws {
        let (code, h, body) = try get("/\(token)/f/0")
        XCTAssertEqual(code, 200); XCTAssertEqual(body, a)
        XCTAssertEqual(h["content-length"], "\(a.count)")
        XCTAssertTrue(h["content-disposition"]?.contains("filename=\"a.bin\"") == true)
        let (_, h1, body1) = try get("/\(token)/f/1")
        XCTAssertEqual(body1, b)
        XCTAssertTrue(h1["content-disposition"]?.contains("filename=\"note _q_.txt\"") == true)   // quotes neutralised
        XCTAssertTrue(h1["content-disposition"]?.contains("filename*=UTF-8''note%20%22q%22.txt") == true)
        XCTAssertEqual(h1["content-type"], "application/octet-stream")
    }

    func testHeadAndBadIndexAndWrongToken() throws {
        let (hc, hh, hb) = try get("/\(token)/f/0", method: "HEAD")
        XCTAssertEqual(hc, 200); XCTAssertEqual(hh["content-length"], "\(a.count)"); XCTAssertTrue(hb.isEmpty)
        XCTAssertEqual(try get("/\(token)/f/9").0, 404)
        XCTAssertEqual(try get("/\(token)/f/x").0, 404)
        XCTAssertEqual(try get("/deadbeef/").0, 404)
        XCTAssertEqual(try get("/").0, 404)
        XCTAssertEqual(try get("/\(token)/manifest.json", method: "POST").0, 405)
    }

    func testStatusCounters() throws {
        _ = try get("/\(token)/f/0")
        let (_, _, body) = try get("/\(token)/status.json")
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Int])
        XCTAssertEqual(obj["started"], 1); XCTAssertEqual(obj["done"], 1); XCTAssertEqual(obj["bytes"], a.count)
    }

    func testZipIsValidStoredArchive() throws {
        let (code, _, body) = try get("/\(token)/all.zip")
        XCTAssertEqual(code, 200)
        let zipURL = dir.appendingPathComponent("out.zip")
        try body.write(to: zipURL)

        func run(_ args: [String]) throws -> (Int32, String) {
            let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/unzip"); p.arguments = args
            let pipe = Pipe(); p.standardOutput = pipe; p.standardError = pipe
            try p.run(); p.waitUntilExit()
            return (p.terminationStatus, String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self))
        }
        let (rc, log) = try run(["-t", zipURL.path])
        XCTAssertEqual(rc, 0, log)
        XCTAssertTrue(log.contains("No errors detected"), log)

        let outDir = dir.appendingPathComponent("x")
        XCTAssertEqual(try run(["-q", zipURL.path, "-d", outDir.path]).0, 0)
        XCTAssertEqual(try Data(contentsOf: outDir.appendingPathComponent("a.bin")), a)
        XCTAssertEqual(try Data(contentsOf: outDir.appendingPathComponent("note \"q\".txt")), b)
    }

    func testDedupeAndDecodeHelpers() {
        XCTAssertEqual(HttpShareServer.dedupeNames(["x/a.txt", "a.txt", "a.txt", ""]), ["a.txt", "a (1).txt", "a (2).txt", "file"])
        XCTAssertEqual(HttpShareServer.urlDecode("/a%20b%zz%C3%A9"), "/a b%zzé")
    }

    func testPhotosAreServedInlineOnlyForAllowListedTypes() throws {
        server.close()
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])
        let svg = Data("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".utf8)
        func file(_ name: String, _ mime: String?, _ data: Data) throws -> OutgoingFile {
            let url = dir.appendingPathComponent(name)
            try data.write(to: url)
            return OutgoingFile(meta: FileMeta(name: name, size: Int64(data.count), mime: mime), url: url)
        }
        server = HttpShareServer(files: [
            try file("beach.JPG", nil, jpeg),                  // no MIME: the extension decides
            try file("drawing.svg", "image/svg+xml", svg),     // an image type that can carry script
            try file("trick.jpg", "text/html", svg),           // the sender's MIME is never echoed for a preview
        ], deviceName: "Test Phone", token: token)
        try server.start()

        let (code, h, body) = try get("/\(token)/v/0")
        XCTAssertEqual(code, 200); XCTAssertEqual(body, jpeg)
        XCTAssertEqual(h["content-type"], "image/jpeg")
        XCTAssertTrue(h["content-disposition"]?.hasPrefix("inline;") == true)
        XCTAssertEqual(h["x-content-type-options"], "nosniff")
        XCTAssertEqual(h["content-security-policy"], "sandbox")
        XCTAssertEqual(try get("/\(token)/v/2").1["content-type"], "image/jpeg")
        XCTAssertEqual(try get("/\(token)/v/1").0, 404)
        XCTAssertEqual(try get("/\(token)/v/9").0, 404)
        XCTAssertTrue(try get("/\(token)/f/0").1["content-disposition"]?.hasPrefix("attachment;") == true)

        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: try get("/\(token)/manifest.json").2) as? [String: Any])
        let files = try XCTUnwrap(manifest["files"] as? [[String: Any]])
        XCTAssertEqual(files.map { $0["view"] as? Bool }, [true, false, true])

        XCTAssertEqual(HttpShareServer.previewType(name: "IMG_1.HEIC", mime: "application/octet-stream"), "image/heic")
        XCTAssertEqual(HttpShareServer.previewType(name: "noext", mime: "IMAGE/PNG; x=y"), "image/png")
        XCTAssertNil(HttpShareServer.previewType(name: "jpg", mime: nil))
    }

    func testLocalIpIsUsableIPv4() {
        if let ip = LocalIp.wifiIPv4() {
            XCTAssertEqual(ip.split(separator: ".").count, 4)
            XCTAssertFalse(ip.hasPrefix("127.")); XCTAssertFalse(ip.hasPrefix("169.254."))
        }
    }
}
