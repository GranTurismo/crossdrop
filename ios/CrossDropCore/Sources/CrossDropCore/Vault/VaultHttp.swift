import Foundation
import CryptoKit

/// Zero-install HTTP/1.1 surface for the vault — same socket as the AEAD protocol, routed here when
/// the first byte looks like an HTTP verb. Port of `core/vault/VaultHttp.kt`:
///
///  - `GET /`                 embedded vault.html explorer (PIN-free; the page asks)
///  - `GET /api/info`         `{name, writable, pin}`
///  - `GET /api/fs?path=`     directory listing JSON; `GET /api/stat?path=` single entry
///  - `GET|HEAD /raw/<path>`  file stream, single-range `Range:` → 206
///  - `POST /upload?path=`    multipart/form-data → staging → atomic commit
///  - WebDAV at `/`: OPTIONS, PROPFIND (Depth 0/1), GET/HEAD, PUT/MKCOL/DELETE/MOVE/COPY (writable
///    only), LOCK/UNLOCK stubs.
///
/// `auth=pin`: JSON API + browser paths accept `X-Vault-Pin`/`?pin=`; DAV methods and direct GETs
/// additionally accept HTTP Digest (realm `crossdrop`). Every request is one-shot (`Connection: close`).
final class VaultHttp {
    private let fs: VaultFs
    private let writable: Bool
    private let pin: String?
    private let deviceName: () -> String
    private let onBytesServed: (Int64) -> Void
    private let throttle: PinThrottle

    init(fs: VaultFs, writable: Bool, pin: String?, deviceName: @escaping () -> String,
         throttle: PinThrottle = PinThrottle(), onBytesServed: @escaping (Int64) -> Void = { _ in }) {
        self.fs = fs; self.writable = writable; self.pin = pin
        self.deviceName = deviceName; self.onBytesServed = onBytesServed; self.throttle = throttle
    }

    private struct Request {
        let method: String
        let target: String
        let rawPath: String
        let params: [String: String]
        let headers: [String: String]
        let ip: String
    }

    private enum Fail: Error { case authRequired, badRequest(String) }

    static let maxPinStrikes = PinThrottle.maxStrikes
    static let pinLockoutMs = PinThrottle.lockoutMs
    /// A client gets this long to deliver a complete request head — slow-loris protection.
    static var headDeadline: TimeInterval = 15      // tests shorten these
    static var headReadTimeout: TimeInterval = 5
    private static let bodyIdle: TimeInterval = 45
    private static let maxHead = 16 * 1024
    private static let pushback = 128 * 1024
    private static let maxFilesPerUpload = 256
    /// HTTP staging ids live in their own range — no tid collisions with native pushes.
    private static let httpStageBase: Int64 = 1_000_000_000
    private static let davRealm = "crossdrop"

    // MARK: entry point

    func handle(socket: TCPSocket) {
        let conn = HttpConn(socket: socket)
        var current: Request?
        do {
            guard let head = try readHead(conn) else { return }
            let (method, rawTarget) = head.request
            let q = rawTarget.firstIndex(of: "?")
            let rawPath = q.map { String(rawTarget[..<$0]) } ?? rawTarget
            let query = q.map { String(rawTarget[rawTarget.index(after: $0)...]) } ?? ""
            let req = Request(method: method, target: rawTarget, rawPath: rawPath, params: parseQuery(query),
                              headers: head.headers, ip: socket.peerAddress)
            current = req
            try route(conn, req)
        } catch let e as VaultError {
            try? respondJson(conn, e.code, errorJson(e.code, e.message))
        } catch Fail.authRequired {
            try? authChallenge(conn, current)
        } catch Fail.badRequest(let m) {
            try? respondJson(conn, 400, errorJson(400, m))
        } catch {
            try? respondJson(conn, 500, errorJson(500, "internal error"))
        }
        lingerBeforeClose(conn)
        // The socket itself is closed by the owning connection thread (after it deregisters it) —
        // closing it here too would leave a window where a recycled fd number could be shut down.
    }

    /// Closing a socket that still has unread request bytes (a rejected upload) makes the kernel send
    /// RST, and the client then reports "connection reset" instead of showing our 400/413. Half-close
    /// our side so the response is delivered, then swallow a bounded amount of the leftover body.
    private func lingerBeforeClose(_ c: HttpConn) {
        Darwin.shutdown(c.socket.fd, SHUT_WR)
        c.socket.setTimeout(1)
        var drained = 0
        while drained < 256 * 1024, let part = try? c.read(32 * 1024), !part.isEmpty { drained += part.count }
    }

