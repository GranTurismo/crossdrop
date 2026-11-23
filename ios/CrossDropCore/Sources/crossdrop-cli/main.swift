import Foundation
import CrossDropCore

// Interop harness for the Kotlin core:
//   crossdrop-cli recv <inboxDir> [--version N] [--count N] [--grant]
//       → prints "LISTENING <port>", accepts N transfers (default 1); --grant answers camera/switch
//         offers with a trust grant and prints "TRUSTED" for later offers that prove it
//   crossdrop-cli send <host> <port> [--version N] [--name NAME] [--kind K] [--text T] [--cat C] [--each] <file>...
//       → sends files, exits 0 on completion; --each sends one offer per file in sequence (live
//         camera / switch batches), reusing any trust grant the receiver returns
//   crossdrop-cli announce --to <host:port> --port N
//       → tells the device at host:port that this one listens on N (ANNOUNCE); prints
//         "ANNOUNCED <name> <instanceId>" with the other device's HELLO_ACK identity, exits 0
//   crossdrop-cli shake --to <host:port> [--port N]
//       → tells the device at host:port that this one was just shaken (SHAKE), listening on N (default 0: "didn't
//         say"); prints "SHOOK <name> <instanceId>" with the other device's HELLO_ACK identity, exits 0
// `recv` answers ANNOUNCE too, printing "ANNOUNCED-FROM <name> <instanceId> <host> <port>" (no session event), and
// SHAKE, printing "SHAKE-FROM <name> <instanceId> <host> <port>". Run `shake` with recv's --id and --port to play the
// other phone: the device can then reach it back.
// --id HEX sets this end's per-launch instance id (hex, as discovery and announced peers expect).
// "My devices":
//   crossdrop-cli pair --to <host:port> [--purpose none]
//       → asks to pair ("Add a device"; `none` = the old backup request); prints "CODE <sas>", then
//         "PAIRED <pairId> <keyHex>" or "DECLINED"
//   crossdrop-cli clip --to <host:port> --pairing <id>:<keyHex> --text T
//       → proves the pairing (PAIR_CHECK) and offers T for the other clipboard; prints "CLIP accepted|refused|notproven"
//   recv --agree: agrees to pairing requests ("PAIR-ASKED <name> <sas> <purpose|->", then "PAIRED-FROM <pairId> <keyHex>");
//   recv --pairing <id>:<keyHex>: holds that pairing (answers PAIR_CHECK; an offer that proves it prints "PAIRED-OFFER <id>"
//   and is answered with the proof); recv --clips: takes clipboard copies, printing "CLIP-FROM <pairId> <text>".
//   send --pairing <id>:<keyHex>: the offer proves the pairing, and the send fails unless the receiver proves it back.
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
let announceTo = opt("--to")
let announcePort = opt("--port").flatMap(Int.init)
let cliInstanceId = opt("--id") ?? "5e1f7c11"
let purposeOpt = opt("--purpose")
func parsePairing(_ s: String?) -> PairSecret? {
    guard let s, let colon = s.firstIndex(of: ":") else { return nil }
    let hex = Array(s[s.index(after: colon)...])
    guard hex.count == 64 else { return nil }
    let key = stride(from: 0, to: 64, by: 2).compactMap { UInt8(String(hex[$0..<$0 + 2]), radix: 16) }
    return key.count == 32 ? PairSecret(id: String(s[..<colon]), key: key) : nil
}
func hexOf(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
let cliPairing = parsePairing(opt("--pairing"))
func flag(_ name: String) -> Bool {
    guard let i = args.firstIndex(of: name) else { return false }
    args.remove(at: i); return true
}
let each = flag("--each")
let grantTrust = flag("--grant")
let agreePairing = flag("--agree")
let takeClips = flag("--clips")
guard let mode = args.first else { print("usage: crossdrop-cli recv|send|announce|shake|pair|clip ..."); exit(2) }
args.removeFirst()
if mode.hasPrefix("vault-") { runVaultCli(mode, args) }

let engine = TransferEngine(identity: SessionIdentity(), deviceName: { deviceName },
                            instanceId: cliInstanceId, protocolVersion: version)
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
    case .failed(let m, _): print("EVENT failed \(m)"); if !each { done.signal() }
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
    let announces = PrintAnnounces(agree: agreePairing, held: cliPairing.map { [$0] } ?? [], takeClips: takeClips)
    try listener.start { sock, _ in
        Thread.detachNewThread {
            engine.receive(socket: sock, control: announces, decideRequest: { req in
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
                let paired = req.pairedWith(announces.pairings())
                if let paired { print("PAIRED-OFFER \(paired.id)") }
                return .accept(sinkFor: { meta in
                    guard let s = FileSink(url: inbox.appendingPathComponent(meta.name)) else { throw ProtocolError("cannot open sink") }
                    return s
                }, delta: store, grant: grant, pairKey: paired?.key)
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
            engine.send(host: host, port: port, files: files,
                        options: SendOptions(kind: kind, text: textOpt, batch: batch, pairKey: cliPairing?.key), emit: onEvent)
        }
    }
    done.wait()
case "announce":
    guard let to = announceTo, let colon = to.lastIndex(of: ":"), let port = Int(to[to.index(after: colon)...]),
          let listenPort = announcePort else {
        print("usage: crossdrop-cli announce --to <host:port> --port N"); exit(2)
    }
    var host = String(to[..<colon])
    if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
    do {
        let answer = try engine.announce(host: host, port: port, listenPort: listenPort)
        print("ANNOUNCED \(answer.name) \(answer.instanceId)")
        exitCode = 0
    } catch {
        print("FAILED \(error)")
        exitCode = 1
    }
case "shake":
    guard let to = announceTo, let colon = to.lastIndex(of: ":"), let port = Int(to[to.index(after: colon)...]) else {
        print("usage: crossdrop-cli shake --to <host:port> [--port N]"); exit(2)
    }
    var host = String(to[..<colon])
    if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
    do {
        let answer = try engine.shake(host: host, port: port, listenPort: announcePort ?? 0)
        print("SHOOK \(answer.name) \(answer.instanceId)")
        exitCode = 0
    } catch {
        print("FAILED \(error)")
        exitCode = 1
    }
case "pair", "clip":
    guard let to = announceTo, let colon = to.lastIndex(of: ":"), let port = Int(to[to.index(after: colon)...]) else {
        print("usage: crossdrop-cli pair|clip --to <host:port> ..."); exit(2)
    }
    let host = String(to[..<colon])
    do {
        if mode == "pair" {
            let id = ControlChannel.newPairId()
            let key = try engine.control(host: host, port: port, readTimeout: ControlChannel.pairWait) { ch -> [UInt8]? in
                print("CODE \(ch.sas)")
                return try ch.pair(pairId: id, purpose: purposeOpt == "none" ? nil : PairPurpose.devices)
            }
            print(key.map { "PAIRED \(id) \(hexOf($0))" } ?? "DECLINED")
        } else {
            guard let pairing = cliPairing, let text = textOpt else { print("clip needs --pairing and --text"); exit(2) }
            let answer = try engine.control(host: host, port: port) { ch -> String in
                guard try ch.pairCheck([pairing]) != nil else { return "notproven" }
                return try ch.clipboard(text) ? "accepted" : "refused"
            }
            print("CLIP \(answer)")
        }
        exitCode = 0
    } catch {
        print("FAILED \(error)")
        exitCode = 1
    }
default:
    print("unknown mode"); exit(2)
}
usleep(200_000)
exit(exitCode)

/// `recv`'s answer to ANNOUNCE and SHAKE (print who it was and where they listen) and, when asked to, to pairing and
/// clipboard copies.
final class PrintAnnounces: ControlHandler {
    private let lock = NSLock()
    private let agree: Bool
    private let takeClips: Bool
    private var held: [PairSecret]
    private var seen = Set<String>()
    init(agree: Bool, held: [PairSecret], takeClips: Bool) { self.agree = agree; self.held = held; self.takeClips = takeClips }
    func pairRequested(name: String, sas: String, purpose: String?) -> Bool {
        print("PAIR-ASKED \(name) \(sas) \(purpose ?? "-")")
        return agree
    }
    func paired(pairId: String, name: String, key: [UInt8], purpose: String?) {
        lock.lock(); held.append(PairSecret(id: pairId, key: key)); lock.unlock()
        print("PAIRED-FROM \(pairId) \(hexOf(key))")
    }
    func pairings() -> [PairSecret] { lock.lock(); defer { lock.unlock() }; return held }
    func clipboard(pairId: String, clipId: String, text: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard takeClips, seen.insert(clipId).inserted else { return false }
        print("CLIP-FROM \(pairId) \(text.replacingOccurrences(of: "\n", with: "\\n"))")
        return true
    }
    func announced(name: String, instanceId: String, host: String, port: Int) {
        print("ANNOUNCED-FROM \(name) \(instanceId) \(host) \(port)")
    }
    func shaken(name: String, instanceId: String, host: String, port: Int) {
        print("SHAKE-FROM \(name) \(instanceId) \(host) \(port)")
    }
}

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
