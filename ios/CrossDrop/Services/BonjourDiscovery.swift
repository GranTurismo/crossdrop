import Foundation
import Darwin

/// mDNS discovery via NetService: advertises `_crossdrop._tcp.` on our listener port (TXT `id`,
/// `name` — same keys as Android NSD / desktop JmDNS) and resolves peers advertising the same type.
/// Must be driven from the main thread (NetService schedules on the main run loop).
final class BonjourDiscovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    static let serviceType = "_crossdrop._tcp."

    var onPeersChanged: (([String: Peer]) -> Void)?
    var onError: ((String) -> Void)?

    private let instanceId: String
    private let deviceName: () -> String
    private var browser: NetServiceBrowser?
    private var published: NetService?
    private var resolving: [String: NetService] = [:]       // service name → service
    private var nameToId: [String: String] = [:]
    private var peers: [String: Peer] = [:]
    private var port: Int = 0

    init(instanceId: String, deviceName: @escaping () -> String) {
        self.instanceId = instanceId
        self.deviceName = deviceName
    }

    func start(port: Int) {
        self.port = port
        stop()
        advertise()
        let b = NetServiceBrowser()
        b.delegate = self
        b.searchForServices(ofType: Self.serviceType, inDomain: "local.")
        browser = b
    }

    func stop() {
        browser?.stop(); browser = nil
        published?.stop(); published = nil
        resolving.values.forEach { $0.stop() }
        resolving.removeAll(); nameToId.removeAll()
        if !peers.isEmpty { peers = [:]; onPeersChanged?(peers) }
    }

    func updateAdvertisedName() {
        guard let published else { return }
        published.setTXTRecord(txt())
    }

    private func txt() -> Data {
        NetService.data(fromTXTRecord: [
            "id": Data(instanceId.utf8),
            "name": Data(deviceName().clippedUTF8(48).utf8),
        ])
    }

    private func advertise() {
        guard port > 0 else { return }
        let s = NetService(domain: "local.", type: Self.serviceType, name: "crossdrop-\(instanceId)", port: Int32(port))
        s.delegate = self
        s.setTXTRecord(txt())
        s.publish()
        published = s
    }

    // MARK: NetServiceBrowserDelegate

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        if service.name.contains(instanceId) { return }   // ourselves
        resolving[service.name] = service
        service.delegate = self
        service.resolve(withTimeout: 6)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        resolving[service.name]?.stop()
        resolving[service.name] = nil
        if let id = nameToId.removeValue(forKey: service.name) {
            peers[id] = nil
            onPeersChanged?(peers)
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        let code = errorDict[NetService.errorCode]?.intValue ?? 0
        onError?(Self.localNetworkDenied(code)
            ? "Local Network access is off — turn it on for CrossDrop in Settings, then tap Rescan, so nearby devices can be found."
            : "Couldn't search for nearby devices (error \(code)).")
    }

    // MARK: NetServiceDelegate

    func netServiceDidResolveAddress(_ sender: NetService) {
        let txt = sender.txtRecordData().map { NetService.dictionary(fromTXTRecord: $0) } ?? [:]
        func str(_ k: String) -> String? { txt[k].flatMap { String(data: $0, encoding: .utf8) } }
        let id = str("id") ?? sender.name.replacingOccurrences(of: "crossdrop-", with: "")
        if id == instanceId { return }
        guard let host = Self.pickHost(sender.addresses ?? []) else { return }
        nameToId[sender.name] = id
        peers[id] = Peer(instanceId: id, name: str("name") ?? sender.name, host: host, port: sender.port)
        onPeersChanged?(peers)
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        // transient — the next browse wave retries
        resolving[sender.name] = nil
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        let code = errorDict[NetService.errorCode]?.intValue ?? 0
        onError?(Self.localNetworkDenied(code)
            ? "Local Network access is off — turn it on for CrossDrop in Settings so other devices can see this one."
            : "Couldn't announce this device on the network (error \(code)).")
    }

    /// `kDNSServiceErr_PolicyDenied` (-65570) / NSNetServicesErrorDomain -72008: the Local Network prompt was declined.
    static func localNetworkDenied(_ code: Int) -> Bool { code == -72008 || code == -65570 }

    // MARK: addresses

    /// Prefer IPv4; fall back to a (scoped) IPv6 literal.
    static func pickHost(_ addrs: [Data]) -> String? {
        var v6: String?
        for data in addrs {
            let ss: sockaddr_storage = data.withUnsafeBytes { raw in
                var s = sockaddr_storage()
                memcpy(&s, raw.baseAddress!, min(raw.count, MemoryLayout<sockaddr_storage>.size))
                return s
            }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let rc: Int32 = data.withUnsafeBytes { raw in
                getnameinfo(raw.baseAddress!.assumingMemoryBound(to: sockaddr.self), socklen_t(data.count),
                            &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
            guard rc == 0 else { continue }
            let s = String(cString: host)
            if Int32(ss.ss_family) == AF_INET { return s }
            if Int32(ss.ss_family) == AF_INET6, v6 == nil { v6 = s }
        }
        return v6
    }
}
