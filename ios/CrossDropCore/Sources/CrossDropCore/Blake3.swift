import Foundation

/// Pure-Swift BLAKE3 (hash mode only) — a port of `core/delta/Blake3.kt`.
/// Streaming hasher with the standard chunk stack, so multi-GB inputs cost O(1) memory.
///
/// The compression function is fully unrolled with the message schedule baked in (generated, see
/// `compress`), keeps state in locals, and `update` feeds whole 64-byte blocks straight from the
/// caller's buffer. That keeps checked (`-O`) builds fast: no array bounds checks sit on the hot path.
public final class Blake3 {
    private let cv = UnsafeMutablePointer<UInt32>.allocate(capacity: 8)
    private let block = UnsafeMutablePointer<UInt8>.allocate(capacity: 64)
    private let msg = UnsafeMutablePointer<UInt32>.allocate(capacity: 16)
    private let out16 = UnsafeMutablePointer<UInt32>.allocate(capacity: 16)
    /// Subtree chaining values, oldest → newest. 54 levels cover 2^64 chunks.
    private let stack = UnsafeMutablePointer<UInt32>.allocate(capacity: 54 * 8)
    private var stackCount = 0
    private var blockLen = 0
    private var blocksCompressed = 0
    private var chunkCounter: UInt64 = 0

    public init() {
        for i in 0..<8 { cv[i] = Blake3.iv[i] }
    }

    deinit {
        cv.deallocate(); block.deallocate(); msg.deallocate(); out16.deallocate(); stack.deallocate()
    }

    public func update(_ input: [UInt8]) { input.withUnsafeBufferPointer { update($0) } }

    public func update(_ input: Data) { input.withUnsafeBytes { update($0.bindMemory(to: UInt8.self)) } }

    public func update(_ input: UnsafeBufferPointer<UInt8>) {
        guard var p = input.baseAddress else { return }
        var n = input.count
        if blockLen > 0 {
            let take = min(64 - blockLen, n)
            memcpy(block + blockLen, p, take)
            blockLen += take; p += take; n -= take
            if n == 0 { return }               // a full block waits for more input (it may be the last)
            consumeBuffered()
        }
        // Whole blocks straight from the caller's memory; the final 1…64 bytes are always buffered.
        while n > 64 {
            Blake3.loadWords(UnsafeRawPointer(p), msg)
            consume(words: msg)
            p += 64; n -= 64
        }
        if n > 0 {
            memcpy(block, p, n)
            blockLen = n
        }
    }

    /// 32-byte digest. Consumes the hasher.
    public func digest() -> [UInt8] {
        // The final (possibly partial, possibly empty) block of the current chunk.
        memset(block + blockLen, 0, 64 - blockLen)
        Blake3.loadWords(UnsafeRawPointer(block), msg)
        var outCv = [UInt32](repeating: 0, count: 8)
        var curCv = (0..<8).map { cv[$0] }
        var curMsg = (0..<16).map { msg[$0] }
        var curCounter = chunkCounter
        var curLen = UInt32(blockLen)
        var curFlags = chunkFlags() | Blake3.chunkEnd
        var i = stackCount - 1
        while i >= 0 {
            // chaining value of the node so far, then parent it with the stack entry
            Blake3.compress(curCv, curMsg, curCounter, curLen, curFlags, out16, full: false)
            for k in 0..<8 { outCv[k] = out16[k] }
            curMsg = (0..<8).map { stack[i * 8 + $0] } + outCv
            curCv = Blake3.iv; curCounter = 0; curLen = 64; curFlags = Blake3.parent
            i -= 1
        }
        Blake3.compress(curCv, curMsg, 0, curLen, curFlags | Blake3.root, out16, full: false)
        var bytes = [UInt8](); bytes.reserveCapacity(32)
        for k in 0..<8 { let w = out16[k]; for b in 0..<4 { bytes.append(UInt8((w >> UInt32(8 * b)) & 0xFF)) } }
        return bytes
    }

    public func hexDigest() -> String { Blake3.hex(digest()) }

    // MARK: chunk machinery

    private func chunkFlags() -> UInt32 { blocksCompressed == 0 ? Blake3.chunkStart : 0 }

    /// The buffered block is full and more input follows, so it is not the last block of the stream.
    private func consumeBuffered() {
        Blake3.loadWords(UnsafeRawPointer(block), msg)
        consume(words: msg)
        blockLen = 0
    }

