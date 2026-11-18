import SwiftUI

// Building blocks shared by every screen — the SwiftUI side of `app/.../ui/Components.kt`.

// MARK: - Surfaces

/// Translucent rounded card — the design's basic surface. `glow` adds a soft coloured shadow; an opaque
/// ink base sits under a glowing translucent fill so the shadow can't tint the card's interior (a SwiftUI
/// shadow is drawn from the shape's alpha, i.e. it would shine through the glass).
struct GlassCard<Content: View>: View {
    var radius: CGFloat = 26
    var fill: Color = CD.glass
    var border: Color? = CD.hairline
    var glow: Color?
    var glowRadius: CGFloat = 40
    var glowOffsetY: CGFloat = 0
    var padding: EdgeInsets = EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
    var spacing: CGFloat = 0
    var alignment: HorizontalAlignment = .leading
    var action: (() -> Void)?
    var actionLabel: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        if let action {
            Button(action: action) { card }
                .buttonStyle(PressableStyle())
                .accessibilityHint(actionLabel ?? "")
                .pointerHover()
        } else {
            card
        }
    }

    private var card: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return VStack(alignment: alignment, spacing: spacing, content: content)
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
            .background(
                ZStack {
                    if let glow {
                        shape.fill(CD.ink).shadow(color: glow, radius: glowRadius / 2, x: 0, y: glowOffsetY)
                    }
                    shape.fill(fill)
                }
            )
            .overlay { if let border { shape.strokeBorder(border, lineWidth: 1) } }
            .contentShape(shape)
    }
}

extension EdgeInsets {
    static func all(_ v: CGFloat) -> EdgeInsets { EdgeInsets(top: v, leading: v, bottom: v, trailing: v) }
    static func hv(_ h: CGFloat, _ v: CGFloat) -> EdgeInsets { EdgeInsets(top: v, leading: h, bottom: v, trailing: h) }
}

/// Grouped list: rows inside one card, split by `RowSeparator`s.
struct GroupCard<Content: View>: View {
    var padding: EdgeInsets = .hv(6, 4)
    @ViewBuilder var content: () -> Content
    var body: some View {
        GlassCard(radius: 26, fill: CD.glassLow, border: CD.groupBorder, padding: padding, content: content)
    }
}

/// 1 pt hairline between rows of a `GroupCard`.
struct RowSeparator: View {
    var color: Color = CD.hairlineSoft
    var body: some View { Rectangle().fill(color).frame(height: 1).accessibilityHidden(true) }
}

/// Dims slightly while pressed — the press feedback for custom-drawn buttons.
struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.72 : 1)
    }
}

// MARK: - Text

/// Big Sora screen title (tabs).
struct ScreenTitle: View {
    let text: String
    var size: CGFloat = 34
    init(_ text: String, size: CGFloat = 34) { self.text = text; self.size = size }
    var body: some View {
        Text(text).displayStyle(size, tracking: -0.04).lineLimit(1).minimumScaleFactor(0.7)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Small uppercase group label ("TODAY", "DISCOVERY").
struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.caps).font(.cd(12, weight: .semibold)).tracking(CDFont.tracking(12, em: 0.08))
            .foregroundColor(CD.textDim)
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Quiet explanatory line under a section, optionally led by an icon.
struct FootNote: View {
    let text: String
    var icon: CdIcon?
    init(_ text: String, icon: CdIcon? = nil) { self.text = text; self.icon = icon }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if let icon { CdIconView(icon, color: CD.textFaint, size: 16).padding(.top, 1) }
            Text(text).body(12, CD.textFaint).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
    }
}

// MARK: - Headers

