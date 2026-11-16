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
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.net.IncomingOffer
import com.crossdrop.ui.isLinkText

/**
 * An offer that arrives while CrossDrop isn't on screen: a heads-up notification that opens the app,
 * where the offer is waiting to be accepted (it still auto-declines after the usual timeout).
 */
object OfferNotifier {
    private const val CHANNEL = "offers"
    private const val ID = 43

    fun show(context: Context, offer: IncomingOffer) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Incoming offers", NotificationManager.IMPORTANCE_HIGH))
        val n = offer.offer.files.size
        val text = offer.text
        val (title, body) = when {
            text != null -> "${offer.peerName} sent you " + (if (isLinkText(text)) "a link" else "some text") to text.take(120)
            offer.kind == OfferKind.CAMERA -> "${offer.peerName} wants to share their camera" to "Open CrossDrop to watch their photos arrive live"
            offer.kind == OfferKind.SWITCH -> "${offer.peerName} wants to move to this phone" to "Open CrossDrop to start"
            else -> "${offer.peerName} wants to send you " + (if (n == 1) "a file" else "$n files") to
                (offer.offer.files.firstOrNull()?.name ?: "")
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(com.crossdrop.net.TransferHub.OFFER_TIMEOUT_MS)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}
