import Foundation
import Contacts
import ContactsUI
import UIKit
@preconcurrency import CrossDropCore

// MARK: - Preview

/// A contact as the offer prompt shows it.
struct ContactPreview: Identifiable {
    let id = UUID()
    let name: String
    let detail: String?

    /// The cards in a contact offer's inline (photo-less) copy.
    static func parse(_ vcf: String?) -> [ContactPreview] {
        guard let vcf, let data = vcf.data(using: .utf8),
              let list = try? CNContactVCardSerialization.contacts(with: data) else { return [] }
        return list.compactMap { c in
            func s(_ key: String, _ get: () -> String) -> String { c.isKeyAvailable(key) ? get() : "" }
            let name = [s(CNContactGivenNameKey) { c.givenName }, s(CNContactFamilyNameKey) { c.familyName }]
                .filter { !$0.isEmpty }.joined(separator: " ")
            let org = s(CNContactOrganizationNameKey) { c.organizationName }
            let phone = c.isKeyAvailable(CNContactPhoneNumbersKey) ? c.phoneNumbers.first?.value.stringValue : nil
            let email = c.isKeyAvailable(CNContactEmailAddressesKey) ? c.emailAddresses.first.map { $0.value as String } : nil
            let shown = !name.isEmpty ? name : !org.isEmpty ? org : (phone ?? email ?? "")
            guard !shown.isEmpty else { return nil }
            let detail = [phone, email, org.isEmpty || org == shown ? nil : org].compactMap { $0 }.first { $0 != shown }
            return ContactPreview(name: shown, detail: detail)
        }
    }
}

// MARK: - Receiving

/// Receiving end of contact cards sent on their own. Called from engine threads (`stagingFile`) and the
/// main thread (`received`).
protocol ContactReceiving: AnyObject, Sendable {
    func stagingFile(_ meta: FileMeta) throws -> (url: URL, sink: OutputSink)
    @MainActor func received(peerName: String, offer: Offer, files: [URL])
}

/// Contact cards someone sent: added straight to the address book (people already there are skipped),
/// or kept in the inbox as a `.vcf` while CrossDrop may not add contacts.
final class ContactInbox: ObservableObject, ContactReceiving, @unchecked Sendable {
    struct Drop: Identifiable {
        let id = UUID()
        var peerName: String
        var names: [String]
        var added = 0
        var skipped = 0
        /// Identifier of the first contact added, to show it.
        var firstAdded: String?
        /// Saved to the inbox instead, because CrossDrop may not add contacts (yet).
        var kept: URL?
        /// Not a card anything here could read; it's kept in the inbox.
        var unreadable = false
    }

    @MainActor @Published private(set) var drop: Drop?
    private let staging: URL
    private let inbox: InboxStore

    init(inbox: InboxStore) {
        self.inbox = inbox
        staging = FileManager.default.temporaryDirectory.appendingPathComponent("contacts-in", isDirectory: true)
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
    }

    func stagingFile(_ meta: FileMeta) throws -> (url: URL, sink: OutputSink) {
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let url = staging.appendingPathComponent(UUID().uuidString + ".vcf")
        guard FileManager.default.createFile(atPath: url.path, contents: nil),
              let h = FileHandle(forWritingAtPath: url.path) else { throw ProtocolError("cannot stage \(meta.name)") }
        return (url, HandleSink(h))
    }

    @MainActor func received(peerName: String, offer: Offer, files: [URL]) {
        let data = files.reduce(into: Data()) { acc, u in if let d = try? Data(contentsOf: u) { acc.append(d) } }
        files.forEach { try? FileManager.default.removeItem(at: $0) }
        let names = ContactPreview.parse(String(data: data, encoding: .utf8)).map(\.name)
        let fileName = offer.files.first?.name ?? ContactCards.fileName(names: names)
        if names.isEmpty {
            drop = Drop(peerName: peerName, names: [], kept: keep(data, name: fileName), unreadable: true)
            return
        }
        if ContactsBridge.canAdd() {
            Task { @MainActor in
                let r = await Task.detached { ContactsBridge.importCards(data) }.value
                drop = Drop(peerName: peerName, names: names, added: r.added, skipped: r.skipped, firstAdded: r.firstAdded)
            }
        } else {
            drop = Drop(peerName: peerName, names: names, kept: keep(data, name: fileName))
        }
    }

    /// After the user allowed contacts access: add the cards that were set aside.
    @MainActor func addKept() async {
        guard var d = drop, let url = d.kept, !d.unreadable, ContactsBridge.canAdd(),
              let data = try? Data(contentsOf: url) else { return }
        let r = await Task.detached { ContactsBridge.importCards(data) }.value
        if r.added + r.skipped > 0 { try? FileManager.default.removeItem(at: url); inbox.refresh() }
        d.added = r.added; d.skipped = r.skipped; d.firstAdded = r.firstAdded; d.kept = nil
        drop = d
    }

    @MainActor func dismiss(_ id: UUID) { if drop?.id == id { drop = nil } }

    private func keep(_ data: Data, name: String) -> URL? {
        guard let (url, sink) = try? inbox.newFile(FileMeta(name: name, size: Int64(data.count), mime: "text/vcard")) else { return nil }
        try? sink.write([UInt8](data))
        sink.close()
        inbox.refresh()
        return url
    }
}

// MARK: - Picking

/// The system contact picker: no contacts permission needed — CrossDrop only sees who was picked.
/// Presented from UIKit, because SwiftUI sheets and this picker don't get along (it dismisses itself).
@MainActor
final class ContactPickerPresenter: NSObject, @preconcurrency CNContactPickerDelegate {
    private static var current: ContactPickerPresenter?
    private let onPick: ([CNContact]) -> Void

    private init(onPick: @escaping ([CNContact]) -> Void) { self.onPick = onPick }

    static func present(onPick: @escaping ([CNContact]) -> Void) {
        guard let top = topViewController() else { return }
        let p = ContactPickerPresenter(onPick: onPick)
        current = p
        let picker = CNContactPickerViewController()
        picker.delegate = p
        top.present(picker, animated: true)
    }

    // Implementing the multi-contact variant is what turns on multiple selection.
    func contactPicker(_ picker: CNContactPickerViewController, didSelect contacts: [CNContact]) {
        onPick(contacts)
        Self.current = nil
    }

    func contactPickerDidCancel(_ picker: CNContactPickerViewController) { Self.current = nil }

    /// Show a contact (one we just added) in the system contact card.
    static func show(contactId: String) {
        guard let top = topViewController(),
              let c = try? CNContactStore().unifiedContact(withIdentifier: contactId,
                                                           keysToFetch: [CNContactViewController.descriptorForRequiredKeys()]) else { return }
        let vc = CNContactViewController(for: c)
        let nav = UINavigationController(rootViewController: vc)
        vc.navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak nav] _ in
            nav?.dismiss(animated: true)
        })
        top.present(nav, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }
}
