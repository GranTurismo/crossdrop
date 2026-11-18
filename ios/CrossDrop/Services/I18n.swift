import Foundation
import SwiftUI

/// Translation runtime, the Swift twin of `core/.../i18n/I18n.kt` (same catalogs, same rules).
///
/// The English text IS the key: `tr("Send to {0}", name)`. A language is one JSON file, `l10n/<lang>.json`
/// (`"English text": "translation"`, or a plural object `{"one": "...", "other": "..."}`), shared with Android and
/// desktop; a missing entry falls back to the English text in the code. Placeholders are positional: `{0}`, `{1}`,
/// and `{n}` for a plural's count. The language follows the phone: the first of the user's languages we ship wins.
enum I18n {
    static let supported = ["en", "ka", "hi", "id", "ar", "fa", "es", "pt", "ru", "tr"]
    private static let rtl: Set<String> = ["ar", "fa"]

    private struct Catalog {
        var strings: [String: String] = [:]
        var plurals: [String: [String: String]] = [:]
    }

    private static let lock = NSLock()
    private static var catalogs: [String: Catalog] = [:]
    private static var lastTags: String?
    private static var lastLanguage = "en"

    /// Native names for the language list: each in its own script, so someone who can't read the current
    /// language can still find theirs. English first, same order as Android.
    static let nativeNames: [(code: String, name: String)] = [
        ("en", "English"), ("ka", "ქართული"), ("hi", "हिन्दी"), ("id", "Bahasa Indonesia"), ("ar", "العربية"),
        ("fa", "فارسی"), ("es", "Español"), ("pt", "Português (Brasil)"), ("ru", "Русский"), ("tr", "Türkçe"),
    ]
    static func nativeName(_ code: String) -> String { nativeNames.first { $0.code == code }?.name ?? code }

    /// The user's own pick from the in-app language list; nil means "automatic" (follow the phone). Stored under
    /// "language" ("auto" = automatic).
    private static var storedChoice: String? = {
        let v = UserDefaults.standard.string(forKey: "language")
        return v.flatMap { supported.contains($0) ? $0 : nil }
    }()

    static var choice: String? {
        lock.lock(); defer { lock.unlock() }
        return storedChoice
    }

    /// Pick a language from the in-app list, or nil for "automatic". The UI refreshes at once (`I18nModel`).
    static func setChoice(_ code: String?) {
        let next = code.flatMap { supported.contains($0) ? $0 : nil }
        lock.lock()
        if next == storedChoice { lock.unlock(); return }
        storedChoice = next
        lastTags = nil
        lock.unlock()
        UserDefaults.standard.set(next ?? "auto", forKey: "language")
        DispatchQueue.main.async { I18nModel.shared.version += 1 }
    }

    /// The user's languages, best first (iOS 16+ also reflects the per-app language set in Settings).
    static var preferred: () -> String = {
        #if DEBUG
        if let forced = UserDefaults.standard.string(forKey: "dev_lang") { return forced }     // -dev_lang ka
        #endif
        return Locale.preferredLanguages.joined(separator: ",")
    }

    static var language: String {
        #if DEBUG
        if let forced = UserDefaults.standard.string(forKey: "dev_lang") { return resolve(forced) }     // -dev_lang ka wins over a saved pick
        #endif
        let tags = preferred()
        lock.lock(); defer { lock.unlock() }
        if let picked = storedChoice { return picked }
        if tags == lastTags { return lastLanguage }
        let chosen = resolve(tags)
        lastTags = tags; lastLanguage = chosen
        return chosen
    }

    static func resolve(_ tags: String) -> String {
        for tag in tags.split(separator: ",") {
            guard let code = normalize(String(tag)) else { continue }
            if supported.contains(code) { return code }
        }
        return "en"
    }

    /// "pt-BR" → "pt", legacy "in" → "id", "ka_GE" → "ka"; nil for blanks.
    static func normalize(_ tag: String) -> String? {
        let primary = tag.trimmingCharacters(in: .whitespaces).lowercased()
            .split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? ""
        if primary.isEmpty { return nil }
        return primary == "in" ? "id" : primary
    }