    /// Absorb one full 64-byte block (already in `words`) that is known not to be the stream's last.
    private func consume(words: UnsafeMutablePointer<UInt32>) {
        if blocksCompressed == Blake3.blocksPerChunk - 1 {
            // last block of a 1024-byte chunk: finish it and fold its CV into the tree
            Blake3.compress(cv, words, chunkCounter, 64, chunkFlags() | Blake3.chunkEnd, out16, full: false)
            var totalChunks = chunkCounter + 1
            var cur = [UInt32](repeating: 0, count: 8)
            for k in 0..<8 { cur[k] = out16[k] }
            while totalChunks & 1 == 0 {
                stackCount -= 1
                for k in 0..<8 { words[k] = stack[stackCount * 8 + k]; words[8 + k] = cur[k] }
                Blake3.compress(Blake3.ivPtr, words, 0, 64, Blake3.parent, out16, full: false)
                for k in 0..<8 { cur[k] = out16[k] }
                totalChunks >>= 1
            }
            for k in 0..<8 { stack[stackCount * 8 + k] = cur[k] }
            stackCount += 1
            for k in 0..<8 { cv[k] = Blake3.iv[k] }
            blocksCompressed = 0
            chunkCounter += 1
        } else {
            Blake3.compress(cv, words, chunkCounter, 64, chunkFlags(), out16, full: false)
            for k in 0..<8 { cv[k] = out16[k] }
            blocksCompressed += 1
        }
    }

    // MARK: constants

    private static let iv: [UInt32] = [
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    ]
    private static let ivPtr: UnsafePointer<UInt32> = {
        let p = UnsafeMutablePointer<UInt32>.allocate(capacity: 8)
        for i in 0..<8 { p[i] = iv[i] }
        return UnsafePointer(p)
    }()
    private static let chunkStart: UInt32 = 1
    private static let chunkEnd: UInt32 = 2
    private static let parent: UInt32 = 4
    private static let root: UInt32 = 8
    private static let blocksPerChunk = 16

    // MARK: compression

    @inline(__always)
    private static func rotr(_ x: UInt32, _ n: UInt32) -> UInt32 { (x &>> n) | (x &<< (32 &- n)) }

    @inline(__always)
    private static func g(_ a: inout UInt32, _ b: inout UInt32, _ c: inout UInt32, _ d: inout UInt32, _ x: UInt32, _ y: UInt32) {
        a = a &+ b &+ x; d = rotr(d ^ a, 16); c = c &+ d; b = rotr(b ^ c, 12)
        a = a &+ b &+ y; d = rotr(d ^ a, 8); c = c &+ d; b = rotr(b ^ c, 7)
    }

    /// Little-endian bytes → 16 words. (All supported Apple targets are little-endian.)
    @inline(__always)
    private static func loadWords(_ src: UnsafeRawPointer, _ dst: UnsafeMutablePointer<UInt32>) {
        for i in 0..<16 { dst[i] = UInt32(littleEndian: src.loadUnaligned(fromByteOffset: i * 4, as: UInt32.self)) }
    }

    private static func compress(_ cv: [UInt32], _ m: [UInt32], _ counter: UInt64, _ blockLen: UInt32,
                                 _ flags: UInt32, _ out: UnsafeMutablePointer<UInt32>, full: Bool) {
        cv.withUnsafeBufferPointer { c in m.withUnsafeBufferPointer { w in
            compress(c.baseAddress!, w.baseAddress!, counter, blockLen, flags, out, full: full)
        } }
    }

