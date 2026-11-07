import Foundation
import CryptoKit

public enum TransferEvent {
    case connecting
    case secured(peerName: String, sas: String)
    case awaitingAccept(Offer)
    case progress(bytesDone: Int64, bytesTotal: Int64, bytesPerSec: Int64, currentFile: FileMeta, bytesSkipped: Int64)
    case completed(Offer, elapsedMs: Int64)
    case declinedByPeer
    case cancelled
    case failed(String)
}

/// A file the app layer wants to ship. `url` must stay readable for the whole transfer.
public struct OutgoingFile {
    public let meta: FileMeta
    public let url: URL
    public init(meta: FileMeta, url: URL) { self.meta = meta; self.url = url }
}

public protocol OutputSink: AnyObject {
    func write(_ bytes: [UInt8]) throws
    func close()
}

public enum ReceiveDecision {
    /// `sinkFor` serves the v1 monolithic path; `delta` (when non-nil and the peer speaks v2+)
    /// engages chunked delta/resume storage instead.
    case accept(sinkFor: (FileMeta) throws -> OutputSink, delta: DeltaReceiver?)
    case reject(String?)
}

/// Thread-safe cancel handle for one session.
public final class CancelToken {
    private let lock = NSLock()
    private var cancelled = false
    private var handler: (() -> Void)?
    public init() {}

    public var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    public func cancel() {
        lock.lock()
        cancelled = true
        let h = handler
        lock.unlock()
        h?()
    }

    func attach(_ h: @escaping () -> Void) {
        lock.lock()
        handler = h
        let already = cancelled
        lock.unlock()
        if already { h() }
    }

    /// Drop the handler once the session is over so a late `cancel()` can't touch a finished socket.
    func detach() {
        lock.lock(); handler = nil; lock.unlock()
    }
}

private struct SessionCancelled: Error {}

/// Reads a local file sequentially or at offsets.
final class FileReader {
    private let handle: FileHandle
    init(_ url: URL) throws { handle = try FileHandle(forReadingFrom: url) }
    func read(_ max: Int) throws -> [UInt8] { [UInt8](try handle.read(upToCount: max) ?? Data()) }
    func seek(_ off: Int64) throws { try handle.seek(toOffset: UInt64(off)) }
    func readExactly(_ n: Int) throws -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(n)
        while out.count < n {
            let part = try read(n - out.count)
            if part.isEmpty { throw ProtocolError("source truncated mid-transfer") }
            out += part
        }
        return out
    }
    func close() { try? handle.close() }
}

/// Moving-window throughput meter, emission throttled to ~10 Hz.
private struct RateMeter {
    private var windowStart = DispatchTime.now().uptimeNanoseconds
    private var windowBytes: Int64 = 0
    private var lastEmitMs: UInt64 = 0
    mutating func add(_ n: Int) { windowBytes += Int64(n) }
    mutating func tick(force: Bool = false) -> Int64? {
        let now = DispatchTime.now().uptimeNanoseconds
        let nowMs = now / 1_000_000
        guard force || nowMs &- lastEmitMs >= 100 else { return nil }
        let dt = now &- windowStart
        let bps = dt > 0 ? Int64(Double(windowBytes) * 1e9 / Double(dt)) : 0
        lastEmitMs = nowMs; windowStart = now; windowBytes = 0
        return bps
    }
}

/// Limits applied to everything a remote peer tells us (the package must stay safe on hostile input).
enum Limits {
    static let maxOfferFiles = 100_000
    static let maxFileSize: Int64 = 1 << 60
    static let maxConcurrentReceives = 8
    static let maxDisplayName = 64
}

/// Caps simultaneous inbound sessions: each one holds a thread and a socket, and any host on the LAN can open them.
final class ReceiveGate {
    private let lock = NSLock()
    private var active = 0
    private let limit: Int
    init(limit: Int) { self.limit = limit }
    func enter() -> Bool { lock.lock(); defer { lock.unlock() }; if active >= limit { return false }; active += 1; return true }
    func leave() { lock.lock(); active -= 1; lock.unlock() }
}

extension TransferEngine {
    /// Validates a peer-supplied OFFER and returns its total size. Throws on anything a legitimate sender never produces.
    static func validate(_ offer: Offer) throws -> Int64 {
        guard offer.files.count <= Limits.maxOfferFiles else { throw ProtocolError("offer lists too many files") }
        var total: Int64 = 0
        for f in offer.files {
            guard f.size >= 0, f.size <= Limits.maxFileSize else { throw ProtocolError("offer has an invalid file size") }
            let (t, overflow) = total.addingReportingOverflow(f.size)
            guard !overflow, t <= Limits.maxFileSize else { throw ProtocolError("offer is too large") }
            total = t
        }
        return total
    }

