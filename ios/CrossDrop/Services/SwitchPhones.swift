import Foundation
import Photos
import Contacts
import UIKit
@preconcurrency import CrossDropCore

// MARK: - Contacts

/// The address book as vCard 3.0, and back. Contact notes are left out: reading them needs an
/// entitlement Apple grants case by case.
enum ContactsBridge {
    static func authorized() -> Bool { CNContactStore.authorizationStatus(for: .contacts) == .authorized }

    static func requestAccess() async -> Bool {
        (try? await CNContactStore().requestAccess(for: .contacts)) ?? false
    }

    private static var keys: [CNKeyDescriptor] {
        ([CNContactGivenNameKey, CNContactFamilyNameKey, CNContactMiddleNameKey, CNContactNamePrefixKey,
          CNContactNameSuffixKey, CNContactNicknameKey, CNContactOrganizationNameKey, CNContactDepartmentNameKey,
          CNContactJobTitleKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey, CNContactPostalAddressesKey,
          CNContactUrlAddressesKey, CNContactBirthdayKey, CNContactImageDataKey] as [CNKeyDescriptor])
            + [CNContactFormatter.descriptorForRequiredKeys(for: .fullName)]
    }

    static func count() -> Int {
        guard authorized() else { return 0 }
        var n = 0
        let req = CNContactFetchRequest(keysToFetch: [CNContactGivenNameKey as CNKeyDescriptor])
        try? CNContactStore().enumerateContacts(with: req) { _, _ in n += 1 }
        return n
    }