    /// Standard BLAKE3 compression. `out[0..<8]` = chaining value (state[i] ^ state[i+8]); with `full`
    /// the upper half (state[i+8] ^ cv[i]) is written to `out[8..<16]` too (XOF output).
    private static func compress(_ cv: UnsafePointer<UInt32>, _ m: UnsafePointer<UInt32>, _ counter: UInt64,
                                 _ blockLen: UInt32, _ flags: UInt32, _ out: UnsafeMutablePointer<UInt32>, full: Bool) {
        let m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3], m4 = m[4], m5 = m[5], m6 = m[6], m7 = m[7]
        let m8 = m[8], m9 = m[9], m10 = m[10], m11 = m[11], m12 = m[12], m13 = m[13], m14 = m[14], m15 = m[15]
        var s0 = cv[0], s1 = cv[1], s2 = cv[2], s3 = cv[3], s4 = cv[4], s5 = cv[5], s6 = cv[6], s7 = cv[7]
        var s8 = Blake3.iv0, s9 = Blake3.iv1, s10 = Blake3.iv2, s11 = Blake3.iv3
        var s12 = UInt32(truncatingIfNeeded: counter), s13 = UInt32(truncatingIfNeeded: counter >> 32)
        var s14 = blockLen, s15 = flags
        // round 0
        g(&s0, &s4, &s8, &s12, m0, m1)
        g(&s1, &s5, &s9, &s13, m2, m3)
        g(&s2, &s6, &s10, &s14, m4, m5)
        g(&s3, &s7, &s11, &s15, m6, m7)
        g(&s0, &s5, &s10, &s15, m8, m9)
        g(&s1, &s6, &s11, &s12, m10, m11)
        g(&s2, &s7, &s8, &s13, m12, m13)
        g(&s3, &s4, &s9, &s14, m14, m15)
        // round 1
        g(&s0, &s4, &s8, &s12, m2, m6)
        g(&s1, &s5, &s9, &s13, m3, m10)
        g(&s2, &s6, &s10, &s14, m7, m0)
        g(&s3, &s7, &s11, &s15, m4, m13)
        g(&s0, &s5, &s10, &s15, m1, m11)
        g(&s1, &s6, &s11, &s12, m12, m5)
        g(&s2, &s7, &s8, &s13, m9, m14)
        g(&s3, &s4, &s9, &s14, m15, m8)
        // round 2
        g(&s0, &s4, &s8, &s12, m3, m4)
        g(&s1, &s5, &s9, &s13, m10, m12)
        g(&s2, &s6, &s10, &s14, m13, m2)
        g(&s3, &s7, &s11, &s15, m7, m14)
        g(&s0, &s5, &s10, &s15, m6, m5)
        g(&s1, &s6, &s11, &s12, m9, m0)
        g(&s2, &s7, &s8, &s13, m11, m15)
        g(&s3, &s4, &s9, &s14, m8, m1)
        // round 3
        g(&s0, &s4, &s8, &s12, m10, m7)
        g(&s1, &s5, &s9, &s13, m12, m9)
        g(&s2, &s6, &s10, &s14, m14, m3)
        g(&s3, &s7, &s11, &s15, m13, m15)
        g(&s0, &s5, &s10, &s15, m4, m0)
        g(&s1, &s6, &s11, &s12, m11, m2)
        g(&s2, &s7, &s8, &s13, m5, m8)
        g(&s3, &s4, &s9, &s14, m1, m6)
        // round 4
        g(&s0, &s4, &s8, &s12, m12, m13)
        g(&s1, &s5, &s9, &s13, m9, m11)
        g(&s2, &s6, &s10, &s14, m15, m10)
        g(&s3, &s7, &s11, &s15, m14, m8)
        g(&s0, &s5, &s10, &s15, m7, m2)
        g(&s1, &s6, &s11, &s12, m5, m3)
        g(&s2, &s7, &s8, &s13, m0, m1)
        g(&s3, &s4, &s9, &s14, m6, m4)
        // round 5
        g(&s0, &s4, &s8, &s12, m9, m14)
        g(&s1, &s5, &s9, &s13, m11, m5)
        g(&s2, &s6, &s10, &s14, m8, m12)
        g(&s3, &s7, &s11, &s15, m15, m1)
        g(&s0, &s5, &s10, &s15, m13, m3)
        g(&s1, &s6, &s11, &s12, m0, m10)
        g(&s2, &s7, &s8, &s13, m2, m6)
        g(&s3, &s4, &s9, &s14, m4, m7)
        // round 6
        g(&s0, &s4, &s8, &s12, m11, m15)
        g(&s1, &s5, &s9, &s13, m5, m0)
        g(&s2, &s6, &s10, &s14, m1, m9)
        g(&s3, &s7, &s11, &s15, m8, m6)
        g(&s0, &s5, &s10, &s15, m14, m10)
        g(&s1, &s6, &s11, &s12, m2, m12)
        g(&s2, &s7, &s8, &s13, m3, m4)
        g(&s3, &s4, &s9, &s14, m7, m13)
        out[0] = s0 ^ s8; out[1] = s1 ^ s9; out[2] = s2 ^ s10; out[3] = s3 ^ s11
        out[4] = s4 ^ s12; out[5] = s5 ^ s13; out[6] = s6 ^ s14; out[7] = s7 ^ s15
        if full {
            out[8] = s8 ^ cv[0]; out[9] = s9 ^ cv[1]; out[10] = s10 ^ cv[2]; out[11] = s11 ^ cv[3]
            out[12] = s12 ^ cv[4]; out[13] = s13 ^ cv[5]; out[14] = s14 ^ cv[6]; out[15] = s15 ^ cv[7]
        }
    }

    private static let iv0: UInt32 = 0x6A09E667
    private static let iv1: UInt32 = 0xBB67AE85
    private static let iv2: UInt32 = 0x3C6EF372
    private static let iv3: UInt32 = 0xA54FF53A

    // MARK: util

    public static func hash(_ bytes: [UInt8]) -> [UInt8] {
        let h = Blake3(); h.update(bytes); return h.digest()
    }

    public static func hash(_ data: Data) -> [UInt8] {
        let h = Blake3(); h.update(data); return h.digest()
    }

    public static func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }
}
