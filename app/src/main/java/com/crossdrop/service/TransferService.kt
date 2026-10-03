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
import com.crossdrop.net.Direction
import com.crossdrop.net.SessionStatus
import com.crossdrop.net.isTerminal
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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
        observe()
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = lifecycleScope.launch {
            val coordinator = (application as CrossDropApp).container.coordinator
            coordinator.session.collectLatest { session ->
                when {
                    session == null -> stopSelf()
                    else -> updateNotification(session)
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
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("CrossDrop transfer")
        .setContentText("Starting…")
        .setOngoing(true)
        .setContentIntent(contentIntent())
        .build()

    private fun updateNotification(session: com.crossdrop.net.SessionState) {
        val title = if (session.direction == Direction.SENDING)
            "Sending to ${session.peerName}" else "Receiving from ${session.peerName}"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
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
            SessionStatus.COMPLETED -> builder.setContentText("Complete").setOngoing(false)
            SessionStatus.FAILED -> builder.setContentText("Failed: ${session.error ?: "unknown"}").setOngoing(false)
            SessionStatus.DECLINED -> builder.setContentText("Declined").setOngoing(false)
            SessionStatus.CANCELLED -> builder.setContentText("Cancelled").setOngoing(false)
            else -> builder.setContentText("Connecting…").setOngoing(true)
        }
        startForegroundWith(builder.build())
    }

    private fun formatRate(bps: Long): String = when {
        bps >= 1_000_000_000 -> "%.1f GB/s".format(bps / 1e9)
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1e6)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1e3)
        else -> "$bps B/s"
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