    /// Every contact as one vCard 3.0 document.
    static func exportAll() -> (vcf: String, count: Int) {
        var out = ""
        var n = 0
        let req = CNContactFetchRequest(keysToFetch: keys)
        try? CNContactStore().enumerateContacts(with: req) { c, _ in
            out += vcard(c); n += 1
        }
        return (out, n)
    }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: ",", with: "\\,").replacingOccurrences(of: ";", with: "\\;")
    }

    private static func type(_ label: String?) -> String {
        switch label {
        case CNLabelPhoneNumberMobile?, CNLabelPhoneNumberiPhone?: return ";TYPE=CELL"
        case CNLabelHome?: return ";TYPE=HOME"
        case CNLabelWork?: return ";TYPE=WORK"
        case CNLabelPhoneNumberMain?: return ";TYPE=MAIN"
        case CNLabelPhoneNumberHomeFax?, CNLabelPhoneNumberWorkFax?: return ";TYPE=FAX"
        case CNLabelPhoneNumberPager?: return ";TYPE=PAGER"
        default: return ""
        }
    }

    /// 75-octet folding (RFC 6350 §3.2), continuation lines start with a space.
    private static func fold(_ line: String) -> String {
        guard line.utf8.count > 75 else { return line + "\r\n" }
        var out = "", cur = 0
        for ch in line {
            let n = String(ch).utf8.count
            if cur + n > (out.isEmpty ? 75 : 74) { out += "\r\n "; cur = 0 }
            out.append(ch); cur += n
        }
        return out + "\r\n"
    }

    static func vcard(_ c: CNContact) -> String {
        var v = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
        func add(_ s: String) { v += fold(s) }
        add("N:" + [c.familyName, c.givenName, c.middleName, c.namePrefix, c.nameSuffix].map(esc).joined(separator: ";"))
        let fn = CNContactFormatter.string(from: c, style: .fullName) ?? c.organizationName
        add("FN:" + esc(fn))
        if !c.nickname.isEmpty { add("NICKNAME:" + esc(c.nickname)) }
        if !c.organizationName.isEmpty || !c.departmentName.isEmpty {
            add("ORG:" + esc(c.organizationName) + (c.departmentName.isEmpty ? "" : ";" + esc(c.departmentName)))
        }
        if !c.jobTitle.isEmpty { add("TITLE:" + esc(c.jobTitle)) }
        for p in c.phoneNumbers { add("TEL" + type(p.label) + ":" + esc(p.value.stringValue)) }
        for e in c.emailAddresses { add("EMAIL;TYPE=INTERNET" + type(e.label).replacingOccurrences(of: ";TYPE=", with: ",") + ":" + esc(e.value as String)) }
        for a in c.postalAddresses {
            let p = a.value
            add("ADR" + type(a.label) + ":;;" + [p.street, p.city, p.state, p.postalCode, p.country].map(esc).joined(separator: ";"))
        }
        for u in c.urlAddresses { add("URL:" + esc(u.value as String)) }
        if let b = c.birthday, let m = b.month, let d = b.day {
            add(b.year.map { String(format: "BDAY:%04d-%02d-%02d", $0, m, d) } ?? String(format: "BDAY:--%02d-%02d", m, d))
        }
        if let img = c.imageData, !img.isEmpty {
            let jpeg = UIImage(data: img).flatMap { $0.jpegData(compressionQuality: 0.85) } ?? img
            add("PHOTO;ENCODING=b;TYPE=JPEG:" + jpeg.base64EncodedString())
        }
        return v + "END:VCARD\r\n"
    }

    /// Adds the contacts in `vcf` that aren't already in the address book. Returns (added, skipped).
    static func importVCard(_ data: Data) -> (added: Int, skipped: Int) {
        guard let parsed = try? CNContactVCardSerialization.contacts(with: data) else { return (0, 0) }
        let store = CNContactStore()
        var existing: [String: [CNContact]] = [:]
        let keys: [CNKeyDescriptor] = [CNContactGivenNameKey as CNKeyDescriptor, CNContactFamilyNameKey as CNKeyDescriptor,
                                       CNContactOrganizationNameKey as CNKeyDescriptor, CNContactPhoneNumbersKey as CNKeyDescriptor,
                                       CNContactEmailAddressesKey as CNKeyDescriptor]
        let req = CNContactFetchRequest(keysToFetch: keys)
        try? store.enumerateContacts(with: req) { c, _ in existing[normName(display(c)), default: []].append(c) }
        var added = 0, skipped = 0
        let save = CNSaveRequest()
        for c in parsed {
            if existing[normName(display(c))]?.contains(where: { same(c, $0) }) == true { skipped += 1; continue }
            if let m = c.mutableCopy() as? CNMutableContact { save.add(m, toContainerWithIdentifier: nil); added += 1 }
        }
        if added > 0, (try? store.execute(save)) == nil { return (0, skipped) }
        return (added, skipped)
    }

    private static func display(_ c: CNContact) -> String {
        let n = [c.givenName, c.familyName].filter { !$0.isEmpty }.joined(separator: " ")
        return n.isEmpty ? c.organizationName : n
    }
    private static func normName(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }
    private static func normPhone(_ s: String) -> String { String(s.filter(\.isNumber).suffix(9)) }

    /// Same person: same name and a shared phone or email (or neither has any).
    private static func same(_ a: CNContact, _ b: CNContact) -> Bool {
        let pa = Set(a.phoneNumbers.map { normPhone($0.value.stringValue) }.filter { !$0.isEmpty })
        let pb = Set(b.phoneNumbers.map { normPhone($0.value.stringValue) }.filter { !$0.isEmpty })
        let ea = Set(a.emailAddresses.map { ($0.value as String).lowercased() })
        let eb = Set(b.emailAddresses.map { ($0.value as String).lowercased() })
        if pa.isEmpty && pb.isEmpty && ea.isEmpty && eb.isEmpty { return true }
        return !pa.isDisjoint(with: pb) || !ea.isDisjoint(with: eb)
    }
}

// MARK: - Photos

enum PhotosBridge {
    static func readAuthorized() -> Bool {
        let s = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return s == .authorized || s == .limited
    }
    static func isLimited() -> Bool { PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited }

    static func requestRead() async -> Bool {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return s == .authorized || s == .limited
    }

    static func requestAdd() async -> Bool {
        if readAuthorized() { return true }          // full access already covers adding — don't ask twice
        let s = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return s == .authorized || s == .limited
    }

    static func fetch(_ type: PHAssetMediaType) -> [PHAsset] {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let r = PHAsset.fetchAssets(with: type, options: opts)
        var out: [PHAsset] = []
        out.reserveCapacity(r.count)
        r.enumerateObjects { a, _, _ in out.append(a) }
        return out
    }

