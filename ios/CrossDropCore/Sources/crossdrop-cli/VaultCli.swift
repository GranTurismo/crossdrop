import Foundation
import CrossDropCore

// Wireless-drive interop modes for the Kotlin core:
//   vault-serve <dir> [--rw] [--pin 1234] [--port N]   → prints "LISTENING <port>", serves until killed
//   vault-ls    <host> <port> [--pin P] [path]
//   vault-stat  <host> <port> [--pin P] <path>
//   vault-pull  <host> <port> [--pin P] <remote> <localFile> [--offset N] [--length N]
//   vault-push  <host> <port> [--pin P] <localFile> <remote>
// Client modes print machine-readable lines (ENTRY/ATTR/PULLED/PUSHED) and exit 0, or
// `ERROR <code> <message>` and exit 1.

func runVaultCli(_ mode: String, _ input: [String]) -> Never {
    var args = input
    func flag(_ name: String) -> Bool {
        guard let i = args.firstIndex(of: name) else { return false }
        args.remove(at: i); return true
    }
    func opt(_ name: String) -> String? {
        guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
        let v = args[i + 1]; args.removeSubrange(i...i + 1); return v
    }
    let pin = opt("--pin")
    let offset = opt("--offset").flatMap(Int64.init) ?? 0
    let length = opt("--length").flatMap(Int64.init) ?? -1
    let preferred = opt("--port").flatMap(Int.init) ?? 0
    let rw = flag("--rw")

    if mode == "vault-serve" {
        guard let dir = args.first else { print("usage: vault-serve <dir>"); exit(2) }
        let server = VaultServer(fs: FileVaultFs(rootDir: URL(fileURLWithPath: dir, isDirectory: true)),
                                 identity: SessionIdentity(), deviceName: { "swift-vault" },
                                 writable: rw, pin: pin, instanceId: "5w1f7va1")
        do { _ = try server.start(preferredPort: preferred) } catch { print("ERROR 500 \(error)"); exit(1) }
        print("LISTENING \(server.port)")
        while true { sleep(3600) }
    }

    guard args.count >= 2, let port = Int(args[1]) else { print("usage: \(mode) <host> <port> ..."); exit(2) }
    let host = args[0]
    let rest = Array(args.dropFirst(2))
    let client = VaultClient(host: host, port: port, identity: SessionIdentity(),
                             deviceName: "swift-vault-cli", instanceId: "5w1f7c1i")
    func fail(_ e: Error) -> Never {
        if let v = e as? VaultError { print("ERROR \(v.code) \(v.message)") } else { print("ERROR 0 \(e)") }
        exit(1)
    }
    do {
        try client.connect(pin: pin)
        switch mode {
        case "vault-ls":
            for e in try client.ls(rest.first ?? "/") { print("ENTRY\t\(e.name)\t\(e.size)\t\(e.isDir)") }
        case "vault-stat":
            guard let p = rest.first else { exit(2) }
            let a = try client.stat(p)
            print("ATTR\t\(a.name)\t\(a.size)\t\(a.isDir)\t\(a.hash ?? "-")")
        case "vault-pull":
            guard rest.count >= 2 else { exit(2) }
            var bytes: Int64 = 0
            let done = try client.pull(rest[0], offset: offset, length: length, to: URL(fileURLWithPath: rest[1])) { bytes = $0 }
            print("PULLED\t\(bytes)\t\(done.hash)")
        case "vault-push":
            guard rest.count >= 2 else { exit(2) }
            let done = try client.push(rest[1], fileURL: URL(fileURLWithPath: rest[0]))
            print("PUSHED\t\(done.path ?? "-")\t\(done.hash)")
        default:
            print("unknown mode"); exit(2)
        }
        client.close()
        exit(0)
    } catch { fail(error) }
}
