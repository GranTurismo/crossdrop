package com.crossdrop.core.connect

/**
 * Where the system installer's confirmation screen would open, as plain values (what Android told
 * `AppInbox`, so the rule is testable without Android).
 *
 * PackageInstaller answers an install with a broadcast whose `EXTRA_INTENT` is the screen to show; launching an
 * Intent taken out of an extra as it comes is what Google Play calls Intent Redirection (it must never reach
 * one of our own private components, nor grant access to our files). The rule below is Google's "Option 3":
 * check where the nested Intent goes before redirecting it.
 */
data class ConfirmTarget(
    /** The package the Intent names itself (its component's, else its `package`); null for an implicit one. */
    val namedPackage: String?,
    /** The package the system says would handle it (`resolveActivity`); null when it can't tell. */
    val resolvedPackage: String?,
    /** Whether that resolved app is part of the system image; null when nothing resolved. */
    val resolvedIsSystem: Boolean?,
)

object InstallConfirm {
    /**
     * May [target] be launched? Yes only when it is aimed at another app: never ours, whether the Intent says
     * so or the system resolves it so, and an installer that resolves must be a system app (every installer,
     * Google's and the makers', is). An Intent that names nothing and resolves to nothing is refused.
     * An installer the system won't resolve for us (app visibility) is still allowed when it names its package.
     */
    fun allow(ownPackage: String, target: ConfirmTarget): Boolean {
        val (named, resolved, isSystem) = target
        if (named == null && resolved == null) return false
        if (named == ownPackage || resolved == ownPackage) return false
        if (resolved != null && isSystem != true) return false
        return true
    }
}