    /// The original photo/video (edits included when there are any), downloaded from iCloud if needed.
    static func export(_ asset: PHAsset, into dir: URL) async throws -> URL {
        let resources = PHAssetResource.assetResources(for: asset)
        let wanted: [PHAssetResourceType] = asset.mediaType == .video ? [.fullSizeVideo, .video] : [.fullSizePhoto, .photo]
        guard let res = wanted.lazy.compactMap({ w in resources.first { $0.type == w } }).first ?? resources.first else {
            throw ProtocolError("no resource")
        }
        var name = res.originalFilename.isEmpty ? "item" : res.originalFilename
        if res.type == .fullSizePhoto || res.type == .fullSizeVideo, let orig = resources.first(where: { $0.type == .photo || $0.type == .video }) {
            // Edited version: keep the original's name stem with the edited file's extension.
            name = (orig.originalFilename as NSString).deletingPathExtension + "." + (name as NSString).pathExtension
        }
        let url = dir.appendingPathComponent(UUID().uuidString.prefix(8) + "-" + name)
        let opts = PHAssetResourceRequestOptions()
        opts.isNetworkAccessAllowed = true
        try await PHAssetResourceManager.default().writeData(for: res, toFile: url, options: opts)
        return url
    }

    /// Adds a file to the library as a new photo/video dated `date`.
    static func save(_ url: URL, video: Bool, name: String, date: Date?) async -> Bool {
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                let o = PHAssetResourceCreationOptions()
                o.originalFilename = name
                req.addResource(with: video ? .video : .photo, fileURL: url, options: o)
                if let date { req.creationDate = date }
            }
            return true
        } catch { return false }
    }
}

// MARK: - Sender

/// "Switch phones" from this iPhone: library and address book to one device, in resumable batches
/// (the receiver's trust grant lets every batch after the first through without asking).
@MainActor
final class SwitchSender: ObservableObject {
    enum Phase { case preparing, waiting, moving, done, failed, declined, cancelled }

    struct Plan { var photos = 0, videos = 0, contacts = 0; var limited = false }

    struct State {
        var peerName: String
        var phase: Phase
        var batch = 0, batches = 0
        var itemsDone = 0, itemsTotal = 0
        var bytesPerSec: Int64 = 0
        var error: String?
        var fraction: Double { itemsTotal == 0 ? 0 : min(1, Double(itemsDone) / Double(itemsTotal)) }
        var finished: Bool { [.done, .failed, .declined, .cancelled].contains(phase) }
    }

    @Published private(set) var state: State?
    private var task: Task<Void, Never>?
    private var last: (Peer, Bool, Bool, Bool)?
    private let coordinator: TransferCoordinator
    private let history: HistoryStore

    init(coordinator: TransferCoordinator, history: HistoryStore) {
        self.coordinator = coordinator; self.history = history
    }

    func plan() -> Plan {
        Plan(photos: PhotosBridge.readAuthorized() ? PHAsset.fetchAssets(with: .image, options: nil).count : 0,
             videos: PhotosBridge.readAuthorized() ? PHAsset.fetchAssets(with: .video, options: nil).count : 0,
             contacts: ContactsBridge.count(), limited: PhotosBridge.isLimited())
    }

    func start(_ peer: Peer, photos: Bool, videos: Bool, contacts: Bool) {
        last = (peer, photos, videos, contacts)
        task?.cancel()
        state = State(peerName: peer.name, phase: .preparing)
        task = Task { await run(peer, photos: photos, videos: videos, contacts: contacts) }
    }

    func resume() { if let (p, a, b, c) = last { start(p, photos: a, videos: b, contacts: c) } }
    func cancel() { task?.cancel(); state?.phase = .cancelled; state?.bytesPerSec = 0 }
    func close() { if state?.finished != false { state = nil } }

    private enum Item { case contacts(URL, Int), asset(PHAsset) }

