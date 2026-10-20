import Foundation

/// FastCDC-style content-defined chunking — bit-exact port of `core/delta/FastCdc.kt`.
/// Sender and receiver (on any platform) must cut identical boundaries.
public enum FastCdc {
    public static let minSize = 16 * 1024
    public static let avgSize = 64 * 1024
    public static let maxSize = 256 * 1024

    /// Same SplitMix64-seeded table as the Kotlin core, truncated to the low 32 bits.
    static let gear: [UInt32] = {
        var z: UInt64 = 0x243F6A8885A308D3
        return (0..<256).map { _ in
            z = z &+ 0x9E3779B97F4A7C15
            var r = z
            r = (r ^ (r >> 30)) &* 0xBF58476D1CE4E5B9
            r = (r ^ (r >> 27)) &* 0x94D049BB133111EB
            return UInt32(truncatingIfNeeded: r ^ (r >> 31))
        }
    }()
    private static let gearPtr: UnsafePointer<UInt32> = {
        let p = UnsafeMutablePointer<UInt32>.allocate(capacity: 256)
        for i in 0..<256 { p[i] = gear[i] }
        return UnsafePointer(p)
    }()

    static let maskS: UInt64 = (1 << 17) - 1
    static let maskL: UInt64 = (1 << 15) - 1

    /// Incremental splitter. Feed bytes with `feed`, collect finished chunks via the callback,
    /// then call `finish` for the trailing partial chunk.
    ///
    /// Byte-for-byte equivalent to the Kotlin loop (append the byte; once the chunk has `minSize`
    /// bytes roll the gear hash and test the mask) but the first `minSize - 1` bytes of every chunk
    /// are block-copied and the scan runs on raw pointers with no per-byte bounds checks.
    public final class Splitter {
        private let chunk = UnsafeMutablePointer<UInt8>.allocate(capacity: FastCdc.maxSize)
        private var chunkLen = 0
        private var fp: UInt64 = 0
        private var offset: Int64 = 0

        public init() {}
        deinit { chunk.deallocate() }

        public func feed(_ data: UnsafeBufferPointer<UInt8>, emit: ([UInt8], Int64) throws -> Void) rethrows {
            guard let base = data.baseAddress else { return }
            let n = data.count
            var p = 0
            let gear = FastCdc.gearPtr
            while p < n {
                if chunkLen < FastCdc.minSize - 1 {
                    // cannot cut yet and the hash is not rolling: plain copy
                    let take = min(FastCdc.minSize - 1 - chunkLen, n - p)
                    memcpy(chunk + chunkLen, base + p, take)
                    chunkLen += take; p += take
                    continue
                }
                let limit = min(n - p, FastCdc.maxSize - chunkLen)
                var consumed = limit
                var cut = chunkLen + limit >= FastCdc.maxSize          // forced cut at maxSize
                var h = fp
                var len = chunkLen
                var i = 0
                while i < limit {
                    h = (h << 1) &+ UInt64(gear[Int(base[p + i])])
                    len += 1
                    let mask = len < FastCdc.avgSize ? FastCdc.maskS : FastCdc.maskL
                    if (h & mask) == 0 { consumed = i + 1; cut = true; break }
                    i += 1
                }
                fp = h
                memcpy(chunk + chunkLen, base + p, consumed)
                chunkLen += consumed; p += consumed
                if cut {
                    try emit(Array(UnsafeBufferPointer(start: chunk, count: chunkLen)), offset)
                    offset += Int64(chunkLen)
                    chunkLen = 0; fp = 0
                }
            }
        }

        public func finish(emit: ([UInt8], Int64) throws -> Void) rethrows {
            if chunkLen > 0 {
                try emit(Array(UnsafeBufferPointer(start: chunk, count: chunkLen)), offset)
                offset += Int64(chunkLen)
                chunkLen = 0; fp = 0
            }
        }
    }

    /// Split a whole file by streaming it through `Splitter`.
    public static func chunks(of url: URL, emit: ([UInt8], Int64) throws -> Void) throws {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        let splitter = Splitter()
        while true {
            let data = try h.read(upToCount: 1024 * 1024) ?? Data()
            if data.isEmpty { break }
            try data.withUnsafeBytes { try splitter.feed($0.bindMemory(to: UInt8.self), emit: emit) }
        }
        try splitter.finish(emit: emit)
    }

    public static func split(_ data: [UInt8]) -> [([UInt8], Int64)] {
        var out: [([UInt8], Int64)] = []
        let splitter = Splitter()
        data.withUnsafeBufferPointer { splitter.feed($0) { out.append(($0, $1)) } }
        splitter.finish { out.append(($0, $1)) }
        return out
    }
}
