import Foundation
import CrossDropCore

// Interop harness for the Kotlin core:
//   crossdrop-cli recv <inboxDir> [--version N] [--count N] [--grant]
//       → prints "LISTENING <port>", accepts N transfers (default 1); --grant answers camera/switch
//         offers with a trust grant and prints "TRUSTED" for later offers that prove it
//   crossdrop-cli send <host> <port> [--version N] [--name NAME] [--kind K] [--text T] [--cat C] [--each] <file>...
//       → sends files, exits 0 on completion; --each sends one offer per file in sequence (live
//         camera / switch batches), reusing any trust grant the receiver returns
setvbuf(stdout, nil, _IOLBF, 0)

var args = Array(CommandLine.arguments.dropFirst())
func opt(_ name: String) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    let v = args[i + 1]; args.removeSubrange(i...i + 1); return v
}
let version = opt("--version").flatMap(Int.init) ?? protocolVersion
let deviceName = opt("--name") ?? "swift-cli"
let kind = opt("--kind")
let textOpt = opt("--text")
let cat = opt("--cat")
let count = opt("--count").flatMap(Int.init) ?? 1
func flag(_ name: String) -> Bool {
    guard let i = args.firstIndex(of: name) else { return false }
    args.remove(at: i); return true
}
let each = flag("--each")
let grantTrust = flag("--grant")
guard let mode = args.first else { print("usage: crossdrop-cli recv|send ..."); exit(2) }
args.removeFirst()
if mode.hasPrefix("vault-") { runVaultCli(mode, args) }

let engine = TransferEngine(identity: SessionIdentity(), deviceName: { deviceName },
                            instanceId: "5w1f7c11", protocolVersion: version)
let done = DispatchSemaphore(value: 0)
var exitCode: Int32 = 1
var heldGrant: TrustGrant?
var granted: [String: [UInt8]] = [:]
let grantLock = NSLock()

func onEvent(_ e: TransferEvent) {
    switch e {
    case .connecting: print("EVENT connecting")
    case .secured(let n, let sas): print("EVENT secured \(n) \(sas)")
    case .awaitingAccept: print("EVENT awaiting")
    case .progress(let d, let t, _, _, let skipped): if d == t { print("EVENT progress \(d)/\(t) skipped=\(skipped)") }
    case .completed(_, let ms): print("EVENT completed \(ms)ms"); exitCode = 0; if !each { done.signal() }
    case .declinedByPeer: print("EVENT declined"); if !each { done.signal() }
    case .cancelled: print("EVENT cancelled"); if !each { done.signal() }
    case .failed(let m): print("EVENT failed \(m)"); if !each { done.signal() }
    case .trustGranted(let g): print("EVENT trust \(g.kind)"); heldGrant = g
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
            engine.receive(socket: sock, decideRequest: { req in
                let offer = req.offer
                print("OFFER \(offer.files.map { "\($0.name):\($0.size):\($0.cat ?? "-")" }.joined(separator: ","))")
                print("KIND \(offer.kind ?? "-") TEXT \(offer.text.map { "\($0.count)ch" } ?? "-") BATCH \(offer.batch.map { "\($0.index)/\($0.count) p\($0.photos) v\($0.videos) c\($0.contacts)" } ?? "-") FROM \(req.peerName)")
                var grant: TrustGrant?
                grantLock.lock()
                if let id = offer.trustId, let token = granted[id], req.provesTrust(token: token) { print("TRUSTED \(id)") }
                else if grantTrust, let k = offer.kind, k == OfferKind.camera || k == OfferKind.switchPhones {
                    var token = [UInt8](repeating: 0, count: 32)
                    _ = SecRandomCopyBytes(kSecRandomDefault, 32, &token)
                    let id = UUID().uuidString.lowercased()
                    granted[id] = token
                    grant = TrustGrant(id: id, token: Data(token).base64EncodedString(), kind: k, ttlMs: 600_000)
                    print("GRANT \(id)")
                }
                grantLock.unlock()
                return .accept(sinkFor: { meta in
                    guard let s = FileSink(url: inbox.appendingPathComponent(meta.name)) else { throw ProtocolError("cannot open sink") }
                    return s
                }, delta: store, grant: grant)
            }, emit: onEvent)
        }
    }
    print("LISTENING \(listener.port)")
    for _ in 0..<count { done.wait() }
case "send":
    guard args.count >= 3, let port = Int(args[1]) else { exit(2) }
    let files = args[2...].map { path -> OutgoingFile in
        let url = URL(fileURLWithPath: path)
        let size = ((try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber)?.int64Value ?? 0
        let mtime = ((try? FileManager.default.attributesOfItem(atPath: path))?[.modificationDate] as? Date).map { Int64($0.timeIntervalSince1970 * 1000) }
        return OutgoingFile(meta: FileMeta(name: url.lastPathComponent, size: size, cat: cat, mtime: mtime), url: url)
    }
    let host = args[0]
    if each {
        // One offer per file, in order; a trust grant from the first carries the rest.
        Thread.detachNewThread {
            for (i, f) in files.enumerated() {
                let batch = kind == OfferKind.switchPhones
                    ? BatchInfo(index: i, count: files.count, photos: cat == "photo" ? files.count : 0,
                                videos: cat == "video" ? files.count : 0, contacts: cat == "contacts" ? 1 : 0,
                                totalBytes: files.reduce(0) { $0 + $1.meta.size })
                    : nil
                let opts = SendOptions(kind: kind, text: textOpt, trust: heldGrant.flatMap(TrustCredential.init), batch: batch)
                let one = DispatchSemaphore(value: 0)
                var ok = false
                engine.send(host: host, port: port, files: [f], options: opts) { e in
                    onEvent(e)
                    switch e {
                    case .completed: ok = true; one.signal()
                    case .failed, .declinedByPeer, .cancelled: one.signal()
                    default: break
                    }
                }
                one.wait()
                if !ok { exitCode = 1; break }
                exitCode = 0
            }
            done.signal()
        }
    } else {
        let batch = kind == OfferKind.switchPhones ? BatchInfo(index: 0, count: 1, totalBytes: files.reduce(0) { $0 + $1.meta.size }) : nil
        Thread.detachNewThread {
            engine.send(host: host, port: port, files: files, options: SendOptions(kind: kind, text: textOpt, batch: batch), emit: onEvent)
        }
    }
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
