import SwiftUI

/// What a file is, for its tile and the inbox's storage breakdown (mirrors Android's `FileKind`).
enum FileKind: String, CaseIterable {
    case photo = "Photos", video = "Videos", audio = "Audio", doc = "Documents"
    case archive = "Archives", app = "Apps", folder = "Folders", other = "Other"

    var label: String { rawValue }

    var icon: CdIcon {
        switch self {
        case .photo: return CdIcons.photo
        case .video: return CdIcons.video
        case .audio: return CdIcons.music
        case .doc: return CdIcons.doc
        case .archive: return CdIcons.archive
        case .app: return CdIcons.apk
        case .folder: return CdIcons.folderIcon
        case .other: return CdIcons.file
        }
    }

    /// Icon colour on the tile.
    var tint: Color {
        switch self {
        case .photo, .app: return CD.accent
        case .video: return CD.violetText
        case .audio, .archive: return CD.skyText
        case .doc, .folder: return CD.amber
        case .other: return CD.textSecondary
        }
    }

    /// Solid swatch for charts and legends.
    var swatch: Color {
        switch self {
        case .photo, .app: return CD.accent
        case .video: return CD.violet
        case .audio, .archive, .other: return CD.sky
        case .doc, .folder: return CD.amber
        }
    }

    var tileBg: Color {
        switch self {
        case .other: return CD.glassHigh
        case .video: return CD.violet.opacity(0.16)
        default: return swatch.opacity(0.14)
        }
    }

    static func of(_ fileName: String, isDir: Bool = false) -> FileKind {
        if isDir { return .folder }
        switch (fileName as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "svg", "bmp", "avif", "dng", "raw": return .photo
        case "mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp": return .video
        case "mp3", "m4a", "flac", "ogg", "wav", "opus", "aac": return .audio
        case "pdf", "doc", "docx", "txt", "md", "epub", "rtf", "odt", "xls", "xlsx", "csv",
             "ppt", "pptx", "key", "pages", "numbers", "fig": return .doc
        case "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2": return .archive
        case "apk", "aab", "xapk", "ipa", "dmg", "exe", "msi": return .app
        default: return .other
        }
    }
}

/// Tinted tile with the file's category icon — the app's file-type glyph.
struct FileTypeTile: View {
    let fileName: String
    var size: CGFloat = 42
    var isDir = false

    var body: some View {
        let kind = FileKind.of(fileName, isDir: isDir)
        IconTile(icon: kind.icon, tint: kind.tint, bg: kind.tileBg, size: size, radius: size * 0.32, iconSize: size * 0.46)
            .accessibilityHidden(true)
    }
}
