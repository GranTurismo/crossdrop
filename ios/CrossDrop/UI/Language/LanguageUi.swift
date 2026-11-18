import SwiftUI

/// Globe button that opens the language list: icon only, so it is recognisable in any language.
struct LanguageButton: View {
    let action: () -> Void
    var body: some View {
        CircleButton(icon: CdIcons.globe, label: tr("Change language"), size: 40, iconSize: 20, action: action)
    }
}

/// The Settings row: "Language" with the current language in its own script.
struct LanguageRow: View {
    let action: () -> Void
    private var current: String {
        I18n.choice == nil ? tr("Automatic") + " · " + I18n.nativeName(I18n.language) : I18n.nativeName(I18n.language)
    }
    var body: some View {
        GlassCard(radius: 22, padding: .hv(14, 12), action: action, actionLabel: tr("Change language")) {
            HStack(spacing: 12) {
                IconTile(icon: CdIcons.globe, tint: CD.sky, size: 40, radius: 13, iconSize: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Language")).body(15, CD.textPrimary, weight: .semibold)
                    Text(current).body(13, CD.textDim)
                }
                Spacer(minLength: 0)
                CdIconView(CdIcons.chevronRight, color: CD.textDim, size: 18)
            }
        }
    }
}

/// The language list: "Automatic" (follow the phone) and every shipped language in its own script. Picking one
/// applies at once and closes the list.
struct LanguageSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let chosen = I18n.choice
        ZStack {
            CD.inkSheet.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(tr("Language")).displayStyle(22).accessibilityAddTraits(.isHeader)
                        Spacer()
                        ChipButton(text: tr("Cancel")) { dismiss() }
                    }
                    .padding(.bottom, 10)
                    row(tr("Automatic"), subtitle: tr("Follows your phone's language"), selected: chosen == nil) { pick(nil) }
                    ForEach(I18n.nativeNames, id: \.code) { item in
                        row(item.name, subtitle: nil, selected: chosen == item.code) { pick(item.code) }
                    }
                }
                .frame(maxWidth: 560)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func pick(_ code: String?) {
        I18n.setChoice(code)
        dismiss()
    }

    private func row(_ title: String, subtitle: String?, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).body(16, selected ? CD.accent : CD.textPrimary, weight: selected ? .semibold : .regular)
                    if let subtitle { Text(subtitle).body(12, CD.textDim) }
                }
                Spacer(minLength: 0)
                if selected { CdIconView(CdIcons.check, color: CD.accent, size: 18) }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(selected ? CD.accent.opacity(0.12) : Color.clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