/// Overlay header: round glass back button + Sora title, optional subtitle and trailing actions.
struct BackHeader<Subtitle: View, Trailing: View>: View {
    let title: String
    var backLabel: String? = nil           // nil = "Back" (read at draw time, so it follows the language)
    var titleSize: CGFloat = 22
    let onBack: () -> Void
    @ViewBuilder var subtitle: () -> Subtitle
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            CircleButton(icon: CdIcons.chevronLeft, label: backLabel ?? tr("Back"), iconSize: 20, action: onBack)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).displayStyle(titleSize).lineLimit(1).accessibilityAddTraits(.isHeader)
                subtitle()
            }
            Spacer(minLength: 0)
            trailing()
        }
    }
}

extension BackHeader where Subtitle == EmptyView, Trailing == EmptyView {
    init(_ title: String, backLabel: String? = nil, titleSize: CGFloat = 22, onBack: @escaping () -> Void) {
        self.init(title: title, backLabel: backLabel, titleSize: titleSize, onBack: onBack, subtitle: { EmptyView() }, trailing: { EmptyView() })
    }
}

// MARK: - Buttons

/// Round icon-only button (44 pt touch target by default).
struct CircleButton: View {
    let icon: CdIcon
    let label: String
    var size: CGFloat = 44
    var iconSize: CGFloat = 20
    var fill: Color = CD.glassRaised
    var tint: Color = CD.textPrimary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            CdIconView(icon, color: tint, size: iconSize)
                .frame(width: size, height: size)
                .background(Circle().fill(fill))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel(label)
        .pointerHover()
    }
}

enum PillStyle { case accent, glass, outline, danger }

/// Full-height pill button — primary (accent), secondary (glass/outline) or destructive.
struct PillButton: View {
    let text: String
    var style: PillStyle = .accent
    var height: CGFloat = 54
    var icon: CdIcon?
    var fontSize: CGFloat = 15
    var glow = false
    var fullWidth = true
    let action: () -> Void

    init(_ text: String, style: PillStyle = .accent, height: CGFloat = 54, icon: CdIcon? = nil, fontSize: CGFloat = 15,
         glow: Bool = false, fullWidth: Bool = true, action: @escaping () -> Void) {
        self.text = text; self.style = style; self.height = height; self.icon = icon; self.fontSize = fontSize
        self.glow = glow; self.fullWidth = fullWidth; self.action = action
    }

    private var colors: (bg: Color, fg: Color) {
        switch style {
        case .accent: return (CD.accent, CD.onAccent)
        case .glass, .outline: return (CD.glassRaised, CD.textPrimary)
        case .danger: return (Color(hex: 0xFF6B6B, alpha: 0.12), CD.dangerText)
        }
    }

    var body: some View {
        let c = colors
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { CdIconView(icon, color: c.fg, size: 18) }
                Text(text).font(.cd(fontSize, weight: .semibold)).foregroundColor(c.fg).lineLimit(1)
            }
            .padding(.horizontal, 18)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
        }
        .buttonStyle(PillButtonStyle(bg: c.bg, outline: style == .outline, glow: glow && style == .accent))
        .pointerHover()
    }
}

private struct PillButtonStyle: ButtonStyle {
    let bg: Color
    let outline: Bool
    let glow: Bool
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                ZStack {
                    if glow && enabled {
                        Capsule().fill(CD.accent).shadow(color: CD.accent.opacity(0.35), radius: 18, x: 0, y: 10)
                    }
                    Capsule().fill(bg)
                }
            )
            .overlay { if outline { Capsule().strokeBorder(CD.hairlineStrong, lineWidth: 1) } }
            .contentShape(Capsule())
            .opacity(enabled ? (configuration.isPressed ? 0.78 : 1) : 0.45)
    }
}

/// Compact pill (36–40 pt) for in-card actions: "Open", "Copy", "Clear".
struct ChipButton: View {
    let text: String
    var icon: CdIcon?
    var fg: Color = CD.textPrimary
    var bg: Color = CD.glassHigh
    var border: Color?
    var height: CGFloat = 36
    var fontSize: CGFloat = 13
    var label: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { CdIconView(icon, color: fg, size: 15) }
                Text(text).font(.cd(fontSize, weight: .semibold)).foregroundColor(fg).lineLimit(1)
            }
            .padding(.horizontal, 14)
            .frame(height: height)
            .background(Capsule().fill(bg))
            .overlay { if let border { Capsule().strokeBorder(border, lineWidth: 1) } }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel(label ?? text)
        .pointerHover()
    }
}

