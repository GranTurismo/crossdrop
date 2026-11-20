import Foundation
@preconcurrency import CrossDropCore
#if !targetEnvironment(macCatalyst)
import CoreMotion
#endif

/// Shake to connect, the accelerometer side (the twin of Android `ShakeSensor`): read only while `start`ed — the app is
/// in front, the radar is on screen with nothing over it and the setting is on — so it costs nothing otherwise. Every
/// sample goes through `ShakeGesture`, and `onShake` runs on the main thread for each shake.
///
/// Not on Mac: nobody shakes a computer. A Mac still answers other devices' SHAKE (the engine's control handler).
@MainActor
final class ShakeSensor {
    /// iPhone and iPad can be shaken; the Mac app can't.
    static var supported: Bool {
        #if targetEnvironment(macCatalyst)
        return false
        #else
        return true
        #endif
    }

    var onShake: (() -> Void)?
    private(set) var running = false

    #if !targetEnvironment(macCatalyst)
    private let motion = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "crossdrop.shake"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .userInitiated
        return q
    }()
    #endif

    func start() {
        #if !targetEnvironment(macCatalyst)
        guard !running, motion.isAccelerometerAvailable else { return }
        running = true
        motion.accelerometerUpdateInterval = 1.0 / 50
        // A fresh gesture each time: half a burst from before the radar was covered doesn't count. It lives on the
        // sensor's serial queue only.
        let gesture = ShakeGesture()
        motion.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
            guard let data else { return }
            // Raw accelerometer: g *with* gravity, like Android's TYPE_ACCELEROMETER (there in m/s²), so the same
            // threshold applies. (userAcceleration would have gravity taken out.)
            let a = data.acceleration, g = 9.81
            if gesture.sample(timeNs: Int64(data.timestamp * 1_000_000_000), x: a.x * g, y: a.y * g, z: a.z * g) {
                DispatchQueue.main.async { self?.onShake?() }
            }
        }
        #endif
    }

    func stop() {
        #if !targetEnvironment(macCatalyst)
        guard running else { return }
        motion.stopAccelerometerUpdates()
        running = false
        #endif
    }
}