    private func run(_ peer: Peer, photos: Bool, videos: Bool, contacts: Bool) async {
        // Resume ledger per receiving device name, trusted for a few days only: a later move to a
        // different (or reset) device with the same name must not skip anything.
        let ledgerKey = "switch_done:" + peer.name, stampKey = "switch_when:" + peer.name
        if Date().timeIntervalSince1970 - UserDefaults.standard.double(forKey: stampKey) > 3 * 86_400 {
            UserDefaults.standard.removeObject(forKey: ledgerKey)
        }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: stampKey)
        var done = Set(UserDefaults.standard.stringArray(forKey: ledgerKey) ?? [])
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-switch-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        // No-ops when access is already there; makes sure PhotoKit/Contacts are ready whoever started us.
        if photos || videos { _ = await PhotosBridge.requestRead() }
        if contacts { _ = await ContactsBridge.requestAccess() }
        var items: [(String, Item)] = []
        var contactCount = 0
        if contacts, ContactsBridge.authorized() {
            let (vcf, n) = await Task.detached { ContactsBridge.exportAll() }.value
            contactCount = n
            if n > 0 {
                let url = dir.appendingPathComponent("Contacts.vcf")
                try? Data(vcf.utf8).write(to: url)
                items.append(("c:all", .contacts(url, n)))
            }
        }
        let photoAssets = photos ? PhotosBridge.fetch(.image) : []
        let videoAssets = videos ? PhotosBridge.fetch(.video) : []
        items += photoAssets.map { ("a:" + $0.localIdentifier, .asset($0)) }
        items += videoAssets.map { ("a:" + $0.localIdentifier, .asset($0)) }
        let todo = items.filter { !done.contains($0.0) }
        let batches = stride(from: 0, to: todo.count, by: Self.maxItems).map { Array(todo[$0..<min($0 + Self.maxItems, todo.count)]) }
        state = State(peerName: peer.name, phase: .waiting, batches: batches.count,
                      itemsDone: items.count - todo.count, itemsTotal: items.count)

        var unreadable = 0
        for (i, batch) in batches.enumerated() {
            if Task.isCancelled { return }
            state?.batch = i
            // Export this batch only (bounded temp space), then send it. Only what was actually
            // exported counts as moved — the rest stays out of the ledger so a resume retries it.
            var files: [OutgoingFile] = []
            var sentKeys: [String] = []
            var names = Set<String>()
            for (key, item) in batch {
                switch item {
                case .contacts(let url, _):
                    let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                    files.append(OutgoingFile(meta: FileMeta(name: "Contacts.vcf", size: size, mime: "text/vcard", cat: ItemCategory.contacts), url: url))
                    sentKeys.append(key)
                case .asset(let a):
                    guard let url = try? await PhotosBridge.export(a, into: dir) else { unreadable += 1; continue }
                    sentKeys.append(key)
                    let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                    var name = String(url.lastPathComponent.drop(while: { $0 != "-" }).dropFirst())
                    if name.isEmpty { name = url.lastPathComponent }
                    var unique = name, n = 1
                    while !names.insert(unique.lowercased()).inserted {
                        let ext = (name as NSString).pathExtension, stem = (name as NSString).deletingPathExtension
                        unique = ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)"; n += 1
                    }
                    let video = a.mediaType == .video
                    files.append(OutgoingFile(meta: FileMeta(
                        name: unique, size: size, mime: UTTypeMime.of(url),
                        cat: video ? ItemCategory.video : ItemCategory.photo,
                        mtime: a.creationDate.map { Int64($0.timeIntervalSince1970 * 1000) }), url: url))
                }
            }
            let info = BatchInfo(index: i, count: batches.count, photos: photoAssets.count, videos: videoAssets.count, contacts: contactCount)
            let result = await sendBatch(peer, files: files, info: info)
            files.forEach { if $0.meta.cat != ItemCategory.contacts { try? FileManager.default.removeItem(at: $0.url) } }
            switch result.status {
            case .completed:
                done.formUnion(sentKeys)
                UserDefaults.standard.set(Array(done), forKey: ledgerKey)
                state?.itemsDone += sentKeys.count
                state?.phase = .moving
            case .declined: state?.phase = .declined; state?.bytesPerSec = 0; return
            case .cancelled: state?.phase = .cancelled; state?.bytesPerSec = 0; return
            default:
                state?.phase = .failed; state?.error = result.error ?? "connection lost"; state?.bytesPerSec = 0
                return
            }
        }
        if unreadable > 0 {
            // Typically iCloud originals that couldn't be downloaded right now: say so, keep the ledger.
            state?.phase = .failed; state?.bytesPerSec = 0
            state?.error = "\(unreadable) item(s) couldn't be read (iCloud photos need a connection). Tap Resume to try them again."
            return
        }
        UserDefaults.standard.removeObject(forKey: ledgerKey)
        state?.phase = .done; state?.bytesPerSec = 0; state?.itemsDone = items.count
        history.add(HistoryEntry(ts: Date().timeIntervalSince1970 * 1000, direction: "SENDING", peerName: peer.name,
                                 summary: "Switch phones", files: items.count, bytes: 0, status: "COMPLETED"))
    }

    /// One batch through the coordinator, awaited; progress is mirrored into `state`.
    private func sendBatch(_ peer: Peer, files: [OutgoingFile], info: BatchInfo) async -> SessionState {
        guard !files.isEmpty else { return SessionState(direction: .sending, peerName: peer.name, status: .completed) }
        guard let gid = coordinator.send([peer], files: files, options: SendOptions(kind: OfferKind.switchPhones, batch: info),
                                         quiet: true, scoped: [], temporary: []) else {
            var s = SessionState(direction: .sending, peerName: peer.name, status: .failed); s.error = "device unreachable"; return s
        }
        while true {
            if Task.isCancelled { coordinator.cancelGroup(gid) }
            if let s = coordinator.sessions.first(where: { $0.groupId == gid }) {
                if s.status == .transferring { state?.phase = .moving; state?.bytesPerSec = s.bytesPerSec }
                if s.status.isTerminal { coordinator.pruneQuiet(kind: OfferKind.switchPhones); return s }
            } else {
                return SessionState(direction: .sending, peerName: peer.name, status: .cancelled)
            }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
    }

    static let maxItems = 50
}

