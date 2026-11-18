import SwiftUI
import CoreImage.CIFilterBuiltins

/// QR on a white card with an accent halo. Scanners need a light quiet zone, so this is the one bright
/// surface in the dark UI. `size` is the outer card.
struct QrCard: View {
    let text: String
    var size: CGFloat = 236
    var halo = true

    var body: some View {
        let big = size >= 160
        let shape = RoundedRectangle(cornerRadius: big ? 32 : 18, style: .continuous)
        ZStack {
            if let img = Self.image(for: text) {
                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                    .padding(big ? 20 : 10)
            }
        }
        .frame(width: size, height: size)
        .background(
            ZStack {
                if halo {
                    shape.fill(Color.white).shadow(color: CD.accent.opacity(0.25), radius: 35, x: 0, y: 20)
                    shape.inset(by: -8).fill(CD.accent.opacity(0.2))
                }
                shape.fill(Color.white)
            }
        )
        .accessibilityElement()
        .accessibilityLabel(tr("QR code for {0}", text))
        .accessibilityAddTraits(.isImage)
    }

    private static var cache: [String: UIImage] = [:]

    /// Ink modules on white, rendered at an integer module scale so edges stay crisp. Core Image's
    /// generator adds its own quiet zone, which is cropped here — the card's padding is the quiet zone.
    static func image(for text: String) -> UIImage? {
        if let hit = cache[text] { return hit }
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let raw = f.outputImage else { return nil }
        let tint = CIFilter.falseColor()
        tint.inputImage = raw
        tint.color0 = CIColor(red: 6 / 255, green: 8 / 255, blue: 12 / 255)
        tint.color1 = CIColor(red: 1, green: 1, blue: 1)
        guard let colored = tint.outputImage else { return nil }
        // CIQRCodeGenerator pads the symbol with a 1-module margin — trim it.
        let trimmed = colored.cropped(to: raw.extent.insetBy(dx: 1, dy: 1))
            .transformed(by: CGAffineTransform(translationX: -1, y: -1))
            .transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cg = CIContext().createCGImage(trimmed, from: trimmed.extent) else { return nil }
        let img = UIImage(cgImage: cg)
        cache[text] = img
        return img
    }
}
