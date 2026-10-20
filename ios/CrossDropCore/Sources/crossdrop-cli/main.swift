import Foundation
import CrossDropCore

// Interop harness for the Kotlin core:
//   crossdrop-cli recv <inboxDir> [--version N] [--once]   → prints "LISTENING <port>", receives one transfer
//   crossdrop-cli send <host> <port> [--version N] <file>... → sends files, exits 0 on completion
setvbuf(stdout, nil, _IOLBF, 0)

var args = Array(CommandLine.arguments.dropFirst())
func opt(_ name: String) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    let v = args[i + 1]; args.removeSubrange(i...i + 1); return v
}
let version = opt("--version").flatMap(Int.init) ?? protocolVersion
guard let mode = args.first else { print("usage: crossdrop-cli recv|send ..."); exit(2) }
args.removeFirst()
if mode.hasPrefix("vault-") { runVaultCli(mode, args) }

let engine = TransferEngine(identity: SessionIdentity(), deviceName: { "swift-cli" },
                            instanceId: "5w1f7c11", protocolVersion: version)
let done = DispatchSemaphore(value: 0)
var exitCode: Int32 = 1

func onEvent(_ e: TransferEvent) {
    switch e {
    case .connecting: print("EVENT connecting")
    case .secured(let n, let sas): print("EVENT secured \(n) \(sas)")
    case .awaitingAccept: print("EVENT awaiting")
    case .progress(let d, let t, _, _, let skipped): if d == t { print("EVENT progress \(d)/\(t) skipped=\(skipped)") }
    case .completed(_, let ms): print("EVENT completed \(ms)ms"); exitCode = 0; done.signal()
    case .declinedByPeer: print("EVENT declined"); done.signal()
    case .cancelled: print("EVENT cancelled"); done.signal()
    case .failed(let m): print("EVENT failed \(m)"); done.signal()
    }
}

switch mode {
case "recv":
    guard let dir = args.first else { exit(2) }
    let inbox = URL(fileURLWithPath: dir, isDirectory: true)
    try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
    let store = FileDeltaStore(inboxDir: inbox)
    let listener = TCPListener()
    try listener.start { sock, _ in
        Thread.detachNewThread {
            engine.receive(socket: sock, decide: { _, offer in
                print("OFFER \(offer.files.map { "\($0.name):\($0.size)" }.joined(separator: ","))")
                return .accept(sinkFor: { meta in
                    guard let s = FileSink(url: inbox.appendingPathComponent(meta.name)) else { throw ProtocolError("cannot open sink") }
                    return s
                }, delta: store)
            }, emit: onEvent)
        }
    }
    print("LISTENING \(listener.port)")
    done.wait()
case "send":
    guard args.count >= 3, let port = Int(args[1]) else { exit(2) }
    let files = args[2...].map { path -> OutgoingFile in
        let url = URL(fileURLWithPath: path)
        let size = ((try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber)?.int64Value ?? 0
        return OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: size), url: url)
    }
    Thread.detachNewThread { engine.send(host: args[0], port: port, files: files, emit: onEvent) }
    done.wait()
default:
    print("unknown mode"); exit(2)
}
usleep(200_000)
exit(exitCode)

final class FileSink: OutputSink {
    private let handle: FileHandle
    init?(url: URL) {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let h = try? FileHandle(forWritingTo: url) else { return nil }
        handle = h
    }
    func write(_ bytes: [UInt8]) throws { try handle.write(contentsOf: Data(bytes)) }
    func close() { try? handle.close() }
}