/// Copies `value` to the clipboard; the label flips to "Copied" as confirmation.
struct CopyButton: View {
    let value: String
    var label: String? = nil               // nil = "Copy"
    var accent = false
    var height: CGFloat = 36
    @State private var copied = false

    var body: some View {
        let title = label ?? tr("Copy")
        ChipButton(text: copied ? tr("Copied") : title, icon: copied ? CdIcons.check : CdIcons.copy,
                   fg: accent ? CD.onAccent : CD.textPrimary, bg: accent ? CD.accent : CD.glassHigh,
                   height: height, label: copied ? tr("Copied") : tr("{0}: {1}", title, value)) {
            UIPasteboard.general.string = value
            copied = true
            UIAccessibility.post(notification: .announcement, argument: tr("Copied"))
        }
        .onChange(of: value) { _ in copied = false }
    }
}

// MARK: - Controls

/// The design's 52×32 switch (visual only — wrap it in a `Toggle` via `CdToggleStyle` for semantics).
struct CdSwitch: View {
    let isOn: Bool
    var body: some View {
        ZStack(alignment: isOn ? .trailing : .leading) {
            Capsule().fill(isOn ? CD.accent : Color.white.opacity(0.14))
            Circle().fill(isOn ? CD.onAccent : CD.textSecondary).frame(width: 26, height: 26).padding(3)
        }
        .frame(width: 52, height: 32)
        .animation(.easeInOut(duration: 0.18), value: isOn)
        .accessibilityHidden(true)
    }
}

/// Settings-style toggle row: optional icon, title + subtitle, the 52×32 switch. A real `Toggle`, so
/// VoiceOver reads it as a switch.
struct ToggleRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool
    var icon: CdIcon?

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: 12) {
                if let icon { CdIconView(icon, color: CD.textPrimary, size: 22) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).body(14, weight: .semibold)
                    Text(subtitle).body(12, CD.textDim).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .toggleStyle(CdToggleStyle())
    }
}

struct CdToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(spacing: 12) {
                configuration.label
                Spacer(minLength: 8)
                CdSwitch(isOn: configuration.isOn)
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .accessibilityElement(children: .combine)
        .accessibilityValue(configuration.isOn ? tr("On") : tr("Off"))
        .accessibilityHint(tr("Double-tap to toggle"))
    }
}

/// Equal-width segmented tabs inside a soft track (Mac / Windows / Browser).
struct SegmentedTabs: View {
    let options: [String]
    @Binding var selected: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(options.enumerated()), id: \.offset) { i, label in
                let on = i == selected
                Button { selected = i } label: {
                    Text(label).font(.cd(13, weight: on ? .semibold : .medium))
                        .foregroundColor(on ? CD.ink : CD.textSecondary)
                        .frame(maxWidth: .infinity).frame(height: 36)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(on ? CD.textPrimary : .clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected] : [])
                .pointerHover()
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(CD.glass))
        .animation(.easeInOut(duration: 0.15), value: selected)
    }
}

/// Filter chip: filled light when selected, outlined otherwise.
struct FilterPill: View {
    let text: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.cd(13, weight: selected ? .semibold : .medium))
                .foregroundColor(selected ? CD.ink : CD.textSecondary)
                .padding(.horizontal, selected ? 16 : 14)
                .frame(height: 36)
                .background(Capsule().fill(selected ? CD.textPrimary : .clear))
                .overlay { if !selected { Capsule().strokeBorder(CD.hairlineStrong, lineWidth: 1) } }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .pointerHover()
    }
}

