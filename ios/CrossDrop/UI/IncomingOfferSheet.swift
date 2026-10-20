import SwiftUI
@preconcurrency import CrossDropCore

/// Modal sheet for an inbound OFFER — the human approves or declines.
struct IncomingOfferSheet: View {
    let offer: IncomingOffer
    let onRespond: (Bool) -> Void

    var body: some View {
        let files = offer.offer.files
        let total = files.reduce(Int64(0)) { $0 + $1.size }
        ZStack {
            Aurora.deepSpaceSoft.ignoresSafeArea()
            VStack(spacing: 10) {
                ZStack {
                    Circle().fill(Aurora.gradient).frame(width: 58, height: 58)
                    Circle().fill(Aurora.deepSpaceSoft).frame(width: 52, height: 52)
                    Text(String(offer.peerName.first.map { String($0).uppercased() } ?? "?"))
                        .font(.cd(22, weight: .bold)).foregroundColor(Aurora.phosphor)
                }
                .padding(.top, 24)
                Text("\(offer.peerName) wants to share")
                    .font(.cd(18, weight: .semibold)).foregroundColor(Aurora.textPrimary)
                Text("encrypted channel · \(files.count) file\(files.count == 1 ? "" : "s") · \(Fmt.bytes(total))")
                    .font(.cd(12)).foregroundColor(Aurora.textDim)

                GlassCard(radius: 18) {
                    VStack(spacing: 8) {
                        ForEach(Array(files.prefix(4).enumerated()), id: \.offset) { _, f in
                            HStack(spacing: 10) {
                                FileTypeTile(fileName: f.name, size: 36)
                                Text(f.name).font(.cd(14)).foregroundColor(Aurora.textPrimary).lineLimit(1)
                                Spacer(minLength: 8)
                                Text(Fmt.bytes(f.size)).font(.cd(13)).foregroundColor(Aurora.textDim)
                            }
                        }
                        if files.count > 4 {
                            Text("+ \(files.count - 4) more").font(.cd(12)).foregroundColor(Aurora.textDim)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 46)
                        }
                    }
                    .padding(12)
                }
                .padding(.top, 6)

                HStack(spacing: 12) {
                    Button("Decline") { onRespond(false) }.buttonStyle(OutlineButtonStyle())
                    Button { onRespond(true) } label: {
                        HStack(spacing: 6) { Image(systemName: "checkmark").font(.cd(14, weight: .bold)); Text("Accept") }
                    }.buttonStyle(PrimaryButtonStyle())
                }
                .padding(.top, 12)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .readableWidth(520)
        }
    }
}
