package com.rohit.ytguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class GuardForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ytguard_monitor_channel"
        private const val NOTIF_ID = 7071

        fun start(context: Context) {
            try {
                val intent = Intent(context, GuardForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startSilentForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSilentForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSilentForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "YT Guard Protection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps YT Guard active in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notif = builder
                .setContentTitle("YT Guard Active")
                .setContentText("Focus protection background mein chal raha hai")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
        }
    }
}
