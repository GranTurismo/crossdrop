// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CrossDropCore",
    platforms: [.iOS(.v16), .macOS(.v13), .macCatalyst(.v16)],
    products: [
        .library(name: "CrossDropCore", targets: ["CrossDropCore"]),
        .executable(name: "crossdrop-cli", targets: ["crossdrop-cli"]),
    ],
    targets: [
        .target(
            name: "CrossDropCore",
            // Checked -O (traps, never UB, on a hostile-input bug) — fast enough since the hashing hot path is bounds-check free.
            // Applied to Debug too: BLAKE3/FastCDC are CPU-bound and a simulator run would crawl at a few MB/s.
            swiftSettings: [.unsafeFlags(["-O"])]
        ),
        .executableTarget(name: "crossdrop-cli", dependencies: ["CrossDropCore"]),
        .testTarget(name: "CrossDropCoreTests", dependencies: ["CrossDropCore"]),
    ]
)
