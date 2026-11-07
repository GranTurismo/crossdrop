import Foundation

/// Zero-dependency micro HTTP server: serves a self-contained web client plus the shared files to
/// any browser on the LAN — the "no app required" path. Port of `core/share/HttpShareServer.kt`.
///
/// All routes live under a random capability token (`http://ip:port/<token>/`); nothing is served
/// without it (404, not 403 — the surface is invisible).
///
///   GET /<token>/               single-file HTML client
///   GET /<token>/manifest.json  {device, files[], zip}
///   GET /<token>/status.json    live download counters (the page polls this)
///   GET /<token>/f/<i>          file i (GET or HEAD, Content-Disposition)
///   GET /<token>/all.zip        all files as an uncompressed ZIP
public final class HttpShareServer {
    public enum Event { case clientConnected, downloadStarted, downloadDone, downloadAborted }
    public struct Stats { public let downloadsStarted: Int; public let downloadsDone: Int; public let bytesServed: Int64 }

    public private(set) var port: Int = 0

    private let files: [OutgoingFile]
    private let deviceName: String
    private let token: String
    private let onEvent: (Event) -> Void

    private let listener = TCPListener()
    private let lock = NSLock()
    private var started = 0, done = 0
    private var served: Int64 = 0
    private var open: [Int32: TCPSocket] = [:]
    private var perIp: [String: Int] = [:]
    private var running = false

    private static let maxHead = 16 * 1024
    private static let chunk = 64 * 1024
    private static let ioTimeout: TimeInterval = 120
    static var headDeadline: TimeInterval = 15      // tests shorten these
    static var headReadTimeout: TimeInterval = 5
    /// Anyone on the Wi-Fi can open connections (the token only gates *content*): bound the threads.
    private static let maxConnections = 32
    private static let maxConnectionsPerIp = 8
    private static let zip64Limit: Int64 = 0xFFFF_FFFF
    private static let zipMaxEntries = 65_535

    public init(files: [OutgoingFile], deviceName: String, token: String, onEvent: @escaping (Event) -> Void = { _ in }) {
        self.files = files; self.deviceName = deviceName; self.token = token; self.onEvent = onEvent
    }

    public func stats() -> Stats {
        lock.lock(); defer { lock.unlock() }
        return Stats(downloadsStarted: started, downloadsDone: done, bytesServed: served)
    }

    public func start() throws {
        guard !running else { return }
        try listener.start { [weak self] socket, ip in
            guard let self else { socket.close(); return }
            self.lock.lock()
            let full = self.open.count >= Self.maxConnections || (self.perIp[ip] ?? 0) >= Self.maxConnectionsPerIp
            if !full { self.open[socket.fd] = socket; self.perIp[ip, default: 0] += 1 }
            self.lock.unlock()
            if full { socket.close(); return }
            let t = Thread { [weak self] in
                self?.handle(socket)
                if let self {
                    self.lock.lock()
                    self.open[socket.fd] = nil
                    if let n = self.perIp[ip] { if n <= 1 { self.perIp[ip] = nil } else { self.perIp[ip] = n - 1 } }
                    self.lock.unlock()
                }
                socket.close()
            }
            t.name = "crossdrop-share"
            t.start()
        }
        port = listener.port
        running = true
    }

    /// Stops accepting and cuts any download still in flight — the link dies with the share.
    public func close() {
        running = false
        listener.stop()
        lock.lock(); let live = Array(open.values); lock.unlock()
        live.forEach { $0.shutdown() }
    }

    // MARK: requests

    private func handle(_ socket: TCPSocket) {
        guard let head = try? readHead(socket) else { return }
        let reqLine = head.components(separatedBy: "\r\n")[0].split(separator: " ", omittingEmptySubsequences: true)
        guard reqLine.count >= 2 else { return }
        let method = reqLine[0].uppercased()
        let rawPath = String(reqLine[1]).split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let path = HttpShareServer.urlDecode(rawPath)
        if method != "GET" && method != "HEAD" {
            try? respond(socket, 405, "Method Not Allowed", "text/plain", [], true)
            return
        }
        try? route(path, method == "HEAD", socket)
    }