    /// Peer-chosen text that ends up in UI: no control/bidi-override characters, bounded length.
    static func displayName(_ raw: String) -> String {
        let cleaned = String(String.UnicodeScalarView(raw.unicodeScalars.filter { u in
            !CharacterSet.controlCharacters.contains(u) && !(0x202A...0x202E).contains(u.value) && !(0x2066...0x2069).contains(u.value)
        })).trimmingCharacters(in: .whitespaces)
        let clipped = String(cleaned.prefix(Limits.maxDisplayName))
        return clipped.isEmpty ? "Unknown device" : clipped
    }

    /// Distinct metas for the delta store: two offered files that land on the same destination (same name,
    /// or names differing only in case on a case-insensitive volume) must not overwrite each other.
    static func uniqueMetas(_ files: [FileMeta], _ store: DeltaReceiver) -> [FileMeta] {
        var seen = Set<String>()
        return files.map { meta in
            var m = meta
            var n = 1
            while !seen.insert(store.targetFor(m).path.lowercased()).inserted {
                let ext = (meta.name as NSString).pathExtension
                let stem = (meta.name as NSString).deletingPathExtension
                m.name = ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)"
                n += 1
            }
            return m
        }
    }
}

/// Drives one transfer in either direction over a single TCP socket. Blocking — call from a
/// dedicated thread (see `TransferService`). Wire-compatible with the Kotlin `TransferEngine`:
///
///   HELLO → HELLO_ACK → [encrypted] OFFER → ACCEPT/DECLINE → payload → DONE → DONE
///
/// Payload paths by negotiated version: v1 DATA*, v2 CHUNK_MAP/CHUNK_REQ/CHUNK, v3 PUSH_CHUNK/PUSH_END.
public final class TransferEngine {
    public static let chunkSize = 256 * 1024
    static let controlTimeout: TimeInterval = 15
    static let dataTimeout: TimeInterval = 60
    static let deltaTimeout: TimeInterval = 600

    private let identity: SessionIdentity
    private let deviceName: () -> String
    private let instanceId: String
    private let version: Int
    private let gate = ReceiveGate(limit: Limits.maxConcurrentReceives)

    public init(identity: SessionIdentity, deviceName: @escaping () -> String, instanceId: String,
                protocolVersion: Int = CrossDropCore.protocolVersion) {
        self.identity = identity; self.deviceName = deviceName
        self.instanceId = instanceId; self.version = protocolVersion
    }

    // MARK: sender

    public func send(host: String, port: Int, files: [OutgoingFile], cancel: CancelToken = CancelToken(),
                     emit: @escaping (TransferEvent) -> Void) {
        let started = Date()
        var socket: TCPSocket?
        defer { cancel.detach(); socket?.close() }
        do {
            emit(.connecting)
            guard files.count <= Limits.maxOfferFiles else { throw ProtocolError("too many files in one transfer") }
            let s = try TCPSocket.connect(host: host, port: port, timeout: TransferEngine.controlTimeout,
                                          isCancelled: { cancel.isCancelled })
            socket = s
            let fio = FrameIO(socket: s)
            cancel.attach { [weak fio] in fio?.cancelAndShutdown(reason: "sender cancelled") }
            s.setTimeout(TransferEngine.controlTimeout)
            let offer = Offer(transferId: UUID().uuidString.lowercased(), files: files.map { $0.meta })

            try fio.write(.hello, Hello(version: version, deviceName: deviceName(),
                                        publicKeyB64: b64(identity.publicKey), instanceId: instanceId).encodeBytes())
            let (ackType, ackPayload) = try fio.read()
            guard ackType == .helloAck else { throw ProtocolError("expected HELLO_ACK, got \(ackType)") }
            let ack = try ackPayload.decodeJSON(HelloAck.self)
            let keys = try SessionCrypto.deriveSession(identity: identity, peerPublicKey: try unb64(ack.publicKeyB64), isInitiator: true)
            fio.writeCipher = RecordCipher(key: keys.sendKey)
            fio.readCipher = RecordCipher(key: keys.recvKey)
            // No read timeout from here (the human may take a while to accept; dead peers are caught by
            // keepalive), but never block forever writing to a peer that stopped reading.
            s.setTimeouts(receive: 0, send: TransferEngine.dataTimeout)
            emit(.secured(peerName: TransferEngine.displayName(ack.deviceName), sas: keys.sas))

            let offerBytes = try offer.encodeBytes()
            guard offerBytes.count < FrameIO.maxFrame - 64 else {
                throw ProtocolError("too many files for one transfer — send them in smaller batches")
            }
            try fio.write(.offer, offerBytes)
            emit(.awaitingAccept(offer))
            let (ansType, ansPayload) = try fio.read()
            switch ansType {
            case .accept: break
            case .decline: emit(.declinedByPeer); return
            case .cancel: emit(.cancelled); return
            default: throw ProtocolError("expected ACCEPT/DECLINE")
            }

            let accept = try ansPayload.decodeJSON(Accept.self)
            let tail: (TransferEvent) -> Void = emit
            if ack.version >= 3 && accept.delta && accept.push {
                try sendPush(fio, offer, files, started, cancel, tail)
            } else if ack.version >= 2 && accept.delta {
                try sendDelta(fio, offer, files, started, cancel, tail)
            } else {
                try sendMonolithic(fio, offer, files, started, cancel, tail)
            }
        } catch {
            if cancel.isCancelled || error is SessionCancelled { emit(.cancelled) }
            else { emit(.failed(describe(error))) }
        }
    }

