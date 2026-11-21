import Foundation

/// Why a transfer stopped, in terms a person can act on. Twin of Kotlin's `FailureKind`
/// (`core/transfer/FailureAdvice.kt`): the engine decides the kind; the app words it (`tr`).
public enum FailureKind: String {
    /// Nothing answered: the other device isn't running CrossDrop, is asleep, or is on another network.
    case unreachable
    /// It answered, then went away mid-way.
    case connectionLost
    /// The bytes that arrived did not match (BLAKE3/SHA-256 check).
    case integrity
    /// This device's storage is full.
    case diskFull
    /// The file to send could not be read (moved, deleted, permission withdrawn).
    case sourceUnreadable
    /// The other side did not speak our protocol (an older build, or not CrossDrop at all).
    case incompatible
    /// Anything else: the raw message is all there is.
    case other

    /// Classify what the engine threw. [connected]: the secure channel was up when it failed (a timeout before
    /// that means nothing was listening, after that means the other side vanished).
    public static func classify(_ error: Error, connected: Bool, sending: Bool) -> FailureKind {
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain {
            if ns.code == NSFileWriteOutOfSpaceError { return .diskFull }
            if sending, [NSFileNoSuchFileError, NSFileReadNoSuchFileError, NSFileReadNoPermissionError].contains(ns.code) { return .sourceUnreadable }
        }
        if ns.domain == NSPOSIXErrorDomain, ns.code == Int(ENOSPC) || ns.code == Int(EDQUOT) { return .diskFull }
        if error is EOFError { return connected ? .connectionLost : .unreachable }
        let m = ((error as? ProtocolError)?.message ?? error.localizedDescription).lowercased()
        // Messages can embed a file name chosen by the other device ("<name> changed mid-transfer"), so what counts is how
        // they end (our fixed wording, the system's wording), never words found anywhere inside.
        if m.hasSuffix("no space left on device") { return .diskFull }
        if m == "integrity check failed" || m.hasSuffix("failed integrity check") || m.hasSuffix("failed hash check") { return .integrity }
        // The file being sent changed (or came up short) while it was read: the sender's file, not the connection.
        if m.hasSuffix("changed mid-transfer") || m == "source truncated mid-transfer" { return sending ? .sourceUnreadable : .other }
        if m.hasPrefix("connect to") || m.hasPrefix("cannot resolve") { return .unreachable }
        // Only a handshake that goes wrong means "the other side doesn't speak our protocol"; the same error type is
        // also how a corrupt frame is reported later on.
        if !connected, m.hasPrefix("expected ") || m.hasSuffix("unsupported") { return .incompatible }
        if m == "timed out waiting for peer" || m == "timed out sending to peer" || m == "connection closed" || m.hasPrefix("read failed") || m.hasPrefix("write failed")
            || m == "stream closed" {
            return connected ? .connectionLost : .unreachable
        }
        return .other
    }
}
