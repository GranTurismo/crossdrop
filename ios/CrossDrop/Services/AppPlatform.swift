import Foundation

/// What this app runs on, as it is told to other devices (TXT `os` and the handshake): the same words as
/// Kotlin's `Platforms`.
enum AppPlatform {
    static var wireName: String {
        #if targetEnvironment(macCatalyst)
        return "mac"
        #else
        return "ios"
        #endif
    }
}