    private func sendMonolithic(_ io: FrameIO, _ offer: Offer, _ files: [OutgoingFile], _ started: Date,
                                _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let total = max(files.reduce(0) { $0 + $1.meta.size }, 1)
        var digest = SHA256()
        var sent: Int64 = 0
        var meter = RateMeter()
        for (index, file) in files.enumerated() {
            try io.write(.data, be32(UInt32(index)))      // file-start marker, also materialises empty files
            let r = try FileReader(file.url)
            defer { r.close() }
            while true {
                if cancel.isCancelled { throw SessionCancelled() }
                let chunk = try r.read(TransferEngine.chunkSize)
                if chunk.isEmpty { break }
                try io.write(.data, be32(UInt32(index)) + chunk)
                digest.update(data: chunk)
                sent += Int64(chunk.count); meter.add(chunk.count)
                if let bps = meter.tick(force: sent == total) {
                    emit(.progress(bytesDone: sent, bytesTotal: total, bytesPerSec: bps, currentFile: file.meta, bytesSkipped: 0))
                }
            }
        }
        let sha = hex([UInt8](digest.finalize()))
        try io.write(.done, Done(transferId: offer.transferId, sha256: sha).encodeBytes())

        let (t, p) = try io.read()
        if t == .cancel { emit(.cancelled); return }
        guard t == .done else { throw ProtocolError("expected DONE echo, got \(t)") }
        let echo = try p.decodeJSON(Done.self)
        if echo.sha256.lowercased() != sha { emit(.failed("integrity check failed")); return }
        emit(.completed(offer, elapsedMs: ms(since: started)))
    }

    private func sendDelta(_ io: FrameIO, _ offer: Offer, _ files: [OutgoingFile], _ started: Date,
                           _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let total = max(files.reduce(0) { $0 + $1.meta.size }, 1)
        var roots: [String] = []
        var sent: Int64 = 0
        var cached: Int64 = 0
        var meter = RateMeter()

        for (index, file) in files.enumerated() {
            // pass 1: FastCDC + BLAKE3 per chunk and per file, remembering offsets
            var chunks: [ChunkEntry] = []
            var offs: [Int64] = []
            let fileHasher = Blake3()
            try FastCdc.chunks(of: file.url) { bytes, off in
                if cancel.isCancelled { throw SessionCancelled() }
                chunks.append(ChunkEntry(len: bytes.count, hash: Blake3.hash(bytes)))
                offs.append(off)
                fileHasher.update(bytes)
            }
            roots.append(fileHasher.hexDigest())

            let per = ChunkCodec.entriesPerPart
            let parts = max(1, (chunks.count + per - 1) / per)
            for p in 0..<parts {
                let slice = chunks[(p * per)..<min(chunks.count, (p + 1) * per)]
                try io.write(.chunkMap, ChunkCodec.encodeMapPart(fileIndex: index, part: p, parts: parts, entries: slice))
            }

            let (reqType, reqPayload) = try io.read()
            switch reqType {
            case .cancel: emit(.cancelled); return
            case .chunkReq: break
            default: throw ProtocolError("expected CHUNK_REQ, got \(reqType)")
            }
            let need = Set(try ChunkCodec.decodeRequest(reqPayload).indices)
            if need.contains(where: { $0 < 0 || $0 >= chunks.count }) { throw ProtocolError("CHUNK_REQ index out of range") }
            let needBytes = need.reduce(Int64(0)) { $0 + Int64(chunks[$1].len) }
            cached += file.meta.size - needBytes

            if !need.isEmpty {
                let r = try FileReader(file.url)
                defer { r.close() }
                for ci in need.sorted() {
                    if cancel.isCancelled { throw SessionCancelled() }
                    try r.seek(offs[ci])
                    let bytes = try r.readExactly(chunks[ci].len)
                    if Blake3.hash(bytes) != chunks[ci].hash { throw ProtocolError("\(file.meta.name) changed mid-transfer") }
                    try io.write(.chunk, ChunkCodec.encodeChunk(fileIndex: index, chunkIndex: ci, payload: bytes))
                    sent += Int64(bytes.count); meter.add(bytes.count)
                    if let bps = meter.tick() {
                        emit(.progress(bytesDone: sent + cached, bytesTotal: total, bytesPerSec: bps, currentFile: file.meta, bytesSkipped: cached))
                    }
                }
            }
        }
        emit(.progress(bytesDone: sent + cached, bytesTotal: total, bytesPerSec: 0,
                       currentFile: files.last?.meta ?? FileMeta(name: "", size: 0), bytesSkipped: cached))
        try finishSend(io, offer, roots, started, emit)
    }

