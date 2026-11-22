package com.rohit.ytguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object GuardRuntimeState {
    val appUnlockExpiry = HashMap<String, Long>()
    var settingsUnlockExpiry = 0L
    var shortsTempUnlockExpiry = 0L
    var ytVideoTempUnlockExpiry = 0L
    var geminiTempUnlockExpiry = 0L
    var backCooldownUntil = 0L
    var lastGateLaunchAt = 0L
    var activeService: YouTubeGuardService? = null

    fun clearAllTempUnlocks() {
        appUnlockExpiry.clear()
        settingsUnlockExpiry = 0L
        shortsTempUnlockExpiry = 0L
        ytVideoTempUnlockExpiry = 0L
        geminiTempUnlockExpiry = 0L
    }

    fun grantUnlock(unlockType: String, targetKey: String, minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 45)
        val expiry = System.currentTimeMillis() + (safeMinutes * 60_000L)
        when (unlockType) {
            "settings" -> settingsUnlockExpiry = expiry
            "shorts" -> shortsTempUnlockExpiry = expiry
            "yt_video" -> ytVideoTempUnlockExpiry = expiry
            "gemini" -> {
                geminiTempUnlockExpiry = expiry
                appUnlockExpiry["com.google.android.apps.bard"] = expiry
            }
            "app" -> {
                if (targetKey.isNotEmpty()) {
                    appUnlockExpiry[targetKey] = expiry
                }
            }
        }
    }
}

class LockGateActivity : Activity() {

    companion object {
        fun launch(
            context: Context,
            badge: String,
            title: String,
            message: String,
            expectedPassword: String,
            unlockType: String,
            targetKey: String,
            defaultMinutes: Int,
            isStandaloneAppLock: Boolean,
            isYouTubeMedia: Boolean
        ) {
            val now = System.currentTimeMillis()
            if (now - GuardRuntimeState.lastGateLaunchAt < 900L) return
            GuardRuntimeState.lastGateLaunchAt = now

            val intent = Intent(context, LockGateActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra("badge", badge)
                putExtra("title", title)
                putExtra("message", message)
                putExtra("expectedPassword", expectedPassword)
                putExtra("unlockType", unlockType)
                putExtra("targetKey", targetKey)
                putExtra("defaultMinutes", defaultMinutes)
                putExtra("isStandaloneAppLock", isStandaloneAppLock)
                putExtra("isYouTubeMedia", isYouTubeMedia)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            renderFromIntent(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Hardware back blocked; user must tap Go Back button or unlock
    }

    private fun renderFromIntent(intent: Intent) {
        val badge = intent.getStringExtra("badge") ?: "YT GUARD • LOCKED"
        val title = intent.getStringExtra("title") ?: "Access Locked"
        val message = intent.getStringExtra("message") ?: "Pichhle page par jayein ya password dalein."
        val expectedPassword = intent.getStringExtra("expectedPassword") ?: "0000"
        val unlockType = intent.getStringExtra("unlockType") ?: "settings"
        val targetKey = intent.getStringExtra("targetKey") ?: ""
        val defaultMinutes = (intent.getIntExtra("defaultMinutes", 5)).coerceIn(1, 45)
        val isStandaloneAppLock = intent.getBooleanExtra("isStandaloneAppLock", false)
        val isYouTubeMedia = intent.getBooleanExtra("isYouTubeMedia", false)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val badgeTv = TextView(this).apply {
            text = badge
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        card.addView(badgeTv)

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        card.addView(titleTv)

        val msgTv = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        }
        card.addView(msgTv)

        val goBackBtn = Button(this).apply {
            text = "Pichhle Page Par Jayein"
            isAllCaps = false
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                GuardRuntimeState.backCooldownUntil = System.currentTimeMillis() + 2000L
                finish()
                GuardRuntimeState.activeService?.performCleanExit(isStandaloneAppLock, isYouTubeMedia)
            }
        }
        card.addView(
            goBackBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        )

        val timerLabel = TextView(this).apply {
            text = "Unlock Time Chunein (1 - 45 min):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(timerLabel)

        val customMinInput = EditText(this).apply {
            setText(defaultMinutes.toString())
            hint = "1-45"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
        }

        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makePresetBtn(mins: Int): Button {
            return Button(this).apply {
                text = "${mins}m"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#334155"))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { customMinInput.setText(mins.toString()) }
            }
        }

        val presetParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
        presetsRow.addView(makePresetBtn(5), presetParams)
        presetsRow.addView(makePresetBtn(15), presetParams)
        presetsRow.addView(makePresetBtn(30), presetParams)
        presetsRow.addView(
            customMinInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        )

        card.addView(
            presetsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )

        val pwdInput = EditText(this).apply {
            hint = "Password dalein..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
        }
        card.addView(
            pwdInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        val errorTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(errorTv)

        val unlockBtn = Button(this).apply {
            text = "Unlock Karein"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                val entered = pwdInput.text.toString().trim()
                val rawMins = customMinInput.text.toString().trim().toIntOrNull()
                if (rawMins == null || rawMins < 1 || rawMins > 45) {
                    errorTv.text = "Time 1 se 45 minute ke beech dalein."
                    errorTv.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (entered == expectedPassword) {
                    GuardRuntimeState.grantUnlock(unlockType, targetKey, rawMins)
                    GuardRuntimeState.backCooldownUntil = System.currentTimeMillis() + 1200L
                    Toast.makeText(
                        this@LockGateActivity,
                        "Unlocked for $rawMins min",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    errorTv.text = "Galat password! Sahi password dalein."
                    errorTv.visibility = View.VISIBLE
                    pwdInput.setText("")
                }
            }
        }
        card.addView(
            unlockBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
