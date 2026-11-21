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
        // The permission only exists from Android 13; before that notifications are simply allowed (asking would always say "denied").
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Incoming offers"), NotificationManager.IMPORTANCE_HIGH))
        val n = offer.offer.files.size
        val text = offer.text
        val (title, body) = when {
            text != null -> (if (isLinkText(text)) tr("{0} sent you a link", offer.peerName) else tr("{0} sent you some text", offer.peerName)) to text.take(120)
            offer.kind == OfferKind.CONTACT -> {
                val cards = offer.contacts
                val count = cards.size.coerceAtLeast(1)
                trPlural(count, "{0} sent you a contact", "{0} sent you {n} contacts", offer.peerName) to
                    (cards.takeIf { it.isNotEmpty() }?.let { com.crossdrop.core.contacts.ContactShare.title(it) } ?: tr("Open CrossDrop to add it"))
            }
            offer.kind == OfferKind.CAMERA -> tr("{0} wants to share their camera", offer.peerName) to tr("Open CrossDrop to watch their photos arrive live")
            offer.kind == OfferKind.SWITCH -> tr("{0} wants to move to this phone", offer.peerName) to tr("Open CrossDrop to start")
            else -> trPlural(n, "{0} wants to send you a file", "{0} wants to send you {n} files", offer.peerName) to
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