    /// v3: no manifest — each chunk is cut, hashed and sent in one streaming pass.
    private func sendPush(_ io: FrameIO, _ offer: Offer, _ files: [OutgoingFile], _ started: Date,
                          _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let total = max(files.reduce(0) { $0 + $1.meta.size }, 1)
        var roots: [String] = []
        var sent: Int64 = 0
        var meter = RateMeter()

        for (index, file) in files.enumerated() {
            let fileHasher = Blake3()
            var count = 0
            var fileBytes: Int64 = 0
            try FastCdc.chunks(of: file.url) { bytes, _ in
                if cancel.isCancelled { throw SessionCancelled() }
                let hash = Blake3.hash(bytes)
                fileHasher.update(bytes)
                try io.write(.pushChunk, ChunkCodec.encodePush(fileIndex: index, hash: hash, payload: bytes))
                count += 1; fileBytes += Int64(bytes.count)
                sent += Int64(bytes.count); meter.add(bytes.count)
                if let bps = meter.tick() {
                    emit(.progress(bytesDone: sent, bytesTotal: total, bytesPerSec: bps, currentFile: file.meta, bytesSkipped: 0))
                }
            }
            if fileBytes != file.meta.size { throw ProtocolError("\(file.meta.name) changed mid-transfer") }
            let root = fileHasher.digest()
            try io.write(.pushEnd, ChunkCodec.encodePushEnd(fileIndex: index, count: count, root: root))
            roots.append(hex(root))
        }
        emit(.progress(bytesDone: sent, bytesTotal: total, bytesPerSec: 0,
                       currentFile: files.last?.meta ?? FileMeta(name: "", size: 0), bytesSkipped: 0))
        try finishSend(io, offer, roots, started, emit)
    }

    private func finishSend(_ io: FrameIO, _ offer: Offer, _ roots: [String], _ started: Date,
                            _ emit: (TransferEvent) -> Void) throws {
        try io.write(.done, Done(transferId: offer.transferId, sha256: "", roots: roots).encodeBytes())
        let (t, p) = try io.read()
        if t == .cancel { emit(.cancelled); return }
        guard t == .done else { throw ProtocolError("expected DONE echo, got \(t)") }
        let echo = try p.decodeJSON(Done.self)
        // v2/v3 completion is only trusted if the receiver proves it holds the same per-file roots.
        guard let r = echo.roots, r == roots else { emit(.failed("integrity check failed")); return }
        emit(.completed(offer, elapsedMs: ms(since: started)))
    }

    // MARK: receiver

