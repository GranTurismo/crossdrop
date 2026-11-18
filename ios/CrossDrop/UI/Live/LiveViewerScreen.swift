import SwiftUI
import AVKit
import QuickLook
@preconcurrency import CrossDropCore

/// Someone else's camera, live: the newest shot fills the screen the moment it lands; older ones sit
/// in a strip underneath. Shots are in the inbox too.
struct LiveViewerScreen: View {
    let feed: LiveFeed
    let onStop: () -> Void
    let onHide: () -> Void
    @State private var pinned: LiveShot?
    @State private var preview: URL?

    var body: some View {
        let shown = pinned ?? feed.shots.last
        ZStack {
            CD.ink.ignoresSafeArea()
            ScreenGlow(alpha: 0.12, centerY: 120, radius: 260)
            VStack(spacing: 14) {
                HStack(spacing: 10) {
                    HStack(spacing: 7) {
                        Dot(color: CD.accent)
                        Text(tr("Live").caps).font(.cd(12, weight: .bold)).foregroundColor(CD.accent)
                    }
                    .padding(.horizontal, 12).frame(height: 32).background(Capsule().fill(CD.accent.opacity(0.14)))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(feed.peerName).displayStyle(17).lineLimit(1)
                        Text(feed.shots.isEmpty ? tr("Waiting for the first shot…") : trPlural(feed.shots.count, one: "1 shot", other: "{n} shots"))
                            .body(12, CD.textDim)
                    }
                    Spacer(minLength: 4)
                    ChipButton(text: tr("Hide"), bg: CD.glassMid, border: CD.hairlineStrong, height: 38, action: onHide)
                    ChipButton(text: tr("Stop"), icon: CdIcons.close, bg: CD.glassMid, border: CD.hairlineStrong, height: 38, action: onStop)
                }
                ZStack(alignment: .top) {
                    RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Color.black)
                    if let shot = shown {
                        ShotView(shot: shot, fill: false)
                            .id(shot.id)
                            .transition(.opacity)
                            .onTapGesture { preview = shot.url }
                    } else {
                        VStack(spacing: 10) {
                            CdIconView(CdIcons.camera, color: CD.textSecondary, size: 40)
                            Text(tr("Shots from {0} appear here\nthe moment they're taken.", feed.peerName))
                                .body(14, CD.textDim).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    if let s = feed.incoming {
                        HStack(spacing: 8) {
                            Dot(color: CD.accent, size: 6)
                            Text(tr("Arriving… {0}%", Int(s.fraction * 100))).body(12, CD.textPrimary)
                        }
                        .padding(.horizontal, 12).frame(height: 32)
                        .background(Capsule().fill(Color.black.opacity(0.6)))
                        .padding(12)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(CD.hairlineStrong, lineWidth: 1))
                .animation(.easeInOut(duration: 0.25), value: shown?.id)

                if feed.shots.count > 1 {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(feed.shots) { shot in
                                    ShotView(shot: shot, fill: true)
                                        .frame(width: 64, height: 64)
                                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                            .strokeBorder(shot == shown ? CD.accent : CD.hairlineStrong, lineWidth: shot == shown ? 2 : 1))
                                        .id(shot.id)
                                        .onTapGesture { pinned = shot }
                                }
                            }
                        }
                        .onChange(of: feed.shots.count) { _ in
                            pinned = nil
                            if let last = feed.shots.last { withAnimation { proxy.scrollTo(last.id, anchor: .trailing) } }
                        }
                    }
                }
                Text(tr("Saved to your inbox as they arrive. Tap a shot to open it.")).body(12, CD.textDim)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(16)
        }
        .quickLookPreview($preview)
    }
}

private struct ShotView: View {
    let shot: LiveShot
    let fill: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: fill ? .fill : .fit)
            } else {
                Color.white.opacity(0.06)
            }
            if shot.isVideo {
                CdIconView(CdIcons.play, color: CD.textPrimary, size: fill ? 12 : 28)
                    .frame(width: fill ? 26 : 64, height: fill ? 26 : 64)
                    .background(Circle().fill(Color.black.opacity(0.55)))
            }
        }
        .task(id: shot.url) {
            let url = shot.url, video = shot.isVideo, maxPx: CGFloat = fill ? 200 : 2048
            image = await Task.detached { () -> UIImage? in
                if video {
                    let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
                    gen.appliesPreferredTrackTransform = true
                    gen.maximumSize = CGSize(width: maxPx, height: maxPx)
                    return (try? gen.copyCGImage(at: .zero, actualTime: nil)).map { UIImage(cgImage: $0) }
                }
                guard let img = UIImage(contentsOfFile: url.path) else { return nil }
                return img.preparingThumbnail(of: fitSize(img.size, maxPx)) ?? img
            }.value
        }
    }
}

private func fitSize(_ s: CGSize, _ maxPx: CGFloat) -> CGSize {
    let scale = min(1, maxPx / max(s.width, s.height, 1))
    return CGSize(width: s.width * scale, height: s.height * scale)
}
