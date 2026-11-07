import Foundation

/// Registers this device's `_crossdrop-vault._tcp.` record (service `xdrop-vault-<id>`, TXT
/// `id/name/mode/auth/ver` — identical to Android's `VaultAdvertiser`). The id is a fresh random
/// token per run, so the beacon can never be correlated across sessions. Main thread only.
final class VaultAdvertiser: NSObject, NetServiceDelegate {
    static let serviceType = "_crossdrop-vault._tcp."
    private var service: NetService?

    func start(vaultId: String, name: String, port: Int, writable: Bool, pinProtected: Bool) {
        stop()
        let s = NetService(domain: "local.", type: Self.serviceType, name: "xdrop-vault-\(vaultId)", port: Int32(port))
        s.delegate = self
        s.setTXTRecord(NetService.data(fromTXTRecord: [
            "id": Data(vaultId.utf8),
            "name": Data(name.clippedUTF8(48).utf8),
            "mode": Data((writable ? "rw" : "ro").utf8),
            "auth": Data((pinProtected ? "pin" : "open").utf8),
            "ver": Data("1".utf8),
        ]))
        s.publish()
        service = s
    }

    func stop() {
        service?.stop()
        service = nil
    }

    func netServiceDidPublish(_ sender: NetService) { NSLog("vault registered %@:%d", sender.name, sender.port) }
    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        NSLog("vault registration failed: %@", errorDict.description)
    }
}
