import XCTest
@testable import CrossDropCore

final class Blake3Tests: XCTestCase {
    private func input(_ len: Int) -> [UInt8] { (0..<len).map { UInt8($0 % 251) } }

    // Official BLAKE3 vectors (hash mode, first 32 bytes) — same set as core/Blake3Test.kt.
    private let vectors: [Int: String] = [
        0: "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
        1: "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
        3: "e1be4d7a8ab5560aa4199eea339849ba8e293d55ca0a81006726d184519e647f",
        1023: "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11",
        1024: "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7",
        1025: "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444",
        2049: "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030",
        3072: "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2",
        5121: "628bd2cb2004694adaab7bbd778a25df25c47b9d4155a55f8fbd79f2fe154cff",
        16384: "f875d6646de28985646f34ee13be9a576fd515f76b5b0a26bb324735041ddde4",
        102400: "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085",
    ]

    func testOfficialVectors() {
        for (len, want) in vectors {
            XCTAssertEqual(Blake3.hex(Blake3.hash(input(len))), want, "len=\(len)")
        }
    }

    func testStreamedMatchesOneShot() {
        let data = input(102400)
        let h = Blake3()
        var i = 0
        while i < data.count {
            let n = min(777, data.count - i)
            h.update(Array(data[i..<i + n]))
            i += n
        }
        XCTAssertEqual(h.hexDigest(), vectors[102400])
    }

    func testCdcBoundsAndReassembly() {
        var rng = SystemRandomNumberGenerator()
        let data = (0..<1_048_576).map { _ in UInt8.random(in: 0...255, using: &rng) }
        let chunks = FastCdc.split(data)
        for (c, _) in chunks.dropLast() {
            XCTAssertGreaterThanOrEqual(c.count, FastCdc.minSize)
            XCTAssertLessThanOrEqual(c.count, FastCdc.maxSize)
        }
        XCTAssertEqual(chunks.flatMap { $0.0 }, data)
    }
}

/// The original byte-at-a-time algorithm (a direct transcription of FastCdc.kt) — oracle for the optimised splitter.
private func referenceCdc(_ data: [UInt8]) -> [(Int, Int64)] {
    var out: [(Int, Int64)] = []
    var chunkLen = 0, offset: Int64 = 0
    var fp: UInt64 = 0
    for b in data {
        chunkLen += 1
        if chunkLen >= FastCdc.minSize {
            fp = (fp << 1) &+ UInt64(FastCdc.gear[Int(b)])
            let mask = chunkLen < FastCdc.avgSize ? FastCdc.maskS : FastCdc.maskL
            if (fp & mask) == 0 || chunkLen >= FastCdc.maxSize {
                out.append((chunkLen, offset)); offset += Int64(chunkLen); chunkLen = 0; fp = 0
            }
        }
    }
    if chunkLen > 0 { out.append((chunkLen, offset)) }
    return out
}

final class CdcEquivalenceTests: XCTestCase {
    private func rand(_ n: Int, seed: UInt64) -> [UInt8] {
        var s = seed
        return (0..<n).map { _ in s = s &* 6364136223846793005 &+ 1442695040888963407; return UInt8(truncatingIfNeeded: s >> 33) }
    }

    func testMatchesReferenceOnRandomAndDegenerateInputs() {
        let inputs: [[UInt8]] = [
            [], [7], rand(FastCdc.minSize - 1, seed: 1), rand(FastCdc.minSize, seed: 2), rand(FastCdc.minSize + 1, seed: 3),
            rand(2_000_000, seed: 4),
            [UInt8](repeating: 0, count: 1_500_000),               // never cuts by hash → forced maxSize cuts
            [UInt8](repeating: 0xFF, count: FastCdc.maxSize * 2 + 5),
            (0..<900_000).map { UInt8($0 % 251) },
        ]
        for (k, data) in inputs.enumerated() {
            let want = referenceCdc(data)
            let got = FastCdc.split(data)
            XCTAssertEqual(got.map { $0.0.count }, want.map { $0.0 }, "input #\(k) chunk lengths")
            XCTAssertEqual(got.map { $0.1 }, want.map { $0.1 }, "input #\(k) offsets")
            // fragmentation must not matter either
            let splitter = FastCdc.Splitter()
            var lens: [Int] = []
            var i = 0, step = 1
            while i < data.count {
                let n = min(step, data.count - i)
                data[i..<i + n].withUnsafeBufferPointer { splitter.feed($0) { c, _ in lens.append(c.count) } }
                i += n; step = (step * 7 + 13) % 70_001 + 1
            }
            splitter.finish { c, _ in lens.append(c.count) }
            XCTAssertEqual(lens, want.map { $0.0 }, "input #\(k) fragmented")
        }
    }
}

final class Blake3StreamingTests: XCTestCase {
    func testAnyFragmentationMatchesOneShotAcrossBoundaries() {
        var s: UInt64 = 99
        func next() -> Int { s = s &* 6364136223846793005 &+ 1442695040888963407; return Int(s >> 40) }
        var lengths = Set<Int>()
        for k in 0...20 { for d in -2...2 { lengths.insert(max(0, 1024 * k + d)); lengths.insert(max(0, 64 * k + d)) } }
        for _ in 0..<40 { lengths.insert(next() % 40_000) }
        lengths.insert(1024 * 64 + 1); lengths.insert(1024 * 128); lengths.insert(1024 * 1000 + 37)
        for len in lengths.sorted() {
            let data = (0..<len).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ ($0 >> 8)) }
            let want = Blake3.hash(data)
            // byte at a time exercises the buffered path; random slices mix buffered + direct paths
            let h1 = Blake3(); for b in data { h1.update([b]) }
            XCTAssertEqual(h1.digest(), want, "byte-wise len=\(len)")
            let h2 = Blake3(); var i = 0
            while i < data.count { let n = min(1 + next() % 3000, data.count - i); h2.update(Array(data[i..<i + n])); i += n }
            XCTAssertEqual(h2.digest(), want, "random slices len=\(len)")
        }
    }
}
