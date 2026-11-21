package com.crossdrop.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.crossdrop.MainActivity
import com.crossdrop.R
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.net.OutboxResult

/**
 * "Send when it's back" finished while the person may be elsewhere: a quiet notification says what went where
 * (or that it could not), because nothing else on screen would.
 */
object OutboxNotifier {
    private const val CHANNEL = "outbox"
    private const val ID = 44

    fun show(context: Context, result: OutboxResult) {
        // The permission only exists from Android 13; before that notifications are simply allowed (asking would always say "denied").
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Sent when it was back"), NotificationManager.IMPORTANCE_DEFAULT))
        val n = result.entry.items.size
        val title = if (result.delivered) tr("Sent to {0}", result.entry.deviceName) else tr("Couldn't send to {0}", result.entry.deviceName)
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(result.entry.items.firstOrNull()?.label?.takeIf { n == 1 } ?: trPlural(n, "1 item", "{n} items"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }
}
