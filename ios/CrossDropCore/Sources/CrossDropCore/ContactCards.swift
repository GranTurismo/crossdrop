import Foundation

/// Sending a few contact cards on their own (the Swift twin of Kotlin `ContactShare`): one `.vcf` in an
/// offer of kind `contact`, plus a photo-less copy inline so the receiver can show who it is before
/// accepting. Older receivers simply get the `.vcf` file.
public enum ContactCards {
    /// A shared `.vcf` bigger than this goes as a plain file — that's a backup, not a few cards.
    public static let maxCardFile: Int64 = 8 * 1024 * 1024

    private static let vcardMimes: Set<String> = ["text/vcard", "text/x-vcard", "text/directory"]

    public static func isVCard(name: String?, mime: String?) -> Bool {
        if let m = mime?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased(), vcardMimes.contains(m) {
            return true
        }
        guard let n = name?.trimmingCharacters(in: .whitespaces).lowercased() else { return false }
        return n.hasSuffix(".vcf") || n.hasSuffix(".vcard")
    }

    /// The prompt's copy of `vcf`: the cards without photos, or nil when even that is too long.
    public static func preview(_ vcf: String) -> String? {
        let s = stripPhotos(vcf)
        guard !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, s.utf8.count <= maxInlineText else { return nil }
        return s
    }

    private static let binaryProps: Set<String> = ["PHOTO", "LOGO", "SOUND", "KEY"]

    /// Drops PHOTO, LOGO, SOUND and KEY properties together with their folded continuation lines.
    public static func stripPhotos(_ vcf: String) -> String {
        var out: [Substring] = []
        var skipping = false
        // Split on "\n" only: "\r\n" is one grapheme in Swift, so split on the scalar view.
        let lines = vcf.unicodeScalars.split(separator: "\n", omittingEmptySubsequences: false).map { Substring($0) }
        for line in lines {
            if line.hasPrefix(" ") || line.hasPrefix("\t") {
                if !skipping { out.append(line) }
                continue
            }
            let head = line.split(separator: ":", maxSplits: 1).first.map(String.init) ?? ""
            let name = (head.split(separator: ";", maxSplits: 1).first.map(String.init) ?? "")
                .split(separator: ".").last.map { $0.trimmingCharacters(in: .whitespaces).uppercased() } ?? ""
            skipping = binaryProps.contains(name)
            if !skipping { out.append(line) }
        }
        return out.joined(separator: "\n")
    }

    /// "Jane Doe.vcf", or "3 contacts.vcf".
    public static func fileName(names: [String]) -> String {
        let base = names.count == 1 ? safeName(names[0]) : "\(names.count) contacts"
        return base + ".vcf"
    }

    /// What prompts and history call the cards: "Jane Doe", "Jane Doe and 2 others".
    public static func title(names: [String]) -> String {
        switch names.count {
        case 0: return "Contact"
        case 1: return names[0]
        case 2: return "\(names[0]) and \(names[1])"
        default: return "\(names[0]) and \(names.count - 1) others"
        }
    }

    private static func safeName(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)
        let cleaned = String(String.UnicodeScalarView(s.unicodeScalars.map { bad.contains($0) ? "_" : $0 }))
            .trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let short = String(cleaned.prefix(60))
        return short.isEmpty ? "Contact" : short
    }
}
