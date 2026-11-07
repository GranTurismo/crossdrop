import Foundation

/// Discovers `_crossdrop-vault._tcp.` services — each vault is its own radar node (orbital beacon),
/// keyed by the vault's ephemeral TXT `id`, independent of the host's transfer instanceId.
/// Must be driven from the main thread (NetService schedules on the main run loop).
final class VaultDiscovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    static let serviceType = "_crossdrop-vault._tcp."

    var onPeersChanged: (([String: Peer]) -> Void)?
    /// Our own vault's current id — filtered from sightings while hosting.
    var selfVaultId: () -> String? = { nil }

    private var browser: NetServiceBrowser?
    private var resolving: [String: NetService] = [:]
    private var nameToId: [String: String] = [:]
    private var peers: [String: Peer] = [:]

    func start() {
        stop()
        let b = NetServiceBrowser()
        b.delegate = self
        b.searchForServices(ofType: Self.serviceType, inDomain: "local.")
        browser = b
    }

    func stop() {
        browser?.stop(); browser = nil
        resolving.values.forEach { $0.stop() }
        resolving.removeAll(); nameToId.removeAll()
        if !peers.isEmpty { peers = [:]; onPeersChanged?(peers) }
    }

    // MARK: NetServiceBrowserDelegate

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        resolving[service.name] = service
        service.delegate = self
        service.resolve(withTimeout: 6)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        resolving[service.name]?.stop()
        resolving[service.name] = nil
        if let id = nameToId.removeValue(forKey: service.name), !nameToId.values.contains(id) {
            peers[id] = nil
            onPeersChanged?(peers)
        }
    }

    // MARK: NetServiceDelegate

    func netServiceDidResolveAddress(_ sender: NetService) {
        let txt = sender.txtRecordData().map { NetService.dictionary(fromTXTRecord: $0) } ?? [:]
        func str(_ k: String) -> String? { txt[k].flatMap { String(data: $0, encoding: .utf8) } }
        let id = str("id") ?? sender.name.replacingOccurrences(of: "xdrop-vault-", with: "")
        if id == selfVaultId() { return }                                  // our own beacon
        guard let host = BonjourDiscovery.pickHost(sender.addresses ?? []) else { return }
        let name = str("name").flatMap { $0.isEmpty ? nil : $0 } ?? "Vault"
        nameToId[sender.name] = id
        peers[id] = Peer(
            instanceId: id, name: name, host: host, port: sender.port,
            vault: VaultDescriptor(vaultName: name, writable: str("mode") == "rw", needsPin: str("auth") == "pin"),
            probeable: true)       // the vault listener drops a bare connect silently, in every build
        onPeersChanged?(peers)
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving[sender.name] = nil
    }
}