    /// Serve one inbound connection. `decide` is called (blocking) once the decrypted OFFER arrives.
    public func receive(socket s: TCPSocket, cancel: CancelToken = CancelToken(),
                        decide: @escaping (_ peerName: String, _ offer: Offer) -> ReceiveDecision,
                        emit: @escaping (TransferEvent) -> Void) {
        let started = Date()
        // Any host on the LAN can open connections: bound how many sessions we run at once.
        guard gate.enter() else {
            s.close()           // silent: a flood of connections must not become a flood of UI events
            return
        }
        let fio = FrameIO(socket: s)
        defer { cancel.detach(); s.close(); gate.leave() }
        cancel.attach { [weak fio] in fio?.cancelAndShutdown(reason: "receiver cancelled") }
        do {
            s.setTimeout(TransferEngine.controlTimeout)
            let first: (FrameType, [UInt8])
            do { first = try fio.read() }
            catch is EOFError {
                // Connected, said nothing, left: a liveness probe (peers check we're still there) or a
                // port scan. Not a session — reporting it would flash a failed transfer in the UI.
                return
            }
            let (helloType, helloPayload) = first
            guard helloType == .hello else { throw ProtocolError("expected HELLO, got \(helloType)") }
            let hello = try helloPayload.decodeJSON(Hello.self)
            guard hello.version >= 1 else { throw ProtocolError("protocol v\(hello.version) unsupported") }
            let negotiated = min(hello.version, version)
            try fio.write(.helloAck, HelloAck(version: version, deviceName: deviceName(),
                                              publicKeyB64: b64(identity.publicKey), instanceId: instanceId).encodeBytes())
            let keys = try SessionCrypto.deriveSession(identity: identity, peerPublicKey: try unb64(hello.publicKeyB64), isInitiator: false)
            fio.writeCipher = RecordCipher(key: keys.sendKey)
            fio.readCipher = RecordCipher(key: keys.recvKey)
            s.setTimeout(TransferEngine.dataTimeout)
            let peerName = TransferEngine.displayName(hello.deviceName)
            emit(.secured(peerName: peerName, sas: keys.sas))

            let (offerType, offerPayload) = try fio.read()
            guard offerType == .offer else { throw ProtocolError("expected OFFER, got \(offerType)") }
            let offer = try offerPayload.decodeJSON(Offer.self)
            _ = try TransferEngine.validate(offer)

            // The human may take a while to tap Accept — don't let the idle timeout fire meanwhile.
            s.setTimeout(0)
            let decision = decide(peerName, offer)
            s.setTimeout(TransferEngine.dataTimeout)

            switch decision {
            case .reject(let reason):
                try fio.write(.decline, Decline(transferId: offer.transferId, reason: reason).encodeBytes())
                emit(.declinedByPeer)
            case .accept(let sinkFor, let delta):
                let useDelta = negotiated >= 2 && delta != nil
                let metas = delta.map { TransferEngine.uniqueMetas(offer.files, $0) } ?? offer.files
                let push = negotiated >= 3 && (delta?.canPush(metas) ?? false)
                try fio.write(.accept, Accept(transferId: offer.transferId, delta: useDelta, push: push).encodeBytes())
                if useDelta, let delta {
                    delta.prunePool()
                    if push { try receivePush(fio, offer, metas, delta, started, cancel, emit) }
                    else { try receiveDelta(s, fio, offer, metas, delta, started, cancel, emit) }
                } else {
                    try receiveMonolithic(fio, offer, sinkFor, started, cancel, emit)
                }
            }
        } catch {
            if cancel.isCancelled || error is SessionCancelled { emit(.cancelled) }
            else { emit(.failed(describe(error))) }
        }
    }

    private func receiveMonolithic(_ io: FrameIO, _ offer: Offer, _ sinkFor: (FileMeta) throws -> OutputSink,
                                   _ started: Date, _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let total = offer.files.reduce(0) { $0 + $1.size }
        var digest = SHA256()
        var received: Int64 = 0
        var currentWritten: Int64 = 0
        var current = -1
        var sink: OutputSink?
        var meter = RateMeter()
        defer { sink?.close() }

        while true {
            let (type, payload) = try io.read()
            switch type {
            case .data:
                guard payload.count >= 4 else { throw ProtocolError("short DATA frame") }
                let fileIndex = Int(Int32(bitPattern: readBE32(payload, 0)))
                if fileIndex != current {
                    if fileIndex <= current || fileIndex >= offer.files.count { throw ProtocolError("bad file index \(fileIndex)") }
                    sink?.close()
                    current = fileIndex
                    currentWritten = 0
                    sink = try sinkFor(offer.files[fileIndex])
                }
                let body = Array(payload[4...])
                // A peer must not stream more than it offered (that would fill the disk unattended).
                currentWritten += Int64(body.count)
                if currentWritten > offer.files[current].size { throw ProtocolError("\(offer.files[current].name) overruns its offered size") }
                try sink!.write(body)
                digest.update(data: body)
                received += Int64(body.count); meter.add(body.count)
                if let bps = meter.tick(force: total > 0 && received == total) {
                    emit(.progress(bytesDone: received, bytesTotal: total, bytesPerSec: bps,
                                   currentFile: offer.files[current], bytesSkipped: 0))
                }
            case .done:
                let done = try payload.decodeJSON(Done.self)
                let local = hex([UInt8](digest.finalize()))
                try io.write(.done, Done(transferId: offer.transferId, sha256: local).encodeBytes())
                if local != done.sha256.lowercased() { emit(.failed("integrity check failed")); return }
                emit(.completed(offer, elapsedMs: ms(since: started)))
                return
            case .cancel: emit(.cancelled); return
            default: throw ProtocolError("unexpected \(type) during payload")
            }
        }
    }

