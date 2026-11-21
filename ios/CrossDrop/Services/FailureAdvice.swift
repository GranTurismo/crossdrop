import Foundation
@preconcurrency import CrossDropCore

/// Turns what the engine concluded (`FailureKind`) into plain words: what happened and what to try. Twin of
/// Kotlin's `FailureAdvice` (`core/transfer/FailureAdvice.kt`): keep the texts and the rules in step.
enum FailureAdvice {
    struct Advice { var title: String; var hint: String? }

    /// `raw` is kept as the title for `.other`, so nothing the engine says is ever hidden.
    static func explain(_ kind: FailureKind, raw: String, peerName: String, peerOS: String?) -> Advice {
        switch kind {
        case .unreachable:
            return Advice(title: tr("Couldn't reach {0}", peerName),
                          hint: tr("Open CrossDrop on that device and make sure you're both on the same Wi-Fi."))
        case .connectionLost:
            return Advice(title: tr("Lost the connection to {0}", peerName),
                          hint: peerOS == "ios" ? iosNote(peerName)
                          : tr("The other device may have gone to sleep, left Wi-Fi or closed CrossDrop. Keep CrossDrop open on both, then try again: what already arrived is kept."))
        case .integrity:
            return Advice(title: tr("The file arrived damaged"),
                          hint: tr("Nothing was saved. Try again, ideally with both devices close to the router."))
        case .diskFull:
            return Advice(title: tr("Not enough space on this device"),
                          hint: tr("Free up some space, then try again. What already arrived is kept."))
        case .sourceUnreadable:
            return Advice(title: tr("Couldn't read the file"), hint: tr("It may have been moved or deleted. Pick it again and retry."))
        case .incompatible:
            return Advice(title: tr("Couldn't talk to {0}", peerName), hint: tr("Update CrossDrop on both devices and try again."))
        case .other:
            return Advice(title: raw.isEmpty ? tr("transfer failed") : raw, hint: nil)
        }
    }

    /// Said before and after a send to an iPhone or iPad: they only receive while CrossDrop is on screen.
    static func iosNote(_ peerName: String) -> String {
        tr("{0} is an iPhone or iPad: it only receives while CrossDrop is open and the screen is on.", peerName)
    }

    /// The hint under a declined send: the receiver tapped No, or the offer timed out unanswered.
    static func declinedHint() -> String {
        tr("They said no, or didn't answer in time. Ask them to look at their screen, then try again.")
    }
}

extension SessionState {
    /// The line under a session's status: what to try after a failure or a decline, or, while sending to an iPhone
    /// or iPad, the heads-up that it only receives with CrossDrop open and its screen on.
    var note: String? {
        if let hint { return hint }
        if direction == .sending, !status.isTerminal, peerOS == "ios" { return FailureAdvice.iosNote(peerName) }
        return nil
    }
}
