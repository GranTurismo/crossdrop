import Foundation

/// One content-defined chunk: length + BLAKE3 identity.
public struct ChunkEntry: Equatable {
    public let len: Int
    public let hash: [UInt8]
    public init(len: Int, hash: [UInt8]) { self.len = len; self.hash = hash }
    public var key: String { hex(hash) }
}

public struct Baseline {
    public let file: URL
    public let chunks: [ChunkEntry]
    public let root: String?
    public init(file: URL, chunks: [ChunkEntry], root: String?) { self.file = file; self.chunks = chunks; self.root = root }
}

/// App-provided storage plumbing for protocol v2/v3 delta/resume receives.
public protocol DeltaReceiver: AnyObject {
    func stagingPool() -> URL
    func baselineFor(_ meta: FileMeta) -> Baseline?
    func targetFor(_ meta: FileMeta) -> URL
    func publishIndex(file: URL, chunks: [ChunkEntry], root: String)
    func canPush(_ files: [FileMeta]) -> Bool
    func invalidate(_ meta: FileMeta)

    /// Housekeeping before a transfer: drop abandoned staging chunks / half-written files. Default: nothing.
    func prunePool()
}

extension DeltaReceiver {
    public func prunePool() {}
}

enum ChunkCodec {
    static let entriesPerPart = 16_384

    static func encodeMapPart(fileIndex: Int, part: Int, parts: Int, entries: ArraySlice<ChunkEntry>) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(16 + entries.count * 36)
        out += be32(UInt32(fileIndex)); out += be32(UInt32(part)); out += be32(UInt32(parts)); out += be32(UInt32(entries.count))
        for e in entries { out += be32(UInt32(e.len)); out += e.hash }
        return out
    }

    struct MapPart { let fileIndex: Int; let part: Int; let parts: Int; let entries: [ChunkEntry] }

    static func decodeMapPart(_ p: [UInt8]) throws -> MapPart {
        guard p.count >= 16 else { throw ProtocolError("short CHUNK_MAP") }
        let fi = Int(Int32(bitPattern: readBE32(p, 0))), part = Int(Int32(bitPattern: readBE32(p, 4)))
        let parts = Int(Int32(bitPattern: readBE32(p, 8))), count = Int(Int32(bitPattern: readBE32(p, 12)))
        guard count >= 0, p.count == 16 + count * 36 else { throw ProtocolError("bad CHUNK_MAP entry count \(count)") }
        guard part >= 0, parts >= 1, part < parts, fi >= 0 else { throw ProtocolError("bad CHUNK_MAP header") }
        var entries = [ChunkEntry](); entries.reserveCapacity(count)
        var o = 16
        for _ in 0..<count {
            let len = Int(Int32(bitPattern: readBE32(p, o)))
            guard len > 0, len <= FastCdc.maxSize else { throw ProtocolError("bad chunk len \(len)") }
            entries.append(ChunkEntry(len: len, hash: Array(p[o + 4..<o + 36])))
            o += 36
        }
        return MapPart(fileIndex: fi, part: part, parts: parts, entries: entries)
    }

    static func encodeRequest(fileIndex: Int, indices: [Int]) -> [UInt8] {
        var out = be32(UInt32(fileIndex)) + be32(UInt32(indices.count))
        for i in indices { out += be32(UInt32(i)) }
        return out
    }

    static func decodeRequest(_ p: [UInt8]) throws -> (fileIndex: Int, indices: [Int]) {
        guard p.count >= 8 else { throw ProtocolError("short CHUNK_REQ") }
        let fi = Int(Int32(bitPattern: readBE32(p, 0))), count = Int(Int32(bitPattern: readBE32(p, 4)))
        guard count >= 0, p.count == 8 + count * 4, fi >= 0 else { throw ProtocolError("bad CHUNK_REQ count \(count)") }
        return (fi, (0..<count).map { Int(Int32(bitPattern: readBE32(p, 8 + $0 * 4))) })
    }

    static func encodePush(fileIndex: Int, hash: [UInt8], payload: [UInt8]) -> [UInt8] {
        be32(UInt32(fileIndex)) + hash + payload
    }

    static func decodePush(_ p: [UInt8]) throws -> (fileIndex: Int, hash: [UInt8], payload: [UInt8]) {
        guard p.count > 36 else { throw ProtocolError("short PUSH_CHUNK") }
        let fi = Int(Int32(bitPattern: readBE32(p, 0)))
        guard fi >= 0 else { throw ProtocolError("bad PUSH_CHUNK header") }
        let data = Array(p[36...])
        guard data.count <= FastCdc.maxSize else { throw ProtocolError("oversized PUSH_CHUNK") }
        return (fi, Array(p[4..<36]), data)
    }

    static func encodePushEnd(fileIndex: Int, count: Int, root: [UInt8]) -> [UInt8] {
        be32(UInt32(fileIndex)) + be32(UInt32(count)) + root
    }

    static func decodePushEnd(_ p: [UInt8]) throws -> (fileIndex: Int, count: Int, root: [UInt8]) {
        guard p.count == 40 else { throw ProtocolError("bad PUSH_END") }
        let fi = Int(Int32(bitPattern: readBE32(p, 0))), count = Int(Int32(bitPattern: readBE32(p, 4)))
        guard fi >= 0, count >= 0 else { throw ProtocolError("bad PUSH_END header") }
        return (fi, count, Array(p[8..<40]))
    }

    static func encodeChunk(fileIndex: Int, chunkIndex: Int, payload: [UInt8]) -> [UInt8] {
        be32(UInt32(fileIndex)) + be32(UInt32(chunkIndex)) + payload
    }

    static func decodeChunk(_ p: [UInt8]) throws -> (fileIndex: Int, chunkIndex: Int, payload: [UInt8]) {
        guard p.count >= 8 else { throw ProtocolError("short CHUNK") }
        let fi = Int(Int32(bitPattern: readBE32(p, 0))), ci = Int(Int32(bitPattern: readBE32(p, 4)))
        guard fi >= 0, ci >= 0 else { throw ProtocolError("bad CHUNK header") }
        return (fi, ci, Array(p[8...]))
    }
}