    private func receiveDelta(_ socket: TCPSocket, _ io: FrameIO, _ offer: Offer, _ metas: [FileMeta], _ delta: DeltaReceiver,
                              _ started: Date, _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let fm = FileManager.default
        let total = max(offer.files.reduce(0) { $0 + $1.size }, 1)
        socket.setTimeout(TransferEngine.deltaTimeout)
        let pool = delta.stagingPool()
        try fm.createDirectory(at: pool, withIntermediateDirectories: true)
        var roots: [String] = []
        var received: Int64 = 0
        var cached: Int64 = 0
        var meter = RateMeter()

        for index in offer.files.indices {
            let meta = metas[index]          // destination-unique (see `uniqueMetas`)
            // 1. manifest (possibly several parts)
            var manifest: [ChunkEntry] = []
            var parts = -1, nextPart = 0
            // Every chunk but the last is ≥ minSize, so a legitimate manifest has at most size/min + 1 entries.
            let maxEntries = meta.size / Int64(FastCdc.minSize) + 1
            let maxParts = Int(maxEntries / Int64(ChunkCodec.entriesPerPart)) + 1
            while parts < 0 || nextPart < parts {
                let (type, payload) = try io.read()
                switch type {
                case .chunkMap:
                    let mp = try ChunkCodec.decodeMapPart(payload)
                    if mp.fileIndex != index || mp.part != nextPart { throw ProtocolError("unexpected chunk-map part \(mp.fileIndex)/\(mp.part)") }
                    if parts >= 0 && mp.parts != parts { throw ProtocolError("inconsistent chunk-map parts") }
                    if mp.parts > maxParts { throw ProtocolError("too many chunk-map parts for \(meta.name)") }
                    parts = mp.parts; nextPart += 1
                    manifest += mp.entries
                    if Int64(manifest.count) > maxEntries { throw ProtocolError("manifest too large for \(meta.name)") }
                case .cancel: emit(.cancelled); return
                default: throw ProtocolError("expected CHUNK_MAP, got \(type)")
                }
            }
            if manifest.reduce(Int64(0), { $0 + Int64($1.len) }) != meta.size { throw ProtocolError("manifest size mismatch for \(meta.name)") }
            if manifest.dropLast().contains(where: { $0.len < FastCdc.minSize }) { throw ProtocolError("undersized chunk in manifest for \(meta.name)") }

            // 2. local reuse sources: staging pool + baseline. Pooled chunks count only if they hash to their name.
            for name in (try? fm.contentsOfDirectory(atPath: pool.path)) ?? [] where name.hasSuffix(".tmp") {
                try? fm.removeItem(at: pool.appendingPathComponent(name))
            }
            var poolFiles: [String: URL] = [:]
            for name in (try? fm.contentsOfDirectory(atPath: pool.path)) ?? [] where name.hasSuffix(".bin") && name.count == 68 {
                poolFiles[String(name.dropLast(4))] = pool.appendingPathComponent(name)
            }
            var poolOk: [String: Bool] = [:]
            func pooled(_ e: ChunkEntry) -> Bool {
                if let ok = poolOk[e.key] { return ok }
                var good = false
                if let f = poolFiles[e.key], let data = try? Data(contentsOf: f), data.count == e.len {
                    good = Blake3.hash(data) == e.hash
                    if !good { try? fm.removeItem(at: f); poolFiles[e.key] = nil }
                }
                poolOk[e.key] = good
                return good
            }
            let baseline = delta.baselineFor(meta)
            var baseIndex: [String: (off: Int64, len: Int)] = [:]
            if let baseline {
                var off: Int64 = 0
                for e in baseline.chunks { if baseIndex[e.key] == nil { baseIndex[e.key] = (off, e.len) }; off += Int64(e.len) }
            }
            var requested: [(key: String, index: Int)] = []
            var requestedKeys = Set<String>()
            for (i, e) in manifest.enumerated() where baseIndex[e.key] == nil && !pooled(e) && !requestedKeys.contains(e.key) {
                requestedKeys.insert(e.key); requested.append((e.key, i))
            }
            cached += meta.size - requested.reduce(Int64(0)) { $0 + Int64(manifest[$1.index].len) }

            // 3. ask for only what's missing
            try io.write(.chunkReq, ChunkCodec.encodeRequest(fileIndex: index, indices: requested.map { $0.index }))
            emit(.progress(bytesDone: received + cached, bytesTotal: total, bytesPerSec: 0, currentFile: meta, bytesSkipped: cached))

            // 4. receive missing chunks → verify → staging pool
            let wantIndex = Set(requested.map { $0.index })
            var pending = requestedKeys
            while !pending.isEmpty {
                let (type, payload) = try io.read()
                switch type {
                case .chunk:
                    let c = try ChunkCodec.decodeChunk(payload)
                    if c.fileIndex != index || !wantIndex.contains(c.chunkIndex) { throw ProtocolError("unexpected chunk \(c.fileIndex)/\(c.chunkIndex)") }
                    let want = manifest[c.chunkIndex]
                    if c.payload.count != want.len || Blake3.hash(c.payload) != want.hash { throw ProtocolError("chunk \(c.chunkIndex) failed hash check") }
                    guard pending.remove(want.key) != nil else { throw ProtocolError("duplicate chunk \(c.chunkIndex)") }
                    let tmp = pool.appendingPathComponent("\(want.key).\(DispatchTime.now().uptimeNanoseconds).tmp")
                    try Data(c.payload).write(to: tmp)
                    let bin = pool.appendingPathComponent("\(want.key).bin")
                    guard rename(tmp.path, bin.path) == 0 else { throw ProtocolError("staging write failed") }
                    poolFiles[want.key] = bin
                    received += Int64(c.payload.count); meter.add(c.payload.count)
                    if let bps = meter.tick(force: pending.isEmpty) {
                        emit(.progress(bytesDone: received + cached, bytesTotal: total, bytesPerSec: bps, currentFile: meta, bytesSkipped: cached))
                    }
                case .cancel: emit(.cancelled); return
                default: throw ProtocolError("expected CHUNK, got \(type)")
                }
            }

            // 5. assemble. Fast path: manifest identical to the baseline index and file still hashes to its root.
            var fastOk = false
            if let baseline, let root = baseline.root, baseline.chunks == manifest,
               let rootNow = try? hashFile(baseline.file), rootNow == root {
                fastOk = true
                roots.append(root)
            }
            if !fastOk {
                let target = delta.targetFor(meta)
                let tmp = target.deletingLastPathComponent()
                    .appendingPathComponent("\(target.lastPathComponent).\(DispatchTime.now().uptimeNanoseconds).part")
                fm.createFile(atPath: tmp.path, contents: nil)
                let out = try FileHandle(forWritingTo: tmp)
                let fileHasher = Blake3()
                let baseHandle = try baseline.map { try FileHandle(forReadingFrom: $0.file) }
                do {
                    for e in manifest {
                        if let pf = poolFiles[e.key] {
                            let data = try Data(contentsOf: pf)
                            try out.write(contentsOf: data)
                            fileHasher.update(data)
                        } else {
                            guard let (off, len) = baseIndex[e.key] else { throw ProtocolError("chunk \(e.key.prefix(8)) missing locally") }
                            try baseHandle!.seek(toOffset: UInt64(off))
                            let data = try baseHandle!.read(upToCount: len) ?? Data()
                            if data.count != len || Blake3.hash(data) != e.hash {
                                delta.invalidate(meta)
                                throw ProtocolError("baseline chunk \(e.key.prefix(8)) corrupt")
                            }
                            try out.write(contentsOf: data)
                            fileHasher.update(data)
                        }
                    }
                    try out.close()
                    try baseHandle?.close()
                } catch {
                    try? out.close(); try? baseHandle?.close()
                    try? fm.removeItem(at: tmp)
                    throw error
                }
                guard rename(tmp.path, target.path) == 0 else {
                    try? fm.removeItem(at: tmp)
                    throw ProtocolError("could not move \(meta.name) into place")
                }
                let rootHex = fileHasher.hexDigest()
                roots.append(rootHex)
                delta.publishIndex(file: target, chunks: manifest, root: rootHex)
            }

            // pool hygiene: chunks now live inside the assembled file
            for e in manifest { try? fm.removeItem(at: pool.appendingPathComponent("\(e.key).bin")) }
        }
        try finishReceive(io, offer, metas, delta, roots, started, emit)
    }