// MARK: - Indicators

/// Small status dot with an optional glow.
struct Dot: View {
    var color: Color = CD.accent
    var size: CGFloat = 7
    var glow = true
    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
            .shadow(color: glow ? color : .clear, radius: glow ? 5 : 0)
            .accessibilityHidden(true)
    }
}

/// Rounded status label: "Completed", "Failed".
struct StatusPill: View {
    let text: String
    let fg: Color
    let bg: Color
    var body: some View {
        Text(text).font(.cd(11, weight: .semibold)).foregroundColor(fg)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(bg))
    }
}

/// Header chip: dot + label in a glass capsule ("Wi-Fi · local only").
struct GlassChip: View {
    let text: String
    var dot: Color? = CD.accent
    var fg: Color = CD.textSecondary
    var body: some View {
        HStack(spacing: 7) {
            if let dot { Dot(color: dot) }
            Text(text).font(.cd(12, weight: .medium)).foregroundColor(fg).lineLimit(1)
        }
        .padding(.horizontal, 12)
        .frame(height: 34)
        .background(Capsule().fill(CD.glassMid))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.09), lineWidth: 1))
    }
}

/// Monospace tag (cipher names).
struct MonoTag: View {
    let text: String
    var body: some View {
        Text(text).monoStyle(11, color: CD.textSecondary)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(CD.glassMid))
    }
}

/// Tinted rounded tile holding an icon (file types, list leads).
struct IconTile: View {
    let icon: CdIcon
    let tint: Color
    var bg: Color?
    var size: CGFloat = 44
    var radius: CGFloat = 14
    var iconSize: CGFloat = 20
    var body: some View {
        CdIconView(icon, color: tint, size: iconSize)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(bg ?? tint.opacity(0.15)))
    }
}

/// Numbered step bubble for how-to lists.
struct StepNumber: View {
    let n: Int
    var body: some View {
        Text("\(n)").font(.cd(12, weight: .bold)).foregroundColor(CD.accent)
            .frame(width: 26, height: 26)
            .background(Circle().fill(CD.accent.opacity(0.14)))
            .accessibilityLabel(tr("Step {0}", n))
    }
}

/// Thin progress bar with an accent fill.
struct ProgressBar: View {
    let fraction: Double
    var height: CGFloat = 6
    var track: Color = CD.glassHigh
    var glow = false
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(CD.accent)
                    .frame(width: max(height, g.size.width * min(1, max(0, fraction))))
                    .shadow(color: glow ? CD.accent.opacity(0.6) : .clear, radius: glow ? 6 : 0)
                    .opacity(fraction > 0 ? 1 : 0)
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityLabel(tr("Progress"))
        .accessibilityValue(tr("{0} percent", Int(min(1, max(0, fraction)) * 100)))
    }
}

// MARK: - Dialogs

extension View {
    /// Confirmation dialog for destructive actions; `danger` makes the confirm button destructive.
    func confirmDialog(_ title: String, isPresented: Binding<Bool>, message: String, confirmLabel: String,
                       danger: Bool = true, onConfirm: @escaping () -> Void) -> some View {
        alert(title, isPresented: isPresented) {
            Button(confirmLabel, role: danger ? .destructive : nil, action: onConfirm)
            Button(tr("Cancel"), role: .cancel) {}
        } message: {
            Text(message)
        }
    }

    /// "Name others see" rename prompt (alert with a text field).
    func renameDialog(isPresented: Binding<Bool>, text: Binding<String>, onSave: @escaping (String) -> Void) -> some View {
        alert(tr("Name others see"), isPresented: isPresented) {
            TextField(tr("Device name"), text: text)
                .textInputAutocapitalization(.words)
            Button(tr("Save")) { onSave(text.wrappedValue) }
            Button(tr("Cancel"), role: .cancel) {}
        } message: {
            Text(tr("How other devices see this one."))
        }
    }
}
