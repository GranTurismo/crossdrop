package com.crossdrop.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.crossdrop.MainActivity
import com.crossdrop.R

/**
 * Devices the user sends to become Direct Share targets, so "Share → <their laptop>" is one tap.
 * Devices rotate their network identity on every launch, so a shortcut can only remember the
 * device's name; [ShareActivity] looks that name up on the radar when it is picked.
 */
object DirectShare {
    const val CATEGORY = "com.crossdrop.SHARE_TARGET"
    private const val PREFIX = "device:"

    fun shortcutId(deviceName: String) = PREFIX + deviceName

    fun deviceNameFrom(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun remember(context: Context, deviceName: String) {
        if (deviceName.isBlank() || deviceName == "self") return
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(deviceName))
                .setShortLabel(deviceName.take(24))
                .setLongLabel("Send to $deviceName")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                .setCategories(setOf(CATEGORY))
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }
}