    private func route(_ path: String, _ headOnly: Bool, _ out: TCPSocket) throws {
        // Token check first and in constant time (the string compares below only ever see paths
        // that already carry the right token).
        let first = path.dropFirst().split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        guard path.hasPrefix("/"), VaultWire.constantTimeEqual(Array(first.utf8), Array(token.utf8)) else {
            return try notFound(out, headOnly)
        }
        let base = "/\(token)"
        if path == base {
            try respond(out, 301, "Moved", "text/plain", [], headOnly, extra: [("Location", "\(base)/")])
        } else if path == "\(base)/" || path == "\(base)/index.html" {
            onEvent(.clientConnected)
            try respond(out, 200, "OK", "text/html; charset=utf-8", Array(ShareHtml.page.utf8), headOnly)
        } else if path == "\(base)/manifest.json" {
            try respond(out, 200, "OK", "application/json", Array(manifest().utf8), headOnly)
        } else if path == "\(base)/status.json" {
            let s = stats()
            let json = "{\"started\":\(s.downloadsStarted),\"done\":\(s.downloadsDone),\"bytes\":\(s.bytesServed)}"
            try respond(out, 200, "OK", "application/json", Array(json.utf8), headOnly)
        } else if path.hasPrefix("\(base)/f/") {
            let i = Int(path.dropFirst("\(base)/f/".count)) ?? -1
            guard files.indices.contains(i) else { return try notFound(out, headOnly) }
            try sendFile(files[i], headOnly, out)
        } else if path == "\(base)/all.zip" {
            try sendZip(headOnly, out)
        } else {
            try notFound(out, headOnly)
        }
    }

    private func manifest() -> String {
        let zip = files.count > 1 && Self.zipFits(files)
        let entries = files.enumerated().map { i, f in
            "{\"i\":\(i),\"name\":\(Self.jsonString(f.meta.name)),\"size\":\(f.meta.size),\"mime\":\(f.meta.mime.map(Self.jsonString) ?? "null")}"
        }.joined(separator: ",")
        return "{\"device\":\(Self.jsonString(deviceName)),\"files\":[\(entries)],\"zip\":\(zip)}"
    }

    // MARK: responses

    private func notFound(_ out: TCPSocket, _ headOnly: Bool) throws {
        try respond(out, 404, "Not Found", "text/plain", Array("not found".utf8), headOnly)
    }

    private func respond(_ out: TCPSocket, _ code: Int, _ reason: String, _ type: String, _ body: [UInt8],
                         _ headOnly: Bool = false, extra: [(String, String)] = []) throws {
        // no-referrer: the capability token lives in the URL and must not leak via Referer.
        var h = "HTTP/1.1 \(code) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n"
        for (k, v) in extra { h += "\(k): \(v)\r\n" }
        h += "\r\n"
        try out.writeAll(Array(h.utf8) + (headOnly ? [] : body))
    }

    private func sendFile(_ file: OutgoingFile, _ headOnly: Bool, _ out: TCPSocket) throws {
        let meta = file.meta
        // CR/LF/quotes in a filename or MIME would split or break response headers.
        let safeName = String(meta.name.map { "\r\n\\\"".contains($0) ? "_" : $0 })
        let mimeOK = meta.mime.map { !$0.isEmpty && $0.unicodeScalars.allSatisfy { Self.mimeChars.contains($0) } } ?? false
        let safeMime = mimeOK ? meta.mime! : "application/octet-stream"
        var h = "HTTP/1.1 200 OK\r\nContent-Type: \(safeMime)\r\nContent-Length: \(meta.size)\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: sandbox\r\n"
        h += "Content-Disposition: attachment; filename=\"\(safeName)\"; filename*=UTF-8''\(Self.rfc5987(meta.name))\r\n"
        h += "Connection: close\r\n\r\n"
        try out.writeAll(Array(h.utf8))
        if headOnly { return }

        onEvent(.downloadStarted)
        bump(started: 1)
        do {
            let r = try FileReader(file.url)
            defer { r.close() }
            let sent = try pump(r, out, limit: meta.size)
            if sent != meta.size { throw ProtocolError("\(meta.name) changed mid-share") }     // short body → abort, don't claim success
            bump(done: 1)
            onEvent(.downloadDone)
        } catch {
            onEvent(.downloadAborted)
        }
    }