enum UTTypeMime {
    static func of(_ url: URL) -> String? {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "heic", "heif": return "image/heic"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "mov": return "video/quicktime"
        case "mp4", "m4v": return "video/mp4"
        default: return nil
        }
    }
}

// MARK: - Importer

/// "Switch phones" arriving on this iPhone/iPad/Mac: photos and videos go into the library with their
/// original dates, contacts into the address book (duplicates skipped), anything else into the inbox.
final class SwitchImporter: ObservableObject, SwitchReceiving, @unchecked Sendable {
    enum Phase { case receiving, importing, done, failed }

    struct State {
        var peerName: String
        var phase: Phase = .receiving
        var batch = 0, batches = 1
        var photosTotal = 0, videosTotal = 0, contactsTotal = 0
        var photos = 0, videos = 0, contactsAdded = 0, contactsSkipped = 0, files = 0, failedItems = 0
        var error: String?
    }

    @MainActor @Published private(set) var state: State?
    private let dir: URL
    private let inbox: InboxStore
    private let history: HistoryStore
    private weak var coordinator: TransferCoordinator?
    private var queue: [(String, Offer, [URL])] = []
    private var importing = false

    init(inbox: InboxStore, history: HistoryStore) {
        self.inbox = inbox; self.history = history
        dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("switch-in", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var noBackup = URLResourceValues(); noBackup.isExcludedFromBackup = true
        var d = dir; try? d.setResourceValues(noBackup)
    }

    @MainActor func attach(_ c: TransferCoordinator) { coordinator = c }

    /// One folder per batch (`b<index>`; a retried batch reuses it, so its chunks resume) and
    /// index-prefixed names, so names that sanitise alike can't overwrite each other.
    private func batchDir(_ offer: Offer) -> URL { dir.appendingPathComponent("b\(offer.batch?.index ?? 0)", isDirectory: true) }

    private static func stagedName(_ offer: Offer, _ meta: FileMeta) -> String {
        let i = offer.files.firstIndex(of: meta) ?? 0
        return String(format: "%05d_", i) + FileDeltaStore.sanitizedFileName(meta.name)
    }

    func store(for offer: Offer) -> FileDeltaStore {
        FileDeltaStore(inboxDir: batchDir(offer)) { SwitchImporter.stagedName(offer, $0) }
    }

    func batchFile(_ offer: Offer, _ meta: FileMeta) throws -> (url: URL, sink: OutputSink) {
        let url = store(for: offer).targetFor(meta)
        guard FileManager.default.createFile(atPath: url.path, contents: nil),
              let h = FileHandle(forWritingAtPath: url.path) else { throw ProtocolError("cannot create \(meta.name)") }
        return (url, HandleSink(h))
    }

    @MainActor func begin(peerName: String, batch: BatchInfo?) {
        state = State(peerName: peerName, batches: batch?.count ?? 1, photosTotal: batch?.photos ?? 0,
                      videosTotal: batch?.videos ?? 0, contactsTotal: batch?.contacts ?? 0)
        Task { _ = await PhotosBridge.requestAdd() }
        if (batch?.contacts ?? 0) > 0 { Task { _ = await ContactsBridge.requestAccess() } }
    }

    @MainActor func importBatch(peerName: String, offer: Offer, files: [URL]) {
        queue.append((peerName, offer, files))
        guard !importing else { return }
        importing = true
        Task { @MainActor in
            while !queue.isEmpty {
                let (p, o, f) = queue.removeFirst()
                await importNow(p, o, f)
            }
            importing = false
        }
    }

    @MainActor private func importNow(_ peerName: String, _ offer: Offer, _ files: [URL]) async {
        if state == nil { begin(peerName: peerName, batch: offer.batch) }
        state?.phase = .importing
        state?.batch = offer.batch?.index ?? 0
        let canAdd = await PhotosBridge.requestAdd()
        for (meta, url) in zip(offer.files, files) {
            guard FileManager.default.fileExists(atPath: url.path) else { state?.failedItems += 1; continue }
            let video = ["mov", "mp4", "m4v", "3gp"].contains(url.pathExtension.lowercased()) || meta.mime?.hasPrefix("video/") == true
            let image = ["jpg", "jpeg", "heic", "heif", "png", "gif", "webp", "dng"].contains(url.pathExtension.lowercased()) || meta.mime?.hasPrefix("image/") == true
            switch meta.cat {
            case .some(ItemCategory.photo) where canAdd && (video || image), .some(ItemCategory.video) where canAdd && (video || image):
                let ok = await PhotosBridge.save(url, video: video, name: meta.name,
                                                 date: meta.mtime.map { Date(timeIntervalSince1970: Double($0) / 1000) })
                if ok { if video { state?.videos += 1 } else { state?.photos += 1 }; try? FileManager.default.removeItem(at: url) }
                else { state?.failedItems += 1; moveToInbox(url, meta) }
            case .some(ItemCategory.contacts):
                if ContactsBridge.authorized(), let data = try? Data(contentsOf: url) {
                    let r = await Task.detached { ContactsBridge.importVCard(data) }.value
                    state?.contactsAdded += r.added; state?.contactsSkipped += r.skipped
                    try? FileManager.default.removeItem(at: url)
                } else { moveToInbox(url, meta) }
            default:
                moveToInbox(url, meta); state?.files += 1
            }
        }
        try? FileManager.default.removeItem(at: batchDir(offer))     // this batch's folder; the next has its own
        let last = offer.batch.map { $0.index >= $0.count - 1 } ?? true
        if last {
            coordinator?.trust.revoke(kind: OfferKind.switchPhones)
            state?.phase = .done
            if let s = state {
                history.add(HistoryEntry(ts: Date().timeIntervalSince1970 * 1000, direction: "RECEIVING", peerName: peerName,
                                         summary: "Switch phones", files: s.photos + s.videos + s.contactsAdded + s.files,
                                         bytes: 0, status: "COMPLETED"))
            }
        } else {
            state?.phase = .receiving
        }
        inbox.refresh()
    }

    private func moveToInbox(_ url: URL, _ meta: FileMeta) {
        guard let (target, sink) = try? inbox.newFile(meta) else { return }
        sink.close()
        try? FileManager.default.removeItem(at: target)
        try? FileManager.default.moveItem(at: url, to: target)
    }

    @MainActor func batchEnded(failed: Bool, error: String?) {
        state?.phase = .failed
        state?.error = failed ? "Connection lost. Start again on the other phone — it picks up where it stopped."
            : "The other phone stopped the move."
    }

    @MainActor func stop() {
        coordinator?.trust.revoke(kind: OfferKind.switchPhones)
        coordinator?.pruneQuiet(kind: OfferKind.switchPhones)
        state = nil
    }

    @MainActor func close() {
        coordinator?.pruneQuiet(kind: OfferKind.switchPhones)
        if state?.phase == .done || state?.phase == .failed { state = nil }
    }
}