    private func route(_ c: HttpConn, _ r: Request) throws {
        let method = r.method, rawPath = r.rawPath, headers = r.headers
        try guardBrowserOrigin(r)
        switch true {
        case method == "OPTIONS": try respondOptions(c)
        case method == "GET" && (rawPath == "/" || rawPath == "/index.html"): try respondPage(c)
        case method == "GET" && rawPath == "/api/info": try respondJson(c, 200, infoJson())
        case method == "GET" && rawPath == "/api/fs":
            try requirePin(r)
            try respondJson(c, 200, fsJson(r.params["path"] ?? "/"))
        case method == "GET" && rawPath == "/api/stat":
            try requirePin(r)
            try respondJson(c, 200, attrJson(r.params["path"] ?? "/"))
        case (method == "GET" || method == "HEAD") && rawPath.hasPrefix("/raw/"):
            try requirePin(r)
            try serveRaw(c, urlDecode(String(rawPath.dropFirst(5))), headers["range"],
                         headOnly: method == "HEAD", download: r.params["dl"] != nil)
        case method == "POST" && rawPath == "/upload":
            try requirePin(r)
            if !writable { throw VaultError(403, "vault is read-only") }
            try continue100(c, headers)
            try handleUpload(c, headers, r.params["path"] ?? "/")

        // WebDAV
        case method == "PROPFIND":
            if !(try authorized(r)) { try authChallenge(c, r); return }
            drainBody(c, headers)
            try handlePropfind(c, urlDecode(rawPath), headers["depth"])
        case method == "GET" || method == "HEAD":
            if !(try authorized(r)) { try authChallenge(c, r); return }
            try serveDavPath(c, urlDecode(rawPath), headers["range"], headOnly: method == "HEAD")
        case method == "PUT":
            if !writable { throw VaultError(403, "vault is read-only") }
            if !(try authorized(r)) { try authChallenge(c, r); return }
            try continue100(c, headers)
            try handlePut(c, urlDecode(rawPath), headers)
        case method == "MKCOL":
            try requireWritable(r)
            _ = try fs.mkdir(urlDecode(rawPath))
            try respond(c, 201)
        case method == "DELETE":
            try requireWritable(r)
            try fs.delete(urlDecode(rawPath))
            try respond(c, 204)
        case method == "MOVE" || method == "COPY":
            try requireWritable(r)
            guard let destHeader = headers["destination"] else { throw VaultError(400, "missing Destination") }
            let dst = destPath(destHeader)
            let overwrite = headers["overwrite"]?.uppercased() != "F"
            let src = urlDecode(rawPath)
            let existed = (try? fs.stat(dst)) != nil
            if method == "MOVE" { try fs.move(src, dst, overwrite: overwrite) }
            else { try fs.copy(src, dst, overwrite: overwrite) }
            try respond(c, existed ? 204 : 201)
        case method == "LOCK":
            try requireWritable(r)
            drainBody(c, headers)
            try respondLock(c)
        case method == "UNLOCK":
            try requireWritable(r)
            try respond(c, 204)
        default:
            throw VaultError(404, "not found")
        }
    }

    // MARK: cross-site protection

    /// Reachable by any web page the user has open on a device on the same network: a page can
    /// POST a multipart form to `http://<phone>:8080/upload` (a "simple" cross-origin request — no
    /// preflight) or rebind its own DNS name to the phone's address. So:
    ///  - `Host` must be an IP literal, `localhost`, a single-label or `.local`/`.lan` name — never
    ///    an arbitrary public name (DNS rebinding);
    ///  - state-changing methods that carry an `Origin` must be same-origin (CSRF).
    /// Native WebDAV/curl clients send no `Origin` and are unaffected.
    private func guardBrowserOrigin(_ r: Request) throws {
        let host = r.headers["host"]
        if let host, !Self.hostAllowed(host) { throw VaultError(403, "unexpected Host header") }
        guard let origin = r.headers["origin"] else { return }
        let safe: Set<String> = ["GET", "HEAD", "OPTIONS", "PROPFIND"]
        if safe.contains(r.method) { return }
        guard let host, let auth = Self.originAuthority(origin), auth.lowercased() == host.lowercased() else {
            throw VaultError(403, "cross-site request refused")
        }
    }

    /// `scheme://authority` → authority (nil for "null" or malformed).
    static func originAuthority(_ origin: String) -> String? {
        guard let r = origin.range(of: "://") else { return nil }
        let rest = origin[r.upperBound...]
        let auth = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        return auth.isEmpty ? nil : auth
    }