    /// All files as an uncompressed (STORED) zip with real header values (no data descriptors) —
    /// the most compatible subset: java.util.zip, OS archivers and mobile Files apps all read it.
    private func sendZip(_ headOnly: Bool, _ out: TCPSocket) throws {
        if files.isEmpty || !Self.zipFits(files) {
            return try respond(out, 413, "Too Large", "text/plain", Array("zip unavailable".utf8), headOnly)
        }
        if headOnly { return try respond(out, 200, "OK", "application/zip", [], true) }
        try out.writeAll(Array("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"crossdrop.zip\"\r\nConnection: close\r\n\r\n".utf8))

        onEvent(.downloadStarted)
        bump(started: 1)
        do {
            let names = Self.dedupeNames(files.map { $0.meta.name })
            // First pass: CRC-32 + true length per entry (metadata can lie).
            let sums = try files.map { try crcOf($0) }
            var central = [UInt8]()
            var offset: Int64 = 0
            let (dt, dd) = Self.dosStamp()
            for (i, file) in files.enumerated() {
                let name = Array(names[i].utf8)
                let (crc, size) = sums[i]
                var local = [UInt8]()
                Self.le32(&local, 0x04034B50); Self.le16(&local, 20); Self.le16(&local, 0x0800)   // UTF-8 names
                Self.le16(&local, 0); Self.le16(&local, dt); Self.le16(&local, dd)
                Self.le32(&local, crc); Self.le32(&local, UInt32(truncatingIfNeeded: size)); Self.le32(&local, UInt32(truncatingIfNeeded: size))
                Self.le16(&local, name.count); Self.le16(&local, 0); local += name
                try out.writeAll(local)

                let r = try FileReader(file.url)
                let written = try pump(r, out, limit: size)
                r.close()
                if written != size { throw ProtocolError("\(file.meta.name) changed mid-share") }

                Self.le32(&central, 0x02014B50); Self.le16(&central, 20); Self.le16(&central, 20)
                Self.le16(&central, 0x0800); Self.le16(&central, 0); Self.le16(&central, dt); Self.le16(&central, dd)
                Self.le32(&central, crc); Self.le32(&central, UInt32(truncatingIfNeeded: written)); Self.le32(&central, UInt32(truncatingIfNeeded: written))
                Self.le16(&central, name.count); Self.le16(&central, 0); Self.le16(&central, 0); Self.le16(&central, 0); Self.le16(&central, 0)
                Self.le32(&central, 0); Self.le32(&central, UInt32(truncatingIfNeeded: offset)); central += name
                offset += Int64(local.count) + written
            }
            var eocd = [UInt8]()
            Self.le32(&eocd, 0x06054B50); Self.le16(&eocd, 0); Self.le16(&eocd, 0)
            Self.le16(&eocd, files.count); Self.le16(&eocd, files.count)
            Self.le32(&eocd, UInt32(central.count)); Self.le32(&eocd, UInt32(truncatingIfNeeded: offset)); Self.le16(&eocd, 0)
            try out.writeAll(central + eocd)
            bump(done: 1)
            onEvent(.downloadDone)
        } catch {
            onEvent(.downloadAborted)
        }
    }

    // MARK: util

    /// Classic (non-zip64) archives cap entries at 65 535 and every offset/size at 4 GiB — and the
    /// *running offset* counts, not just each file: three 2 GiB files wrap the third entry's
    /// offset. Shares that don't fit simply don't offer all.zip (per-file downloads still work).
    static func zipFits(_ files: [OutgoingFile]) -> Bool {
        guard !files.isEmpty, files.count <= zipMaxEntries else { return false }
        let names = dedupeNames(files.map { $0.meta.name })
        var total: Int64 = 22
        for (i, f) in files.enumerated() {
            let nameLen = Int64(names[i].utf8.count)
            guard f.meta.size >= 0, f.meta.size < zip64Limit else { return false }
            total += (30 + nameLen) + f.meta.size + (46 + nameLen)
            if total >= zip64Limit { return false }
        }
        return true
    }

    private func bump(started s: Int = 0, done d: Int = 0, bytes b: Int64 = 0) {
        lock.lock(); started += s; done += d; served += b; lock.unlock()
    }

    /// Streams at most `limit` bytes: Content-Length was already sent, so a file that grew since
    /// the manifest was built must not spill extra bytes onto the connection.
    private func pump(_ r: FileReader, _ out: TCPSocket, limit: Int64 = Int64.max) throws -> Int64 {
        var total: Int64 = 0
        while total < limit {
            var buf = try r.read(Int(min(Int64(HttpShareServer.chunk), limit - total)))
            if buf.isEmpty { return total }
            if Int64(buf.count) > limit - total { buf = Array(buf.prefix(Int(limit - total))) }
            try out.writeAll(buf)
            total += Int64(buf.count)
            bump(bytes: Int64(buf.count))
        }
        return total
    }

