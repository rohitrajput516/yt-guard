package com.rohit.ytguard

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
import org.json.JSONObject

class GuardForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ytguard_monitor_channel"
        private const val NOTIF_ID = 7071
        private const val EXTRA_TITLE = "notif_title"
        private const val EXTRA_BODY = "notif_body"

        fun syncFromJson(context: Context, rawJson: String?) {
            if (rawJson.isNullOrEmpty()) {
                stop(context)
                return
            }
            try {
                val json = JSONObject(rawJson)
                val activeNames = ArrayList<String>()
                val shorts = json.optBoolean("shortsBlockEnabled", false)
                val keywords = json.optBoolean("ytBlacklistEnabled", false)
                val gemini = json.optBoolean("geminiBlockEnabled", false)
                val tamper = json.optBoolean("tamperShieldEnabled", false)
                val appLock = json.optBoolean("appBlockerEnabled", false)

                if (shorts) activeNames.add("Shorts")
                if (keywords) activeNames.add("Keywords")
                if (gemini) activeNames.add("Gemini")
                if (appLock) activeNames.add("App Lock")
                if (tamper) activeNames.add("Tamper Shield")

                if (activeNames.isEmpty()) {
                    stop(context)
                    return
                }

                val presetArr = json.optJSONArray("studyModeFeatures")
                val studyActive = if (presetArr != null && presetArr.length() > 0) {
                    var allOn = true
                    for (i in 0 until presetArr.length()) {
                        val key = presetArr.optString(i, "")
                        if (key.isNotEmpty() && !json.optBoolean(key, false)) {
                            allOn = false
                            break
                        }
                    }
                    allOn
                } else {
                    shorts && appLock && tamper
                }

                val title = if (studyActive) {
                    "YT Guard • Study Mode Active"
                } else {
                    "YT Guard • Protection Active"
                }
                val body = "Active: ${activeNames.joinToString(", ")} • Tap to open"
                startOrUpdate(context, title, body)
            } catch (_: Exception) {
            }
        }

        fun startOrUpdate(context: Context, title: String, body: String) {
            try {
                val intent = Intent(context, GuardForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_BODY, body)
                }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "YT Guard • Protection Active"
        val body = intent?.getStringExtra(EXTRA_BODY) ?: "Focus protection is running • Tap to open"
        showSilentNotification(title, body)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showSilentNotification(title: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "YT Guard Active Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows currently active focus shields and opens YT Guard on tap"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 101, openIntent, piFlags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notif = builder
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
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
