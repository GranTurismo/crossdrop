import SwiftUI
import AVFoundation
import Photos
@preconcurrency import CrossDropCore

/// A shot taken here and where it went.
struct CameraShot: Identifiable {
    let url: URL
    let video: Bool
    let groupId: UUID?
    var id: URL { url }
}

/// Capture session for the live camera: preview, photos, and videos (with sound when allowed).
@MainActor
final class CameraModel: NSObject, ObservableObject {
    enum Access { case unknown, granted, denied }
    @Published var access: Access = .unknown
    @Published var recording = false
    @Published var recordStart = Date()
    @Published var error: String?
    @Published var front = false

    let session = AVCaptureSession()
    private let photoOut = AVCapturePhotoOutput()
    private let movieOut = AVCaptureMovieFileOutput()
    private var input: AVCaptureDeviceInput?
    private let queue = DispatchQueue(label: "crossdrop.camera")
    private var onShot: ((URL, Bool) -> Void)?

    func start(onShot: @escaping (URL, Bool) -> Void) async {
        self.onShot = onShot
        let ok = await AVCaptureDevice.requestAccess(for: .video)
        access = ok ? .granted : .denied
        guard ok else { return }
        let mic = await AVCaptureDevice.requestAccess(for: .audio)
        let session = self.session, photoOut = self.photoOut, movieOut = self.movieOut
        let front = self.front
        let configured: AVCaptureDeviceInput? = await withCheckedContinuation { cont in
            queue.async {
                session.beginConfiguration()
                session.sessionPreset = .high
                var camInput: AVCaptureDeviceInput?
                if let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
                    ?? AVCaptureDevice.default(for: .video),
                   let i = try? AVCaptureDeviceInput(device: dev), session.canAddInput(i) {
                    session.addInput(i); camInput = i
                }
                if mic, let m = AVCaptureDevice.default(for: .audio), let mi = try? AVCaptureDeviceInput(device: m), session.canAddInput(mi) {
                    session.addInput(mi)
                }
                if session.canAddOutput(photoOut) { session.addOutput(photoOut) }
                if session.canAddOutput(movieOut) { session.addOutput(movieOut) }
                session.commitConfiguration()
                session.startRunning()
                cont.resume(returning: camInput)
            }
        }
        input = configured
        if configured == nil { error = tr("No camera available") }
    }

    func stop() {
        let session = self.session
        if movieOut.isRecording { movieOut.stopRecording() }
        queue.async { session.stopRunning() }
    }

    func flip() {
        guard !recording, let current = input else { return }
        front.toggle()
        let session = self.session, front = self.front
        queue.async {
            guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back),
                  let next = try? AVCaptureDeviceInput(device: dev) else { return }
            session.beginConfiguration()
            session.removeInput(current)
            if session.canAddInput(next) { session.addInput(next) } else { session.addInput(current) }
            session.commitConfiguration()
            DispatchQueue.main.async { self.input = session.inputs.compactMap { $0 as? AVCaptureDeviceInput }.first { $0.device.hasMediaType(.video) } }
        }
    }

    private func newURL(_ ext: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("picked-camera", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd_HHmmss_SSS"; f.locale = Locale(identifier: "en_US_POSIX")
        return dir.appendingPathComponent("CrossDrop_\(f.string(from: Date())).\(ext)")
    }

    func takePhoto() {
        let settings = photoOut.availablePhotoCodecTypes.contains(.jpeg)
            ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]) : AVCapturePhotoSettings()
        photoOut.capturePhoto(with: settings, delegate: self)
    }

    func toggleRecording() {
        if movieOut.isRecording { movieOut.stopRecording(); return }
        recordStart = Date()
        recording = true
        movieOut.startRecording(to: newURL("mov"), recordingDelegate: self)
    }

    fileprivate func deliver(_ url: URL, video: Bool) {
        onShot?(url, video)
        Task.detached {
            // A copy goes to the photo library too, so a declined or failed send never loses the shot.
            guard await PhotosBridge.requestAdd() else { return }
            _ = await PhotosBridge.save(url, video: video, name: url.lastPathComponent, date: Date())
        }
    }
}

extension CameraModel: AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            Task { @MainActor in self.error = tr("Couldn't take the photo") }
            return
        }
        Task { @MainActor in
            let url = self.newURL("jpg")
            do { try data.write(to: url); self.deliver(url, video: false) } catch { self.error = tr("Couldn't save the photo") }
        }
    }

    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo url: URL, from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            self.recording = false
            let ok = error == nil || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true)
            if ok { self.deliver(url, video: true) } else { self.error = tr("Recording failed") }
        }
    }
}

private struct PreviewLayer: UIViewRepresentable {
    let session: AVCaptureSession
    final class Host: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
    func makeUIView(context: Context) -> Host {
        let v = Host()
        v.preview.session = session
        v.preview.videoGravity = .resizeAspectFill
        return v
    }
    func updateUIView(_ v: Host, context: Context) {}
}