    private func receivePush(_ io: FrameIO, _ offer: Offer, _ metas: [FileMeta], _ delta: DeltaReceiver, _ started: Date,
                             _ cancel: CancelToken, _ emit: (TransferEvent) -> Void) throws {
        let fm = FileManager.default
        let total = max(offer.files.reduce(0) { $0 + $1.size }, 1)
        var roots: [String] = []
        var received: Int64 = 0
        var meter = RateMeter()

        for index in offer.files.indices {
            let meta = metas[index]          // destination-unique (see `uniqueMetas`)
            let target = delta.targetFor(meta)
            let part = target.deletingLastPathComponent()
                .appendingPathComponent("\(target.lastPathComponent).\(DispatchTime.now().uptimeNanoseconds).part")
            fm.createFile(atPath: part.path, contents: nil)
            let out = try FileHandle(forWritingTo: part)
            var manifest: [ChunkEntry] = []
            let fileHasher = Blake3()
            var fileBytes: Int64 = 0
            var lastChunkLen = Int.max
            var done = false
            defer {
                try? out.close()
                if !done { salvage(part, manifest, delta.stagingPool()) }
                try? fm.removeItem(at: part)
            }
            loop: while true {
                let (type, payload) = try io.read()
                switch type {
                case .pushChunk:
                    let c = try ChunkCodec.decodePush(payload)
                    if c.fileIndex != index { throw ProtocolError("unexpected push chunk for file \(c.fileIndex)") }
                    // Only the final chunk of a file may be shorter than minSize — otherwise a peer could make us
                    // keep a manifest entry (and 40 bytes of memory) per few bytes received.
                    if lastChunkLen < FastCdc.minSize { throw ProtocolError("undersized push chunk in \(meta.name)") }
                    lastChunkLen = c.payload.count
                    if fileBytes + Int64(c.payload.count) > meta.size { throw ProtocolError("\(meta.name) overruns its offered size") }
                    let h = Blake3.hash(c.payload)
                    if h != c.hash { throw ProtocolError("push chunk failed hash check") }
                    try out.write(contentsOf: Data(c.payload))
                    fileHasher.update(c.payload)
                    manifest.append(ChunkEntry(len: c.payload.count, hash: h))
                    fileBytes += Int64(c.payload.count)
                    received += Int64(c.payload.count); meter.add(c.payload.count)
                    if let bps = meter.tick() {
                        emit(.progress(bytesDone: received, bytesTotal: total, bytesPerSec: bps, currentFile: meta, bytesSkipped: 0))
                    }
                case .pushEnd:
                    let end = try ChunkCodec.decodePushEnd(payload)
                    if end.fileIndex != index || end.count != manifest.count || fileBytes != meta.size {
                        throw ProtocolError("\(meta.name) ended inconsistently")
                    }
                    let root = fileHasher.digest()
                    if root != end.root { throw ProtocolError("\(meta.name) failed integrity check") }
                    try out.close()
                    guard rename(part.path, target.path) == 0 else { throw ProtocolError("could not move \(meta.name) into place") }
                    let rootHex = hex(root)
                    delta.publishIndex(file: target, chunks: manifest, root: rootHex)
                    roots.append(rootHex)
                    done = true
                    break loop
                case .cancel: emit(.cancelled); return
                default: throw ProtocolError("expected PUSH_CHUNK, got \(type)")
                }
            }
        }
        emit(.progress(bytesDone: received, bytesTotal: total, bytesPerSec: 0,
                       currentFile: offer.files.last ?? FileMeta(name: "", size: 0), bytesSkipped: 0))
        try finishReceive(io, offer, metas, delta, roots, started, emit)
    }

