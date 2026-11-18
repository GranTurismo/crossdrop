import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

/// Sonar palette — dark-only by design (mirrors `app/.../ui/theme/Theme.kt`). Near-black ink, one
/// luminous mint accent, translucent "glass" surfaces (white at 4–10 %) and three quiet category hues
/// (amber, violet, sky) for file types and status.
enum CD {
    static let ink = Color(hex: 0x06080C)
    static let inkSheet = Color(hex: 0x13171D)
    static let inkNav = Color(hex: 0x161A20)

    static let accent = Color(hex: 0x5CF2C0)
    static let onAccent = Color(hex: 0x04130D)
    static let onAccentMuted = Color(hex: 0x0C3A2B)
    static let accentText = Color(hex: 0x7CF5CD)

    static let textPrimary = Color(hex: 0xF4F7F9)
    static let textSecondary = Color(hex: 0xC8D0D8)
    static let textDim = Color(hex: 0x9AA6B2)
    static let textFaint = Color(hex: 0x8B97A3)
    static let navIdle = Color(hex: 0xAEB8C2)

    static let amber = Color(hex: 0xFFC163)
    static let violet = Color(hex: 0xA48BFF)
    static let violetText = Color(hex: 0xC3B5FF)
    static let sky = Color(hex: 0x4CC9FF)
    static let skyText = Color(hex: 0x7FD8FF)
    static let danger = Color(hex: 0xFF6B6B)
    static let dangerText = Color(hex: 0xFF9B9B)

    /// Glass fills — white at low alpha over `ink`.
    static let glassLow = Color.white.opacity(0.04)
    static let glass = Color.white.opacity(0.05)
    static let glassMid = Color.white.opacity(0.06)
    static let glassRaised = Color.white.opacity(0.07)
    static let glassHigh = Color.white.opacity(0.08)

    /// Hairline borders.
    static let hairlineSoft = Color.white.opacity(0.06)
    static let hairline = Color.white.opacity(0.08)
    static let hairlineStrong = Color.white.opacity(0.10)
    /// Border of grouped list cards — between `hairlineSoft` and `hairline`.
    static let groupBorder = Color.white.opacity(0.07)
}

// MARK: - Type

/// Sora (display: titles, big numbers) and JetBrains Mono (figures: sizes, speeds, codes, addresses)
/// are bundled Latin subsets, SemiBold only (`Resources/Fonts`, registered via `UIAppFonts`). Body text
/// is the system font. Sizes follow Dynamic Type, capped so fixed-design layouts don't break.
enum CDFont {
    static let display = "Sora-SemiBold"
    static let mono = "JetBrainsMono-SemiBold"

    static func scaled(_ size: CGFloat) -> CGFloat {
        min(UIFontMetrics(forTextStyle: .body).scaledValue(for: size), size * 1.6)
    }

    #if DEBUG
    /// Logs once if a bundled face failed to register (SwiftUI silently falls back to the system font).
    static func checkRegistered() {
        for name in [display, mono] where UIFont(name: name, size: 12) == nil {
            NSLog("CrossDrop: font %@ is not registered", name)
        }
    }
    #endif
}

extension Font {
    /// System body text at a fixed design size that still follows Dynamic Type.
    static func cd(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        .system(size: CDFont.scaled(size), weight: weight, design: design)
    }

    /// Sora SemiBold.
    static func display(_ size: CGFloat) -> Font { .custom(CDFont.display, fixedSize: CDFont.scaled(size)) }

    /// JetBrains Mono SemiBold (ligatures are stripped from the bundled subset, so "://" reads as typed).
    static func mono(_ size: CGFloat) -> Font { .custom(CDFont.mono, fixedSize: CDFont.scaled(size)) }
}

extension CDFont {
    /// Letter-spacing in points for `size`, dropped for scripts whose letters must stay joined or shaped
    /// (Arabic, Persian, Hindi, Georgian): tracking there detaches the letters. Use for all translated text.
    static func tracking(_ size: CGFloat, em: CGFloat) -> CGFloat { I18n.isCursiveOrCaseless ? 0 : scaled(size) * em }
}

extension String {
    /// Capitals for small labels, only where the script has them (Turkish gets its dotted İ).
    var caps: String { I18n.isCursiveOrCaseless ? self : uppercased(with: Locale(identifier: I18n.language)) }
}

extension Text {
    /// Display text (Sora) — titles and big figures. `tracking` is in em. Sora is Latin-only; other scripts fall back to the system font.
    func displayStyle(_ size: CGFloat, tracking: CGFloat = -0.03, color: Color = CD.textPrimary) -> Text {
        font(.display(size)).tracking(CDFont.tracking(size, em: tracking)).foregroundColor(color)
    }

    /// Figures (JetBrains Mono).
    func monoStyle(_ size: CGFloat, color: Color = CD.textPrimary) -> Text {
        font(.mono(size)).foregroundColor(color)
    }

    /// Body text in the system font.
    func body(_ size: CGFloat, _ color: Color = CD.textPrimary, weight: Font.Weight = .regular) -> Text {
        font(.cd(size, weight: weight)).foregroundColor(color)
    }
}

// MARK: - Ambient light

/// One soft radial glow behind a screen's content — the design's ambient light. `centerX` is a fraction
/// of the width; `centerY` and `radius` are absolute (from the top of the screen) so the glow sits at the
/// same spot on every phone.
struct ScreenGlow: View {
    var color: Color = CD.accent
    var alpha: Double = 0.18
    var centerX: CGFloat = 0.5
    var centerY: CGFloat = 300
    var radius: CGFloat = 250

    var body: some View {
        GeometryReader { geo in
            RadialGradient(colors: [color.opacity(alpha), color.opacity(0)], center: .center,
                           startRadius: 0, endRadius: radius)
                .frame(width: radius * 2, height: radius * 2)
                .position(x: geo.size.width * centerX, y: centerY)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - Layout helpers

extension View {
    /// Keeps content readable on iPad / Mac windows instead of stretching rows across 1500 pt.
    func readableWidth(_ max: CGFloat = 640) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }

    /// Pointer hover feedback (iPad trackpad / Mac cursor).
    func pointerHover() -> some View { hoverEffect(.highlight) }
}

/// How much of the bottom edge the floating nav covers — scrolling tabs pad their content by it so the
/// last row can scroll clear of the glass bar.
private struct NavInsetKey: EnvironmentKey { static let defaultValue: CGFloat = 0 }

extension EnvironmentValues {
    var navInset: CGFloat {
        get { self[NavInsetKey.self] }
        set { self[NavInsetKey.self] = newValue }
    }
}
