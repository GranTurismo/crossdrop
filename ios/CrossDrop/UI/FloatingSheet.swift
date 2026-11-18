import SwiftUI

/// The design's floating sheet (rounded on every corner, inset 8 pt from the screen edges) shown above
/// the whole shell — nav included — but below an incoming offer. Screens hand it content through the
/// environment instead of `.sheet`, which can't float, and can't stack under the offer layer.
@MainActor
final class SheetHost: ObservableObject {
    struct Item: Identifiable {
        let id = UUID()
        let content: AnyView
        let onDismiss: (() -> Void)?
    }

    @Published private(set) var item: Item?

    func present<V: View>(onDismiss: (() -> Void)? = nil, @ViewBuilder _ content: () -> V) {
        item = Item(content: AnyView(content()), onDismiss: onDismiss)
    }

    /// Closes the sheet; `notify` runs its dismiss handler (a cancel), off for "an option was picked".
    func dismiss(notify: Bool = true) {
        let old = item
        item = nil
        if notify { old?.onDismiss?() }
    }
}

struct FloatingSheetLayer: View {
    @ObservedObject var host: SheetHost
    @State private var drag: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            if let item = host.item {
                Color(hex: 0x040609, alpha: 0.62)
                    .ignoresSafeArea()
                    .onTapGesture { host.dismiss() }
                    .accessibilityLabel(tr("Close"))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { host.dismiss() }
                    .transition(.opacity)

                card(item)
                    .id(item.id)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.38, dampingFraction: 0.88), value: host.item?.id)
    }

    private func card(_ item: SheetHost.Item) -> some View {
        let shape = RoundedRectangle(cornerRadius: 36, style: .continuous)
        return VStack(spacing: 0) {
            Capsule().fill(Color.white.opacity(0.18)).frame(width: 40, height: 5)
                .padding(.top, 10).padding(.bottom, 16)
                .accessibilityHidden(true)
            ViewThatFits(in: .vertical) {
                item.content.padding(.horizontal, 18).padding(.bottom, 22)
                ScrollView { item.content.padding(.horizontal, 18).padding(.bottom, 22) }
            }
        }
        .frame(maxWidth: 520)
        .background(ZStack { shape.fill(.ultraThinMaterial); shape.fill(CD.inkSheet.opacity(0.92)) })
        .overlay(shape.strokeBorder(Color.white.opacity(0.09), lineWidth: 1))
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
        .offset(y: max(0, drag))
        .gesture(
            DragGesture()
                .onChanged { drag = $0.translation.height }
                .onEnded { v in
                    if v.translation.height > 120 || v.predictedEndTranslation.height > 260 { host.dismiss() }
                    withAnimation(.spring()) { drag = 0 }
                }
        )
        .accessibilityAction(.escape) { host.dismiss() }
        .accessibilityAddTraits(.isModal)
        .background(
            // Esc (hardware keyboard / Mac) closes the sheet like a tap on the scrim.
            Button(tr("Close")) { host.dismiss() }.keyboardShortcut(.cancelAction)
                .opacity(0).frame(width: 0, height: 0).accessibilityHidden(true)
        )
    }
}