    private func crcOf(_ file: OutgoingFile) throws -> (UInt32, Int64) {
        let r = try FileReader(file.url)
        defer { r.close() }
        var crc: UInt32 = 0xFFFF_FFFF
        var size: Int64 = 0
        while true {
            let buf = try r.read(HttpShareServer.chunk)
            if buf.isEmpty { break }
            for b in buf { crc = Self.crcTable[Int((crc ^ UInt32(b)) & 0xFF)] ^ (crc >> 8) }
            size += Int64(buf.count)
        }
        return (~crc, size)
    }

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    /// Read up to the blank line, capped — returns the raw header block.
    private func readHead(_ s: TCPSocket) throws -> String {
        var buf = [UInt8]()
        // Slow-loris guard: overall deadline + short reads while the head is pending.
        let deadline = Date().addingTimeInterval(HttpShareServer.headDeadline)
        s.setTimeout(HttpShareServer.headReadTimeout)
        defer { s.setTimeout(HttpShareServer.ioTimeout) }
        while buf.count < HttpShareServer.maxHead {
            if Date() > deadline { throw ProtocolError("request head too slow") }
            buf += try s.readFully(1)
            if buf.count >= 4 && buf.suffix(4).elementsEqual([13, 10, 13, 10]) { break }
            if buf.count >= 2 && buf.suffix(2).elementsEqual([10, 10]) { break }
        }
        return String(decoding: buf, as: UTF8.self)
    }

    static func urlDecode(_ s: String) -> String {
        let bytes = Array(s.utf8)
        var out = [UInt8](); out.reserveCapacity(bytes.count)
        var i = 0
        while i < bytes.count {
            if bytes[i] == UInt8(ascii: "%"), i + 2 < bytes.count,
               let v = UInt8(String(decoding: bytes[i + 1...i + 2], as: UTF8.self), radix: 16) {
                out.append(v); i += 3      // malformed escapes keep the '%' literally
            } else { out.append(bytes[i]); i += 1 }
        }
        return String(decoding: out, as: UTF8.self)
    }

    private static let mimeChars: CharacterSet = {
        var cs = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        cs.insert(charactersIn: "!#$&^_.+*/-")
        return cs
    }()

    static func rfc5987(_ name: String) -> String {
        let keep = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&+-.^_`|~")
        return name.utf8.map { b in
            let ch = Character(UnicodeScalar(b))
            return b < 0x80 && keep.contains(ch) ? String(ch) : String(format: "%%%02X", b)
        }.joined()
    }

    static func dedupeNames(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.map { raw in
            var clean = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
            clean = String(clean.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F })     // no control chars/NUL in entry names
            if clean.trimmingCharacters(in: .whitespaces).isEmpty || clean == "." || clean == ".." { clean = "file" }
            var n = clean
            var i = 1
            while !seen.insert(n).inserted {
                let ns = clean as NSString
                let ext = ns.pathExtension
                n = ext.isEmpty ? "\(clean) (\(i))" : "\(ns.deletingPathExtension) (\(i)).\(ext)"
                i += 1
            }
            return n
        }
    }

    static func jsonString(_ s: String) -> String {
        guard let d = try? JSONEncoder().encode(s), let out = String(data: d, encoding: .utf8) else { return "\"\"" }
        return out
    }

    private static func le16(_ o: inout [UInt8], _ v: Int) { o.append(UInt8(v & 0xFF)); o.append(UInt8((v >> 8) & 0xFF)) }
    private static func le32(_ o: inout [UInt8], _ v: UInt32) {
        o.append(UInt8(v & 0xFF)); o.append(UInt8((v >> 8) & 0xFF)); o.append(UInt8((v >> 16) & 0xFF)); o.append(UInt8((v >> 24) & 0xFF))
    }
    private static func le32(_ o: inout [UInt8], _ v: Int) { le32(&o, UInt32(truncatingIfNeeded: v)) }

    private static func dosStamp() -> (Int, Int) {
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date())
        let time = (c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | (c.second ?? 0) / 2
        let date = ((c.year ?? 1980) - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1)
        return (time, date)
    }
}