    /// Move verified chunks of an interrupted push into the staging pool so a retry resumes. Best effort.
    private func salvage(_ part: URL, _ chunks: [ChunkEntry], _ pool: URL) {
        guard !chunks.isEmpty, let h = try? FileHandle(forReadingFrom: part) else { return }
        defer { try? h.close() }
        let fm = FileManager.default
        try? fm.createDirectory(at: pool, withIntermediateDirectories: true)
        for e in chunks {
            guard let data = try? h.read(upToCount: e.len), data.count == e.len else { return }
            let bin = pool.appendingPathComponent("\(e.key).bin")
            if fm.fileExists(atPath: bin.path) { continue }
            let tmp = pool.appendingPathComponent("\(e.key).\(DispatchTime.now().uptimeNanoseconds).tmp")
            guard (try? data.write(to: tmp)) != nil else { return }
            _ = rename(tmp.path, bin.path)
        }
    }

    private func finishReceive(_ io: FrameIO, _ offer: Offer, _ metas: [FileMeta], _ delta: DeltaReceiver, _ roots: [String],
                               _ started: Date, _ emit: (TransferEvent) -> Void) throws {
        let (type, payload) = try io.read()
        if type == .cancel { emit(.cancelled); return }
        guard type == .done else { throw ProtocolError("expected DONE, got \(type)") }
        let done = try payload.decodeJSON(Done.self)
        try io.write(.done, Done(transferId: offer.transferId, sha256: "", roots: roots).encodeBytes())
        guard let r = done.roots, r == roots else {
            metas.forEach { delta.invalidate($0) }
            emit(.failed("integrity check failed"))
            return
        }
        emit(.completed(offer, elapsedMs: ms(since: started)))
    }

    // MARK: helpers

    private func hashFile(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        let b = Blake3()
        while true {
            let d = try h.read(upToCount: 256 * 1024) ?? Data()
            if d.isEmpty { break }
            b.update(d)
        }
        return b.hexDigest()
    }

    private func ms(since d: Date) -> Int64 { Int64(Date().timeIntervalSince(d) * 1000) }

    private func describe(_ e: Error) -> String {
        if let p = e as? ProtocolError { return p.message }
        if e is EOFError { return "connection closed by peer" }
        return e.localizedDescription
    }
}