    static var isRTL: Bool { rtl.contains(language) }

    /// Debug launch flags (`-dev_language_sheet YES`); always false in release builds.
    static func devFlag(_ key: String) -> Bool {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: key)
        #else
        return false
        #endif
    }

    /// Locale for dates and numbers: the app's language, with ASCII digits in every language (the UI never mixes digit sets).
    static var locale: Locale { Locale(identifier: language + "@numbers=latn") }

    /// Scripts where Latin typographic tricks (negative letter-spacing, forced capitals) break the text.
    static var isCursiveOrCaseless: Bool { ["ar", "fa", "hi", "ka"].contains(language) }

    // MARK: lookups

    static func tr(_ text: String, _ args: [CustomStringConvertible]) -> String {
        let found = catalog(language).strings[text] ?? text
        return format(found, n: nil, args)
    }

    /// `one` and `other` are the English forms; `other` is the catalog key. `{n}` is replaced by `n`.
    static func plural(_ n: Int, one: String, other: String, _ args: [CustomStringConvertible]) -> String {
        let lang = language
        let forms = catalog(lang).plurals[other]
        let text = forms?[category(lang, n)] ?? forms?["other"] ?? ((lang == "en" || forms == nil) ? (n == 1 ? one : other) : other)
        return format(text, n: n, args)
    }

    static func format(_ text: String, n: Int?, _ args: [CustomStringConvertible]) -> String {
        if n == nil && args.isEmpty { return text }
        guard text.contains("{") else { return text }
        var out = ""
        var i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if c == "{", let close = text[i...].firstIndex(of: "}") {
                let name = String(text[text.index(after: i)..<close])
                var replacement: String?
                if name == "n", let n { replacement = String(n) }
                else if !name.isEmpty, name.allSatisfy(\.isNumber), let idx = Int(name), idx < args.count { replacement = args[idx].description }
                if let replacement { out += replacement; i = text.index(after: close); continue }
            }
            out.append(c)
            i = text.index(after: i)
        }
        return out
    }

    /// CLDR plural category for a whole number.
    static func category(_ language: String, _ n: Int) -> String {
        let a = abs(n)
        switch language {
        case "ru":
            if a % 10 == 1 && a % 100 != 11 { return "one" }
            if (2...4).contains(a % 10) && !(12...14).contains(a % 100) { return "few" }
            return "many"
        case "ar":
            if a == 0 { return "zero" }
            if a == 1 { return "one" }
            if a == 2 { return "two" }
            if (3...10).contains(a % 100) { return "few" }
            if (11...99).contains(a % 100) { return "many" }
            return "other"
        case "hi", "fa", "pt": return a <= 1 ? "one" : "other"
        case "id": return "other"
        default: return a == 1 ? "one" : "other"
        }
    }

    private static func catalog(_ language: String) -> Catalog {
        if language == "en" { return Catalog() }
        lock.lock(); defer { lock.unlock() }
        if let cached = catalogs[language] { return cached }
        var result = Catalog()
        if let url = Bundle.main.url(forResource: language, withExtension: "json", subdirectory: "l10n"),
           let data = try? Data(contentsOf: url),
           let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            for (key, value) in root {
                if let s = value as? String, !s.isEmpty { result.strings[key] = s }
                else if let forms = value as? [String: String] { result.plurals[key] = forms.filter { !$0.value.isEmpty } }
            }
        }
        catalogs[language] = result
        return result
    }
}

/// Translate `text` (English, with `{0}` placeholders).
func tr(_ text: String, _ args: CustomStringConvertible...) -> String { I18n.tr(text, args) }

/// Plural: `one` and `other` are the English forms, `{n}` is the count.
func trPlural(_ n: Int, one: String, other: String, _ args: CustomStringConvertible...) -> String {
    I18n.plural(n, one: one, other: other, args)
}

/// Lets SwiftUI know the language changed: views that show translated text are rebuilt (`.id(version)` in `RootView`).
final class I18nModel: ObservableObject {
    static let shared = I18nModel()
    @Published var version = 0
}
