package com.crossdrop.core

import com.crossdrop.core.connect.ConfirmTarget
import com.crossdrop.core.connect.InstallConfirm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Intent Redirection rule for the installer's confirmation screen (Play policy warning, version code 15). */
class InstallConfirmTest {
    private val ours = "com.crossdrop"
    private fun ok(t: ConfirmTarget) = InstallConfirm.allow(ours, t)

    @Test fun `the system installer is allowed`() {
        assertTrue(ok(ConfirmTarget("com.google.android.packageinstaller", "com.google.android.packageinstaller", true)))
        assertTrue(ok(ConfirmTarget("com.miui.packageinstaller", "com.miui.packageinstaller", true)))
    }

    @Test fun `an installer the system won't resolve for us is allowed when it names its package`() {
        assertTrue(ok(ConfirmTarget("com.android.packageinstaller", null, null)))
    }

    @Test fun `an implicit intent the system resolves to a system app is allowed`() {
        assertTrue(ok(ConfirmTarget(null, "com.android.packageinstaller", true)))
    }

    @Test fun `our own components are never launched, however the intent says so`() {
        assertFalse(ok(ConfirmTarget(ours, ours, false)))
        assertFalse(ok(ConfirmTarget(ours, null, null)))
        assertFalse(ok(ConfirmTarget(null, ours, true)))
        // Names the installer but the system would hand it to us.
        assertFalse(ok(ConfirmTarget("com.android.packageinstaller", ours, false)))
    }

    @Test fun `an app that resolves but is not part of the system is refused`() {
        assertFalse(ok(ConfirmTarget("com.evil.app", "com.evil.app", false)))
        assertFalse(ok(ConfirmTarget(null, "com.evil.app", false)))
    }

    @Test fun `an intent that names nothing and resolves to nothing is refused`() {
        assertFalse(ok(ConfirmTarget(null, null, null)))
    }
}