    static func hostAllowed(_ header: String) -> Bool {
        var h = header.trimmingCharacters(in: .whitespaces).lowercased()
        if h.hasPrefix("[") { return h.contains("]") }                       // IPv6 literal
        if let colon = h.lastIndex(of: ":") { h = String(h[..<colon]) }       // strip :port
        if h.hasSuffix(".") { h.removeLast() }
        if h.isEmpty { return false }
        let parts = h.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) { return true }   // IPv4
        if !h.contains(".") { return true }                                  // single label (NetBIOS/hostname)
        return h == "localhost" || h.hasSuffix(".local") || h.hasSuffix(".lan") ||
            h.hasSuffix(".localdomain") || h.hasSuffix(".home.arpa")
    }

    // MARK: brute-force throttle + auth

    /// Records a credential check: lockout after repeated misses (see `PinThrottle`).
    private func recordAttempt(_ ip: String, ok: Bool) throws -> Bool {
        try throttle.record(ip, ok: ok)
    }

    private func pinMatches(_ sent: String, _ p: String, _ ip: String) throws -> Bool {
        try recordAttempt(ip, ok: VaultWire.constantTimeEqual(Array(sent.utf8), Array(p.utf8)))
    }

    private func requirePin(_ r: Request) throws {
        guard let p = pin else { return }
        let sent = r.headers["x-vault-pin"] ?? r.params["pin"]
        guard let sent, try pinMatches(sent, p, r.ip) else { throw VaultError(401, "pin required") }
    }

    /// Digest nonces are stateless: `<8-byte unix time hex><HMAC-SHA256(secret, time)>` — verifiable
    /// without a table, and they expire (`nonceLifetime`), so a captured Authorization header stops
    /// working after half a day instead of forever (long enough that a mounted Finder/Explorer
    /// session never notices; expired-but-genuine nonces get `stale=true` so clients retry silently).
    /// The PIN itself is only 4 digits and Digest/MD5 over plain HTTP can be cracked offline by anyone
    /// who can sniff the LAN — expiry limits replay, it is not a confidentiality guarantee.
    private static let nonceLifetime: Int64 = 12 * 3600
    private let nonceKey = SymmetricKey(size: .bits256)

    func makeNonce(at date: Date = Date()) -> String {
        let ts = hex(withUnsafeBytes(of: UInt64(date.timeIntervalSince1970).bigEndian) { Array($0) })
        let mac = HMAC<SHA256>.authenticationCode(for: Data(ts.utf8), using: nonceKey)
        return ts + hex(Array(mac.prefix(16)))
    }

    enum NonceState { case valid, stale, forged }

    func checkNonce(_ nonce: String) -> NonceState {
        guard nonce.count == 16 + 32 else { return .forged }
        let ts = String(nonce.prefix(16))
        let want = hex(Array(HMAC<SHA256>.authenticationCode(for: Data(ts.utf8), using: nonceKey).prefix(16)))
        guard VaultWire.constantTimeEqual(Array(nonce.suffix(32).lowercased().utf8), Array(want.utf8)),
              let t = UInt64(ts, radix: 16) else { return .forged }
        let age = Int64(Date().timeIntervalSince1970) - Int64(clamping: t)
        return age >= -5 && age <= Self.nonceLifetime ? .valid : .stale
    }

    private func authorized(_ r: Request) throws -> Bool {
        guard let p = pin else { return true }
        if let sent = r.headers["x-vault-pin"] ?? r.params["pin"] { return try pinMatches(sent, p, r.ip) }
        return try checkDigest(r.headers["authorization"], r, p)
    }

    /// Write-verb gate: read-only 403s before auth, then Digest/pin applies.
    private func requireWritable(_ r: Request) throws {
        if !writable { throw VaultError(403, "vault is read-only") }
        if !(try authorized(r)) { throw Fail.authRequired }
    }

    private static let digestKV = try! NSRegularExpression(pattern: #"(\w+)\s*=\s*(?:"([^"]*)"|[^,\s]+)"#)

    private func digestParams(_ auth: String?) -> [String: String]? {
        guard let auth, auth.hasPrefix("Digest ") else { return nil }
        let body = String(auth.dropFirst(7))
        let ns = body as NSString
        var kv: [String: String] = [:]
        for m in Self.digestKV.matches(in: body, range: NSRange(location: 0, length: ns.length)) {
            let key = ns.substring(with: m.range(at: 1)).lowercased()
            let quoted = m.range(at: 2).location != NSNotFound ? ns.substring(with: m.range(at: 2)) : ""
            if !quoted.isEmpty { kv[key] = quoted; continue }
            let whole = ns.substring(with: m.range)
            guard let eq = whole.firstIndex(of: "=") else { continue }
            var v = String(whole[whole.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if v.count >= 2, v.hasPrefix("\""), v.hasSuffix("\"") { v = String(v.dropFirst().dropLast()) }
            kv[key] = v
        }
        return kv
    }

    /// Path part of a request-URI / `uri=` value (absolute-form tolerated), percent-decoded.
    private func normalisedPath(_ uri: String) -> String {
        var p = uri
        if let scheme = p.range(of: "://") {
            if let slash = p[scheme.upperBound...].firstIndex(of: "/") { p = String(p[slash...]) } else { p = "/" }
        }
        if let q = p.firstIndex(of: "?") { p = String(p[..<q]) }
        return urlDecode(p)
    }

    private func checkDigest(_ auth: String?, _ r: Request, _ p: String) throws -> Bool {
        guard let kv = digestParams(auth) else { return false }
        guard let user = kv["username"], let uri = kv["uri"], let nonce = kv["nonce"], let response = kv["response"] else { return false }
        // Unknown/expired nonce → plain 401 (the challenge carries a fresh one); no strike, the
        // client simply hasn't seen our latest challenge.
        guard checkNonce(nonce) == .valid else { return false }
        // The digest signs `uri`, so it must be the resource actually being requested — otherwise a
        // captured header for one URL could be replayed against another handler path.
        guard normalisedPath(uri) == normalisedPath(r.target) else { return false }
        let ha1 = md5Hex("\(user):\(Self.davRealm):\(p)")
        let ha2 = md5Hex("\(r.method):\(uri)")
        let expected: String
        if let qop = kv["qop"] {
            expected = md5Hex("\(ha1):\(nonce):\(kv["nc"] ?? "null"):\(kv["cnonce"] ?? "null"):\(qop):\(ha2)")
        } else {
            expected = md5Hex("\(ha1):\(nonce):\(ha2)")
        }
        return try recordAttempt(r.ip, ok: VaultWire.constantTimeEqual(Array(expected.utf8), Array(response.lowercased().utf8)))
    }

    private func md5Hex(_ s: String) -> String {
        let data = s.data(using: .isoLatin1, allowLossyConversion: true) ?? Data()
        return hex([UInt8](Insecure.MD5.hash(data: data)))
    }

    private func authChallenge(_ c: HttpConn, _ r: Request?) throws {
        var stale = false
        if let nonce = digestParams(r?.headers["authorization"])?["nonce"], checkNonce(nonce) == .stale { stale = true }
        try respond(c, 401, [
            ("WWW-Authenticate", "Digest realm=\"\(Self.davRealm)\", nonce=\"\(makeNonce())\", qop=\"auth\", algorithm=MD5" + (stale ? ", stale=true" : "")),
            ("Content-Type", "text/plain; charset=utf-8"),
        ], contentLength: 0)
    }

    // MARK: WebDAV

    private func respondOptions(_ c: HttpConn) throws {
        try respond(c, 204, [
            ("DAV", "1,2"),
            ("Allow", "OPTIONS,GET,HEAD,POST,PROPFIND,PUT,DELETE,MKCOL,COPY,MOVE,LOCK,UNLOCK"),
            ("MS-Author-Via", "DAV"),
        ], contentLength: 0)
    }

    /// `GET /some/dir` → the explorer page; `GET /file` → bytes.
    private func serveDavPath(_ c: HttpConn, _ path: String, _ range: String?, headOnly: Bool) throws {
        let e: VaultEntry
        do { e = try fs.stat(path) } catch let err as VaultError {
            if err.code == 404 && headOnly { try respond(c, 404, contentLength: 0); return }
            throw err
        }
        if e.isDir {
            if headOnly { try respond(c, 200, [("Content-Type", "text/html")], contentLength: 0); return }
            try respondPage(c)
        } else {
            try serveRaw(c, path, range, headOnly: headOnly, download: false)
        }
    }

    private func handlePropfind(_ c: HttpConn, _ path: String, _ depth: String?) throws {
        if depth == "infinity" { throw VaultError(403, "depth infinity not supported") }
        let e = try fs.stat(path)
        var sb = #"<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">"#
        appendResponse(&sb, try davHref(path, e.isDir), e)
        if e.isDir && depth != "0" {
            let parent = try davHref(path, true)
            for child in try fs.list(path) {
                appendResponse(&sb, parent + urlEncode(child.name) + (child.isDir ? "/" : ""), child)
            }
        }
        sb += "</D:multistatus>"
        let body = Array(sb.utf8)
        try respond(c, 207, [("Content-Type", "application/xml; charset=utf-8"), ("Content-Length", "\(body.count)")])
        try c.write(body)
    }

    /// href form of a jail path: percent-encoded per segment, dirs trailing /.
    private func davHref(_ path: String, _ isDir: Bool) throws -> String {
        let clean = try fs.sanitize(path)
        if clean.isEmpty { return "/" }
        let href = "/" + clean.split(separator: "/", omittingEmptySubsequences: false).map { urlEncode(String($0)) }.joined(separator: "/")
        return isDir ? href + "/" : href
    }

    private func appendResponse(_ sb: inout String, _ href: String, _ e: VaultEntry) {
        sb += "<D:response><D:href>\(xml(href))</D:href><D:propstat><D:prop>"
        sb += "<D:displayname>\(xml(e.name))</D:displayname>"
        sb += e.isDir ? "<D:resourcetype><D:collection/></D:resourcetype>" : "<D:resourcetype/>"
        if !e.isDir {
            sb += "<D:getcontentlength>\(e.size)</D:getcontentlength>"
            if let m = e.mime { sb += "<D:getcontenttype>\(xml(m))</D:getcontenttype>" }
        }
        sb += "<D:getlastmodified>\(httpDate(e.mtime * 1000))</D:getlastmodified>"
        sb += "<D:getetag>\"\(e.mtime)-\(e.size)\"</D:getetag>"
        sb += "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    /// Direct PUT: body → file, replacing. 201 when created, 204 when replaced. Bodies are either
    /// `Content-Length`-framed or `Transfer-Encoding: chunked` (what Finder sends for large files).
    private func handlePut(_ c: HttpConn, _ path: String, _ headers: [String: String]) throws {
        let existed = (try? fs.stat(path)) != nil
        if headers["transfer-encoding"]?.lowercased().contains("chunked") == true {
            // Hint from Finder: claim that space up-front so an obviously too-big upload fails early.
            if let hint = headers["x-expected-entity-length"].flatMap({ Int64($0) }), hint > 0, !fs.hasRoom(forBytes: hint) {
                throw VaultError(413, "insufficient storage")
            }
            _ = try fs.putStream(path, input: ChunkedBody(c), length: VaultPaths.unknownLength)
        } else {
            guard let length = headers["content-length"].flatMap({ Int64($0) }) else { throw VaultError(411, "content-length required") }
            if length < 0 { throw Fail.badRequest("bad content-length") }
            _ = try fs.putStream(path, input: c, length: length)
        }
        try respond(c, existed ? 204 : 201)
    }

    /// Decoder for a chunked request body (RFC 9112 §7.1): hex size line (extensions ignored), data,
    /// CRLF, … `0` chunk, optional trailers. Every length is bounded so a hostile client can't make
    /// the parser buffer without limit.
    private final class ChunkedBody: VaultByteSource {
        private let conn: HttpConn
        private var remaining = 0          // bytes left in the current chunk
        private var finished = false
        private static let maxChunk = 16 * 1024 * 1024
        init(_ conn: HttpConn) { self.conn = conn }

        private func line(limit: Int) throws -> String {
            var bytes: [UInt8] = []
            while true {
                let b = try conn.readByte()
                if b < 0 { throw EOFError() }
                if b == 0x0A { break }
                if b != 0x0D { bytes.append(UInt8(b)) }
                if bytes.count > limit { throw VaultError(400, "chunk header too long") }
            }
            return String(decoding: bytes, as: UTF8.self)
        }

        func read(_ max: Int) throws -> [UInt8] {
            if finished { return [] }
            if remaining == 0 {
                let sizeLine = try line(limit: 256)
                let hexPart = sizeLine.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
                guard !hexPart.isEmpty, hexPart.count <= 8, let size = Int(hexPart, radix: 16), size >= 0, size <= Self.maxChunk else {
                    throw VaultError(400, "bad chunk size")
                }
                if size == 0 {
                    for _ in 0..<32 { if try line(limit: 1024).isEmpty { break } }      // trailers
                    finished = true
                    return []
                }
                remaining = size
            }
            let part = try conn.read(Swift.min(max, remaining))
            if part.isEmpty { throw EOFError() }
            remaining -= part.count
            if remaining == 0, try line(limit: 8) != "" { throw VaultError(400, "chunk not terminated") }
            return part
        }
    }

    /// Finder requires lock tokens to mount read-write; the lock is a stub.
    private func respondLock(_ c: HttpConn) throws {
        let token = "opaquelocktoken:crossdrop-" + UUID().uuidString.lowercased()
        let body = Array(("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype>" +
            "<D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth>" +
            "<D:timeout>Second-3600</D:timeout>" +
            "<D:locktoken><D:href>\(token)</D:href></D:locktoken>" +
            "</D:activelock></D:lockdiscovery></D:prop>").utf8)
        try respond(c, 200, [
            ("Lock-Token", "<\(token)>"),
            ("Content-Type", "application/xml; charset=utf-8"),
            ("Content-Length", "\(body.count)"),
        ])
        try c.write(body)
    }

    /// `Destination:` may be an absolute URL or a bare path — take the path part.
    private func destPath(_ h: String) -> String {
        var p = h.trimmingCharacters(in: .whitespaces)
        if let scheme = p.range(of: "://") {
            if let slash = p[scheme.upperBound...].firstIndex(of: "/") { p = String(p[slash...]) } else { p = "/" }
        }
        if let q = p.firstIndex(of: "?") { p = String(p[..<q]) }
        return urlDecode(p)
    }

    /// Finder/Explorer PUTs carry `Expect: 100-continue` — answer it so the body flows.
    private func continue100(_ c: HttpConn, _ headers: [String: String]) throws {
        if headers["expect"]?.lowercased() == "100-continue" {
            try c.write(Array("HTTP/1.1 100 Continue\r\n\r\n".utf8))
        }
    }

    /// Discard a bounded request body (PROPFIND/LOCK payloads are ignored).
    private func drainBody(_ c: HttpConn, _ headers: [String: String]) {
        var left = min(headers["content-length"].flatMap { Int64($0) } ?? 0, 256 * 1024)
        while left > 0 {
            guard let part = try? c.read(Int(min(8 * 1024, left))), !part.isEmpty else { break }
            left -= Int64(part.count)
        }
    }

    // MARK: JSON + formatting

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return f
    }()
    private static let dateLock = NSLock()

    private func httpDate(_ ms: Int64) -> String {
        Self.dateLock.lock(); defer { Self.dateLock.unlock() }
        return Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private func xml(_ s: String) -> String {
        var out = ""
        for c in s.unicodeScalars {
            switch c {
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "&": out += "&amp;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            case "\t", "\n", "\r": out.unicodeScalars.append(c)
            default:
                // XML 1.0 forbids other C0 controls and U+FFFE/FFFF — a hostile filename must not
                // be able to make the whole PROPFIND response unparseable for Finder.
                if c.value < 0x20 || c.value == 0xFFFE || c.value == 0xFFFF { out += "\u{FFFD}" } else { out.unicodeScalars.append(c) }
            }
        }
        return out
    }

    private func infoJson() -> String {
        "{\"name\":\(jsonStr(deviceName())),\"writable\":\(writable),\"pin\":\(pin != nil)}"
    }

    private func fsJson(_ path: String) throws -> String {
        let entries = try fs.list(path)
        var s = "{\"path\":\(jsonStr("/" + (try fs.sanitize(path)))),\"writable\":\(writable),\"entries\":["
        s += entries.map(entryJson).joined(separator: ",")
        return s + "]}"
    }

    /// (The Kotlin original double-wraps this in braces — `{{…}}` — an invalid-JSON bug no client
    /// relies on; the Swift port emits a single object.)
    private func attrJson(_ path: String) throws -> String { entryJson(try fs.stat(path)) }

    private func entryJson(_ e: VaultEntry) -> String {
        var s = "{\"name\":\(jsonStr(e.name)),\"size\":\(e.size),\"isDir\":\(e.isDir),\"mtime\":\(e.mtime)"
        if let m = e.mime { s += ",\"mime\":\(jsonStr(m))" }
        return s + "}"
    }

    private func errorJson(_ code: Int, _ msg: String) -> String { "{\"code\":\(code),\"message\":\(jsonStr(msg))}" }

    private func jsonStr(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u.value {
            case 0x22, 0x5C: out += "\\"; out.unicodeScalars.append(u)
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0..<0x20: out += String(format: "\\u%04x", Int(u.value))
            default: out.unicodeScalars.append(u)
            }
        }
        return out + "\""
    }

    // MARK: /raw

    private func serveRaw(_ c: HttpConn, _ path: String, _ rangeHeader: String?, headOnly: Bool, download: Bool) throws {
        let e = try fs.stat(path)
        if e.isDir { throw VaultError(404, "not found") }
        let size = e.size
        var start: Int64 = 0, end = size - 1, status = 200
        // Multi-range isn't supported: a server may ignore Range then (RFC 9110 §14.2) → plain 200.
        if let rh = rangeHeader, !rh.contains(",") {
            guard let r = parseRange(rh, size) else { throw VaultError(416, "unsatisfiable range") }
            start = r.0; end = r.1; status = 206
        }
        let len: Int64 = size == 0 ? 0 : end - start + 1
        let name = String(path.split(separator: "/", omittingEmptySubsequences: false).last ?? "")
        let mime = e.mime ?? fs.mimeOf(name)
        var headers: [(String, String)] = [
            ("Accept-Ranges", "bytes"),
            ("Content-Type", mime),
            ("Content-Length", "\(len)"),
            ("Cache-Control", "no-store"),
            ("Content-Disposition", "\(download ? "attachment" : "inline"); filename*=UTF-8''\(urlEncode(name))"),
        ]
        if status == 206 { headers.append(("Content-Range", "bytes \(start)-\(end)/\(size)")) }
        // Files in a shared folder are untrusted: an .html/.svg served inline would run script in
        // the vault page's own origin (where the PIN lives). `sandbox` gives it an opaque origin.
        let activeTypes = ["text/html", "image/svg+xml", "text/xml", "application/xml", "application/xhtml+xml"]
        if activeTypes.contains(where: { mime.lowercased().hasPrefix($0) }) {
            headers.append(("Content-Security-Policy", "sandbox"))
        }
        try respond(c, status, headers, contentLength: len)
        if headOnly || len == 0 { return }
        let src = try fs.open(path, offset: start)
        defer { src.close() }
        var left = len
        while left > 0 {
            let part = try src.read(Int(min(64 * 1024, left)))
            if part.isEmpty { break }
            try c.write(part)
            left -= Int64(part.count)
        }
        onBytesServed(len)
    }

    /// `bytes=a-b`, `bytes=a-`, `bytes=-n` — single range only.
    func parseRange(_ h: String, _ size: Int64) -> (Int64, Int64)? {
        let t = h.trimmingCharacters(in: .whitespaces)
        guard t.hasPrefix("bytes=") else { return nil }
        let spec = String(t.dropFirst(6))
        if spec.contains(",") { return nil }
        guard let dash = spec.firstIndex(of: "-") else { return nil }
        let a = spec[..<dash].trimmingCharacters(in: .whitespaces)
        let b = spec[spec.index(after: dash)...].trimmingCharacters(in: .whitespaces)
        if size == 0 { return nil }
        if a.isEmpty {
            guard let n = Int64(b), n > 0 else { return nil }
            return (max(size - n, 0), size - 1)
        }
        guard let s = Int64(a), s < size else { return nil }
        let e: Int64
        if b.isEmpty { e = size - 1 } else { guard let v = Int64(b) else { return nil }; e = v }
        if e < s { return nil }
        return (s, min(e, size - 1))
    }

    // MARK: /upload

    /// Content-length-bounded stream over the request body.
    private final class BodyReader {
        let conn: HttpConn
        var left: Int64
        init(_ conn: HttpConn, _ length: Int64) { self.conn = conn; left = length }

        func read(_ max: Int) throws -> [UInt8] {
            if left <= 0 { return [] }
            let part = try conn.read(Int(min(Int64(max), left)))
            left -= Int64(part.count)
            return part
        }

        func unread(_ data: [UInt8]) { conn.unread(data); left += Int64(data.count) }

        /// One \r\n-terminated line, at most `limit` bytes (a hostile client could otherwise send a
        /// single endless "header" line and grow memory without bound). EOF ends the line too.
        func readLine(limit: Int = 8 * 1024) throws -> String {
            var bytes: [UInt8] = []
            while left > 0 {
                let b = try conn.readByte()
                if b < 0 { break }
                left -= 1
                if b == 0x0A { break }
                if b != 0x0D { bytes.append(UInt8(b)) }
                if bytes.count > limit { throw VaultError(400, "multipart header line too long") }
            }
            return String(bytes.map { Character(Unicode.Scalar($0)) })
        }
    }

    private func handleUpload(_ c: HttpConn, _ headers: [String: String], _ dirPath: String) throws {
        guard let ctype = headers["content-type"] else { throw Fail.badRequest("no content-type") }
        var boundary = ""
        if let r = ctype.range(of: "boundary=") {
            boundary = String(ctype[r.upperBound...]).trimmingCharacters(in: .whitespaces)
            if boundary.count >= 2, boundary.hasPrefix("\""), boundary.hasSuffix("\"") { boundary = String(boundary.dropFirst().dropLast()) }
        }
        if boundary.isEmpty || boundary.count > 128 { throw Fail.badRequest("no boundary") }
        guard let length = headers["content-length"].flatMap({ Int64($0) }) else { throw Fail.badRequest("no content-length") }

        if !(try fs.stat(dirPath).isDir) { throw VaultError(400, "bad target dir") }

        if length < 0 { throw Fail.badRequest("bad content-length") }
        // Claim the space up-front (overflow-safe; concurrent uploads share one ledger).
        if !fs.reserve(length) { throw VaultError(413, "insufficient storage") }
        defer { fs.release(length) }

        let body = BodyReader(c, length)
        var saved: [String] = []

        let first = try body.readLine(limit: 1024)
        if first == "--\(boundary)--" { try respondJson(c, 200, "{\"ok\":true,\"saved\":[]}"); return }
        if first != "--\(boundary)" { throw Fail.badRequest("bad multipart preamble") }

        let marker = Array("\r\n--\(boundary)".utf8)
        var partNo: Int64 = 0
        while true {
            var ph: [String: String] = [:]
            var headerLines = 0
            while true {
                if body.left <= 0 { throw EOFError() }
                let line = try body.readLine()
                if line.isEmpty { break }
                headerLines += 1
                if headerLines > 32 { throw VaultError(400, "too many multipart headers") }
                if let colon = line.firstIndex(of: ":"), colon != line.startIndex {
                    ph[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] =
                        line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                }
            }
            let filename = ph["content-disposition"].flatMap(parseFilename)
            partNo += 1
            if filename != nil && saved.count >= Self.maxFilesPerUpload { throw VaultError(400, "too many files") }
            let stage = fs.stageFile(id: Self.httpStageBase + partNo)
            defer { try? FileManager.default.removeItem(at: stage) }
            FileManager.default.createFile(atPath: stage.path, contents: nil)
            let out = filename != nil ? FileHandle(forWritingAtPath: stage.path) : nil
            do { try streamPart(body, marker, out); try out?.close() } catch { try? out?.close(); throw error }
            if let filename {
                let rel = try joinPath(dirPath, sanitizeFilename(filename))
                saved.append(try fs.commitStaged(stage, rel))
            }
            if try body.readLine().hasPrefix("--") { break }
        }
        try respondJson(c, 200, "{\"ok\":true,\"saved\":[" + saved.map(jsonStr).joined(separator: ",") + "]}")
    }

    /// Scan the body for `\r\n--boundary`, writing everything before it to `out`; the marker and
    /// anything after it is pushed back for the next part.
    private func streamPart(_ body: BodyReader, _ marker: [UInt8], _ out: FileHandle?) throws {
        var pending: [UInt8] = []
        while true {
            let chunk = try body.read(64 * 1024)
            if chunk.isEmpty { throw EOFError() }
            let data = pending.isEmpty ? chunk : pending + chunk
            if let idx = indexOf(data, marker) {
                if idx > 0 { try out?.write(contentsOf: Data(data[0..<idx])) }
                body.unread(Array(data[(idx + marker.count)...]))
                return
            }
            let keep = marker.count - 1           // marker may straddle chunks
            let flushable = data.count - keep
            if flushable > 0 {
                try out?.write(contentsOf: Data(data[0..<flushable]))
                pending = Array(data[flushable...])
            } else { pending = data }
        }
    }

    private func indexOf(_ hay: [UInt8], _ needle: [UInt8]) -> Int? {
        let limit = hay.count - needle.count
        if limit < 0 { return nil }
        let first = needle[0]
        var i = 0
        while i <= limit {
            if hay[i] == first {
                var j = 1
                while j < needle.count && hay[i + j] == needle[j] { j += 1 }
                if j == needle.count { return i }
            }
            i += 1
        }
        return nil
    }

    private static let filenameStar = try! NSRegularExpression(pattern: #"filename\*\s*=\s*UTF-8''([^;]+)"#, options: .caseInsensitive)
    private static let filenamePlain = try! NSRegularExpression(pattern: #"filename\s*=\s*"((?:[^"\\]|\\.)*)""#)

    private func parseFilename(_ disposition: String) -> String? {
        let ns = disposition as NSString
        let full = NSRange(location: 0, length: ns.length)
        if let m = Self.filenameStar.firstMatch(in: disposition, range: full) { return urlDecode(ns.substring(with: m.range(at: 1))) }
        if let m = Self.filenamePlain.firstMatch(in: disposition, range: full) {
            return ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "\\\"", with: "\"").replacingOccurrences(of: "\\\\", with: "\\")
        }
        return nil
    }

    private func sanitizeFilename(_ name: String) throws -> String {
        let flat = name.replacingOccurrences(of: "\\", with: "/")
        let base = String(flat.split(separator: "/", omittingEmptySubsequences: false).last ?? "")
            .replacingOccurrences(of: "\u{0}", with: " ").trimmingCharacters(in: .whitespaces)
        if base.isEmpty || base.hasPrefix(".") { throw VaultError(400, "bad filename") }
        return base
    }

    private func joinPath(_ dir: String, _ name: String) throws -> String {
        let d = try fs.sanitize(dir)
        return d.isEmpty ? name : "\(d)/\(name)"
    }

    // MARK: plumbing

    private func readHead(_ c: HttpConn) throws -> (request: (String, String), headers: [String: String])? {
        var bytes: [UInt8] = []
        var matched = 0
        let end: [UInt8] = [13, 10, 13, 10]
        // Slow-loris guard: one overall deadline for the head, and short per-read timeouts while
        // it is pending (the long idle timeout applies once the request is in).
        let deadline = Date().addingTimeInterval(Self.headDeadline)
        c.socket.setTimeout(Self.headReadTimeout)
        defer { c.socket.setTimeout(Self.bodyIdle) }
        while bytes.count < Self.maxHead {
            if Date() > deadline { throw Fail.badRequest("request head too slow") }
            let b = try c.readByte()
            if b < 0 {
                if bytes.isEmpty { return nil }
                throw Fail.badRequest("truncated head")
            }
            bytes.append(UInt8(b))
            matched = UInt8(b) == end[matched] ? matched + 1 : (UInt8(b) == end[0] ? 1 : 0)
            if matched == 4 { break }
        }
        if matched < 4 { throw Fail.badRequest("head too large") }
        let text = String(bytes.map { Character(Unicode.Scalar($0)) })      // ISO-8859-1
        let lines = text.components(separatedBy: "\r\n")
        let req = lines[0].components(separatedBy: " ")
        if req.count < 2 { throw Fail.badRequest("bad request line") }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            if let colon = line.firstIndex(of: ":"), colon != line.startIndex {
                headers[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] =
                    line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
        return ((req[0].uppercased(), req[1]), headers)
    }

    private func parseQuery(_ q: String) -> [String: String] {
        var out: [String: String] = [:]
        for kv in q.split(separator: "&") where !kv.allSatisfy({ $0 == " " }) {
            if let e = kv.firstIndex(of: "=") {
                out[urlDecode(String(kv[..<e]), plusAsSpace: true)] = urlDecode(String(kv[kv.index(after: e)...]), plusAsSpace: true)
            } else { out[urlDecode(String(kv), plusAsSpace: true)] = "" }
        }
        return out
    }

    // MARK: response

    private func respondPage(_ c: HttpConn) throws {
        let page = Array(VaultPage.html.utf8)
        try respond(c, 200, [
            ("Content-Type", "text/html; charset=utf-8"),
            ("Content-Length", "\(page.count)"),
            ("Cache-Control", "no-store"),
            ("X-Frame-Options", "DENY"),
            ("Content-Security-Policy", "frame-ancestors 'none'"),
        ])
        try c.write(page)
    }

    private func respondJson(_ c: HttpConn, _ status: Int, _ body: String) throws {
        let b = Array(body.utf8)
        try respond(c, status, [
            ("Content-Type", "application/json"),
            ("Content-Length", "\(b.count)"),
            ("Cache-Control", "no-store"),
        ])
        try c.write(b)
    }

    private func respond(_ c: HttpConn, _ status: Int, _ headers: [(String, String)] = [], contentLength: Int64 = -1) throws {
        var sb = "HTTP/1.1 \(status) \(statusText(status))\r\n"
        for (k, v) in headers { sb += "\(k): \(v.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: ""))\r\n" }
        if contentLength >= 0 && !headers.contains(where: { $0.0.lowercased() == "content-length" }) {
            sb += "Content-Length: \(contentLength)\r\n"
        }
        sb += "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
        sb += "Connection: close\r\n\r\n"
        try c.write(sb.data(using: .isoLatin1, allowLossyConversion: true).map { [UInt8]($0) } ?? [])
    }

    private func statusText(_ code: Int) -> String {
        switch code {
        case 200: return "OK"; case 201: return "Created"; case 204: return "No Content"; case 206: return "Partial Content"
        case 207: return "Multi-Status"; case 301: return "Moved Permanently"
        case 400: return "Bad Request"; case 401: return "Unauthorized"; case 403: return "Forbidden"
        case 404: return "Not Found"; case 405: return "Method Not Allowed"; case 409: return "Conflict"
        case 411: return "Length Required"; case 412: return "Precondition Failed"
        case 413: return "Payload Too Large"; case 429: return "Too Many Requests"; case 416: return "Range Not Satisfiable"
        case 500: return "Internal Server Error"; default: return "?"
        }
    }

    // MARK: encoding

    /// Percent-decode. `plusAsSpace` only for query params — in a path '+' is literal.
    /// Input is the ISO-8859-1 head text, so each character is one byte; bytes are UTF-8 decoded.
    func urlDecode(_ s: String, plusAsSpace: Bool = false) -> String {
        let chars = Array(s.unicodeScalars)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(chars.count)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c == "%", i + 2 < chars.count {
                let hexStr = String(String.UnicodeScalarView(chars[(i + 1)...(i + 2)]))
                if let h = UInt8(hexStr, radix: 16) { bytes.append(h); i += 3; continue }
            }
            if c == "+" && plusAsSpace { bytes.append(0x20) } else { bytes.append(UInt8(truncatingIfNeeded: c.value)) }
            i += 1
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    func urlEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            if (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39) || b == 0x2D || b == 0x5F || b == 0x2E || b == 0x7E {
                out.unicodeScalars.append(Unicode.Scalar(b))
            } else { out += String(format: "%%%02X", Int(b)) }
        }
        return out
    }
}