/// Live camera: every photo or video taken here is saved to Photos and sent straight to `targets`.
/// The first shot asks each device once; after that they arrive without a prompt.
struct CameraScreen: View {
    let targets: [Peer]
    let onClose: () -> Void
    @EnvironmentObject var coordinator: TransferCoordinator
    @StateObject private var cam = CameraModel()
    @State private var videoMode = false
    @State private var shots: [CameraShot] = []
    @State private var flash = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch cam.access {
            case .denied: denied
            default:
                PreviewLayer(session: cam.session).ignoresSafeArea()
                if flash { Color.white.opacity(0.5).ignoresSafeArea().transition(.opacity) }
                controls
            }
        }
        .task {
            await cam.start { url, video in
                let gid = coordinator.sendShot(targets, url: url, mime: video ? "video/quicktime" : "image/jpeg")
                shots = Array((shots + [CameraShot(url: url, video: video, groupId: gid)]).suffix(30))
            }
        }
        .onDisappear {
            cam.stop()
            coordinator.pruneQuiet(kind: OfferKind.camera)
        }
    }

    private var denied: some View {
        VStack(spacing: 16) {
            CdIconView(CdIcons.camera, color: CD.textSecondary, size: 44)
            Text(tr("Camera access is off")).displayStyle(22)
            Text(tr("CrossDrop needs the camera to send photos the moment you take them."))
                .body(14, CD.textDim).multilineTextAlignment(.center)
            PillButton(tr("Open Settings"), fullWidth: false) { openSystemSettings() }
            PillButton(tr("Close"), style: .glass, fullWidth: false, action: onClose)
        }
        .padding(24)
    }

    private var controls: some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                CircleButton(icon: CdIcons.close, label: tr("Close camera"), fill: Color.black.opacity(0.45), action: onClose)
                HStack(spacing: 8) {
                    Dot(color: CD.accent)
                    Text(tr("Live to {0}", targets.map(\.name).joined(separator: ", "))).body(13, weight: .semibold).lineLimit(1)
                }
                .padding(.horizontal, 14).frame(height: 40).frame(maxWidth: .infinity, alignment: .leading)
                .background(Capsule().fill(Color.black.opacity(0.45)))
            }
            Spacer()
            if let e = cam.error {
                Text(e).body(13, CD.dangerText).padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Color.black.opacity(0.6)))
            }
            if cam.recording {
                TimelineView(.periodic(from: cam.recordStart, by: 0.5)) { ctx in
                    let s = Int(ctx.date.timeIntervalSince(cam.recordStart))
                    HStack(spacing: 6) {
                        Circle().fill(Color.white).frame(width: 8, height: 8)
                        Text(String(format: "%d:%02d", s / 60, s % 60)).body(13, .white, weight: .semibold)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 5).background(Capsule().fill(CD.danger.opacity(0.85)))
                }
            }
            status
            HStack {
                thumb.frame(width: 54, height: 54)
                Spacer()
                shutter
                Spacer()
                CircleButton(icon: CdIcons.swap, label: tr("Switch camera"), size: 54, fill: Color.black.opacity(0.45)) { cam.flip() }
            }
            .padding(.horizontal, 28)
            HStack(spacing: 6) {
                modeChip(tr("Photo"), !videoMode) { if !cam.recording { videoMode = false } }
                modeChip(tr("Video"), videoMode) { if !cam.recording { videoMode = true } }
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 20)
    }

    private var shutter: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if videoMode { cam.toggleRecording() }
            else {
                withAnimation(.easeOut(duration: 0.12)) { flash = true }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { withAnimation { flash = false } }
                cam.takePhoto()
            }
        } label: {
            ZStack {
                Circle().strokeBorder(Color.white, lineWidth: 4).frame(width: 78, height: 78)
                RoundedRectangle(cornerRadius: cam.recording ? 8 : 31, style: .continuous)
                    .fill(videoMode ? CD.danger : Color.white)
                    .frame(width: cam.recording ? 30 : 62, height: cam.recording ? 30 : 62)
                    .animation(.easeInOut(duration: 0.2), value: cam.recording)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(videoMode ? (cam.recording ? tr("Stop recording") : tr("Record video")) : tr("Take photo"))
    }

    private var thumb: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black.opacity(0.45))
            if let s = shots.last, !s.video, let img = UIImage(contentsOfFile: s.url.path) {
                Image(uiImage: img.preparingThumbnail(of: CGSize(width: 120, height: 120)) ?? img).resizable().aspectRatio(contentMode: .fill)
            } else if shots.last?.video == true {
                CdIconView(CdIcons.video, color: .white, size: 22)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.4), lineWidth: 1))
    }

    /// "Pixel ✓ · Waiting for Mac" for the newest shot.
    private var status: some View {
        let text: String = {
            guard let s = shots.last else { return tr("Take a photo — it lands on the other screen instantly") }
            let mine = coordinator.sessions.filter { $0.groupId == s.groupId }
            if mine.isEmpty { return tr("Saved") }
            return mine.map { x in
                switch x.status {
                case .completed: return tr("{0} ✓", x.peerName)
                case .awaitingAccept: return tr("Waiting for {0} to accept", x.peerName)
                case .transferring: return tr("{0} {1}%", x.peerName, Int(x.fraction * 100))
                case .connecting: return tr("Connecting to {0}", x.peerName)
                case .declined: return tr("{0} declined", x.peerName)
                default: return tr("{0}: not sent", x.peerName)
                }
            }.joined(separator: "  ·  ")
        }()
        return Text(text).body(13, CD.textPrimary).multilineTextAlignment(.center).lineLimit(2)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(Capsule().fill(Color.black.opacity(0.45)))
    }

    private func modeChip(_ label: String, _ selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).body(13, selected ? .black : CD.textPrimary, weight: .semibold)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(Capsule().fill(selected ? CD.accent : Color.black.opacity(0.45)))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
