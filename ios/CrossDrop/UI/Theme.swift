import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

/// Neon Aurora palette — dark-only by design (mirrors `ui/theme/Theme.kt`).
enum Aurora {
    static let deepSpace = Color(hex: 0x030014)
    static let deepSpaceSoft = Color(hex: 0x0A0620)
    static let deepSpaceRaised = Color(hex: 0x141033)
    static let surfaceHigh = Color(hex: 0x201851)
    static let teal = Color(hex: 0x3BFFB0)
    static let tealDim = Color(hex: 0x1FBF8A)
    static let cyan = Color(hex: 0x45E5FF)
    static let violet = Color(hex: 0x9D6BFF)
    static let pink = Color(hex: 0xFF4FD8)
    static let phosphor = Color(hex: 0xB8FFE9)
    static let textPrimary = Color(hex: 0xEEF2FF)
    static let textDim = Color(hex: 0x8B93BB)
    static let danger = Color(hex: 0xFF5470)
    static let glassEdge = Color(hex: 0x8BFFDC, alpha: 0.24)
    static let glassFill = Color(hex: 0x78E0FF, alpha: 0.08)

    /// Signature aurora sweep — mint → cyan → violet → magenta.
    static let gradient = LinearGradient(colors: [teal, cyan, violet, pink], startPoint: .leading, endPoint: .trailing)
    static let cardGradient = LinearGradient(colors: [Color(hex: 0x1C1550), Color(hex: 0x100A2E)], startPoint: .top, endPoint: .bottom)

    static func tint(_ c: Color) -> LinearGradient {
        LinearGradient(colors: [c.opacity(0.26), c.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Ambient aurora haze behind full-screen overlays.
struct AuroraBackdrop: View {
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack {
                Aurora.deepSpace
                glow(Aurora.teal, 0.10, CGPoint(x: w * 0.18, y: h * 0.08), w * 0.95)
                glow(Aurora.violet, 0.11, CGPoint(x: w * 0.88, y: h * 0.30), w * 0.85)
                glow(Aurora.cyan, 0.06, CGPoint(x: w * 0.45, y: h * 0.95), w * 1.1)
                glow(Aurora.pink, 0.05, CGPoint(x: w * 0.75, y: h * 0.78), w * 0.7)
            }
        }
        .ignoresSafeArea()
    }

    private func glow(_ c: Color, _ a: Double, _ center: CGPoint, _ r: CGFloat) -> some View {
        RadialGradient(colors: [c.opacity(a), .clear], center: .center, startRadius: 0, endRadius: r)
            .frame(width: r * 2, height: r * 2)
            .position(center)
            .allowsHitTesting(false)
    }
}

/// Glass-edged raised card — the app's standard container.
struct GlassCard<Content: View>: View {
    var radius: CGFloat = 18
    var stroke: Color = Aurora.glassEdge
    @ViewBuilder var content: () -> Content
    var body: some View {
        content()
            .background(Aurora.deepSpaceRaised, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(stroke, lineWidth: 1))
    }
}

struct ScreenTitle: View {
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.cd(24, weight: .heavy)).foregroundColor(Aurora.textPrimary)
            Text(subtitle).font(.cd(12)).foregroundColor(Aurora.textDim)
        }
    }
}

/// Small tinted tile showing a file's extension — the app's file-type glyph.
struct FileTypeTile: View {
    let fileName: String
    var size: CGFloat = 42

    var body: some View {
        let ext = (fileName as NSString).pathExtension.prefix(4).uppercased()
        let label = ext.isEmpty ? "···" : ext
        let tint = Self.tint(for: label.lowercased())
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(Aurora.tint(tint))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(tint.opacity(0.35), lineWidth: 1))
            .overlay(Text(label).font(.system(size: label.count > 3 ? 8 : 9.5, weight: .bold)).tracking(0.5).foregroundColor(tint))
            .frame(width: size, height: size)
    }

    static func tint(for ext: String) -> Color {
        switch ext {
        case "jpg", "jpeg", "png", "gif", "webp", "heic", "svg": return Aurora.cyan
        case "mp4", "mkv", "mov", "webm", "avi": return Aurora.violet
        case "mp3", "m4a", "flac", "ogg", "wav", "opus": return Aurora.phosphor
        case "pdf", "doc", "docx", "txt", "md", "epub": return Aurora.pink
        case "apk": return Aurora.teal
        case "zip", "rar", "7z", "tar", "gz": return Aurora.violet
        default: return Aurora.textDim
        }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var color: Color = Aurora.teal
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.cd(15, weight: .semibold))
            .foregroundColor(Aurora.deepSpace)
            .padding(.horizontal, 22).padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(color.opacity(configuration.isPressed ? 0.7 : 1), in: Capsule())
            .hoverEffect(.highlight)
    }
}

struct OutlineButtonStyle: ButtonStyle {
    var color: Color = Aurora.danger
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.cd(15, weight: .semibold))
            .foregroundColor(color)
            .padding(.horizontal, 22).padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .overlay(Capsule().stroke(color.opacity(configuration.isPressed ? 0.25 : 0.5), lineWidth: 1))
            .hoverEffect(.highlight)
    }
}

// MARK: - Typography / layout helpers

extension Font {
    /// Fixed-design size that still follows Dynamic Type (capped so layouts built for ~1.0 don't break).
    static func cd(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: size)
        return .system(size: min(scaled, size * 1.6), weight: weight, design: design)
    }
}

extension View {
    /// Keeps content readable on iPad / Mac windows instead of stretching list rows across 1500 pt.
    func readableWidth(_ max: CGFloat = 720) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }

    /// Pointer hover feedback (iPad trackpad / Mac cursor).
    func pointerHover() -> some View { hoverEffect(.highlight) }
}

/// ScrollView whose content stays a readable column on wide windows (iPad, Mac).
struct ReadableScroll<Content: View>: View {
    var maxWidth: CGFloat = 640
    @ViewBuilder var content: () -> Content
    var body: some View {
        ScrollView { content().frame(maxWidth: maxWidth).frame(maxWidth: .infinity) }
    }
}
