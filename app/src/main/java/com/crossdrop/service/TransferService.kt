package com.crossdrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.crossdrop.CrossDropApp
import com.crossdrop.MainActivity
import com.crossdrop.R
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.isTerminal
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service bound to active transfer sessions — keeps the socket alive
 * if the user backgrounds the app, and surfaces progress in the shade. Zero
 * cost while idle: it only runs while a session exists.
 */
class TransferService : LifecycleService() {

    private var observeJob: Job? = null

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ))
        // Observing starts in onStartCommand, after startForeground: stopping before that
        // point crashes the app (ForegroundServiceDidNotStartInTimeException).
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = lifecycleScope.launch {
            val container = (application as CrossDropApp).container
            combine(
                container.coordinator.sessions,
                container.qrShare.state,
                container.vaultHost.state,
                container.switchSender.state,
                container.coordinator.live,
            ) { s, q, v, sw, live -> Snapshot(s, q, v, sw, live) }
                .collectLatest { snap ->
                    val active = snap.sessions.filter { !it.status.isTerminal }
                    when {
                        snap.switchOut != null && !snap.switchOut.finished -> updateSwitchNotification(snap.switchOut)
                        active.isNotEmpty() -> updateNotification(active)
                        snap.live != null -> updateLiveNotification(snap.live)
                        snap.share != null -> updateShareNotification(snap.share)
                        snap.vault != null -> updateVaultNotification(snap.vault)
                        // A just-accepted receive registers a moment after the service starts:
                        // wait briefly (collectLatest cancels this when anything changes).
                        else -> { kotlinx.coroutines.delay(1_500); stopSelf() }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWith(initialNotification())
        observe()
        return START_STICKY
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun initialNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr("CrossDrop transfer"))
        .setContentText(tr("Starting…"))
        .setOngoing(true)
        .setContentIntent(contentIntent())
        .build()

    private class Snapshot(
        val sessions: List<com.crossdrop.net.SessionState>,
        val share: com.crossdrop.net.QrShareManager.State?,
        val vault: com.crossdrop.net.VaultHostManager.State?,
        val switchOut: com.crossdrop.switchphones.SwitchSender.State?,
        val live: com.crossdrop.net.LiveFeed?,
    )

    /** Several sessions at once read as one: "Sending to 3 devices · 42%". */
    private fun updateNotification(sessions: List<com.crossdrop.net.SessionState>) {
        if (sessions.size > 1) {
            val total = sessions.sumOf { it.bytesTotal.coerceAtLeast(1) }
            val done = sessions.sumOf { it.bytesDone }
            val pct = (done * 100 / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            val sending = sessions.count { it.direction == Direction.SENDING }
            startForegroundWith(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(
                        if (sending == sessions.size) {
                            val devices = sessions.map { it.peerName }.distinct().size
                            trPlural(devices, "Sending to 1 device", "Sending to {n} devices")
                        } else trPlural(sessions.size, "1 transfer", "{n} transfers")
                    )
                    .setContentText("$pct% · ${formatRate(sessions.sumOf { it.bytesPerSec })}")
                    .setProgress(100, pct, false)
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent())
                    .build(),
            )
            return
        }
        val session = sessions.first()
        val title = if (session.direction == Direction.SENDING)
            tr("Sending to {0}", session.peerName) else tr("Receiving from {0}", session.peerName)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)

        when (session.status) {
            SessionStatus.TRANSFERRING -> {
                val pct = (session.fraction * 100).toInt()
                builder.setContentText("$pct% · ${formatRate(session.bytesPerSec)}")
                    .setProgress(100, pct, false)
                    .setOngoing(true)
            }
            SessionStatus.COMPLETED -> builder.setContentText(tr("Complete")).setOngoing(false)
            SessionStatus.FAILED -> builder.setContentText(tr("Failed: {0}", session.error ?: tr("unknown"))).setOngoing(false)
            SessionStatus.DECLINED -> builder.setContentText(tr("Declined")).setOngoing(false)
            SessionStatus.CANCELLED -> builder.setContentText(tr("Cancelled")).setOngoing(false)
            else -> builder.setContentText(tr("Connecting…")).setOngoing(true)
        }
        startForegroundWith(builder.build())
    }

    private fun updateSwitchNotification(s: com.crossdrop.switchphones.SwitchSender.State) {
        val pct = (s.fraction * 100).toInt()
        startForegroundWith(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr("Moving to {0}", s.peerName))
                .setContentText(tr("{0} of {1} items · {2}%", "%,d".format(s.itemsDone), "%,d".format(s.itemsTotal), pct))
                .setProgress(100, pct, s.bytesTotal == 0L)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(contentIntent())
                .build(),
        )
    }

    private fun updateLiveNotification(feed: com.crossdrop.net.LiveFeed) {
        startForegroundWith(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr("Live camera from {0}", feed.peerName))
                .setContentText(if (feed.shots.isEmpty()) tr("Waiting for the first shot") else trPlural(feed.shots.size, "1 shot received", "{n} shots received"))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(contentIntent())
                .build(),
        )
    }

    /** "Send to anyone" share — keeps the HTTP server reachable while backgrounded. */
    private fun updateShareNotification(share: com.crossdrop.net.QrShareManager.State) {
        val text = when {
            share.delivered -> tr("All files delivered")
            share.downloadsStarted > 0 ->
                trPlural(share.downloadsStarted, "{0}/1 download · {1}", "{0}/{n} downloads · {1}",
                    share.downloadsDone, formatBytes(share.bytesServed))
            share.connected -> tr("Receiver connected")
            else -> trPlural(share.files.size, "1 file · waiting for a scan", "{n} files · waiting for a scan")
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Sharing via browser link"))
            .setContentText(text)
            .setOngoing(!share.delivered)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
        startForegroundWith(notification)
    }

    /** Hosted LAN vault — keeps the listener + NSD advert alive in background. */
    private fun updateVaultNotification(vault: com.crossdrop.net.VaultHostManager.State) {
        val clients = vault.clients.size
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Vault live — {0}", vault.vaultName))
            .setContentText(
                listOf(
                    if (vault.writable) tr("read-write") else tr("read-only"),
                    trPlural(clients, "1 client", "{n} clients"),
                    "↓${formatBytes(vault.bytesOut)}",
                ).joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
        startForegroundWith(notification)
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(com.crossdrop.core.i18n.I18n.locale(), b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    private fun formatRate(bps: Long): String = when {
        bps >= 1_000_000_000 -> "%.1f GB/s".format(com.crossdrop.core.i18n.I18n.locale(), bps / 1e9)
        bps >= 1_000_000 -> "%.1f MB/s".format(com.crossdrop.core.i18n.I18n.locale(), bps / 1e6)
        bps >= 1_000 -> "%.0f KB/s".format(com.crossdrop.core.i18n.I18n.locale(), bps / 1e3)
        else -> "$bps B/s"
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
