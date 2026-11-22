package com.rohit.ytguard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class YouTubeGuardService : AccessibilityService() {

    private val PREFS_NAME = "ytguard_prefs"
    private val KEY_CONFIG = "config_json"
    private val YOUTUBE_PKG = "com.google.android.youtube"

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var activeOverlayTag: String? = null

    private var backCooldownUntil = 0L
    private var unverifiedWatchStart = 0L
    private var lastInspectedPkg = ""
    private var lastDetectedForegroundPkg = ""

    private val appUnlockExpiry = HashMap<String, Long>()
    private var settingsUnlockExpiry = 0L
    private var shortsTempUnlockExpiry = 0L
    private var ytVideoTempUnlockExpiry = 0L

    private val usagePollRunnable = object : Runnable {
        override fun run() {
            try {
                checkForegroundAppViaUsageStats()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 400L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler.removeCallbacks(usagePollRunnable)
        handler.post(usagePollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(usagePollRunnable)
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    private fun checkForegroundAppViaUsageStats() {
        val now = System.currentTimeMillis()
        if (now < backCooldownUntil || overlayView != null) return

        val config = loadConfig() ?: return
        if (!config.optBoolean("appBlockerEnabled", false)) return

        val pkg = queryTopPackageUsingUsageStats() ?: return
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return

        checkAndBlockApp(pkg, config, now)
    }

    private fun queryTopPackageUsingUsageStats(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val end = System.currentTimeMillis()
            val start = end - 5000L
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    latestPkg = event.packageName
                }
            }
            if (!latestPkg.isNullOrEmpty()) {
                lastDetectedForegroundPkg = latestPkg
            }
            lastDetectedForegroundPkg.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("gboard")
        ) {
            return
        }

        if (now < backCooldownUntil || overlayView != null) {
            return
        }

        val config = loadConfig() ?: return

        if (pkg != lastInspectedPkg) {
            unverifiedWatchStart = 0L
            lastInspectedPkg = pkg
        }

        // 1. SETTINGS TAMPER SHIELD
        if (isSettingsOrInstallerPackage(pkg)) {
            if (config.optBoolean("tamperShieldEnabled", false) && now >= settingsUnlockExpiry) {
                val root = rootInActiveWindow ?: return
                checkSettingsTamperPopup(root, config)
            }
            return
        }

        // 2. CUSTOM APP & CATEGORY BLOCKER (Instant via Accessibility + Backup via Usage Stats)
        if (config.optBoolean("appBlockerEnabled", false)) {
            if (checkAndBlockApp(pkg, config, now)) {
                return
            }
        }

        // 3. YOUTUBE SHORTS & VIDEO CATEGORY INSPECTION
        if (pkg != YOUTUBE_PKG) {
            unverifiedWatchStart = 0L
            return
        }

        val shortsEnabled = config.optBoolean("shortsBlockEnabled", false)
        val whitelistEnabled = config.optBoolean("ytWhitelistEnabled", false)
        val blacklistEnabled = config.optBoolean("ytBlacklistEnabled", false)

        if (!shortsEnabled && !whitelistEnabled && !blacklistEnabled) {
            return
        }

        val root = rootInActiveWindow ?: return
        val allTexts = ArrayList<String>()
        val allViewIds = ArrayList<String>()
        val screenWidth = resources.displayMetrics.widthPixels

        var isShortsTabSelected = false
        var hasMiniplayer = false
        var hasSearchBoxActive = false

        var rightRemixTop = -1
        var rightShareTop = -1
        val rightRailTops = ArrayList<Int>()
        val rightRailLabels = HashSet<String>()
        val bounds = Rect()

        collectNodeTree(root, allTexts, allViewIds) { node ->
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            } else {
                ""
            }

            if ((desc == "shorts" || text == "shorts" || desc.startsWith("shorts,")) &&
                (node.isSelected || node.parent?.isSelected == true || stateDesc.contains("selected"))
            ) {
                isShortsTabSelected = true
            }

            if (id.contains("miniplayer") || id.contains("floaty_bar") || desc.contains("close miniplayer")) {
                hasMiniplayer = true
            }
            if (id.contains("search_edit_text") && node.isFocused) {
                hasSearchBoxActive = true
            }

            // Detect YouTube Shorts Vertical Right-Edge Action Rail (Remix, Share, Save, Dislike, Comment)
            if (shortsEnabled && node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                if (bounds.left > (screenWidth * 0.68f) && bounds.width() > 0 && bounds.height() > 0) {
                    val label = when {
                        desc.contains("remix") || text == "remix" -> "remix"
                        desc.startsWith("share") || text == "share" -> "share"
                        desc.contains("dislike") || text == "dislike" -> "dislike"
                        desc.contains("comment") -> "comment"
                        desc == "save" || text == "save" || desc.contains("save to playlist") -> "save"
                        desc.contains("like this video") || desc.startsWith("like ") -> "like"
                        else -> ""
                    }
                    if (label.isNotEmpty()) {
                        rightRailLabels.add(label)
                        rightRailTops.add(bounds.top)
                        if (label == "remix") rightRemixTop = bounds.top
                        if (label == "share") rightShareTop = bounds.top
                    }
                }
            }
        }

        if (hasSearchBoxActive) {
            unverifiedWatchStart = 0L
            return
        }

        // 3A. SHORTS DETECTION (View IDs + Selected Tab + Vertical Right Rail)
        if (shortsEnabled && now >= shortsTempUnlockExpiry) {
            val hasShortsViewId = allViewIds.any { id ->
                id.contains("reel_watch") ||
                id.contains("reel_recycler") ||
                id.contains("reel_player") ||
                id.contains("shorts_player") ||
                id.contains("reel_main_container") ||
                id.contains("reel_progress_bar") ||
                id.contains("reel_channel_bar") ||
                id.contains("reel_dyn_")
            }

            val remixShareStacked = rightRemixTop > 0 && rightShareTop > 0 &&
                abs(rightRemixTop - rightShareTop) > dp(35)

            val verticalRailSpread = if (rightRailTops.size >= 3) {
                (rightRailTops.maxOrNull() ?: 0) - (rightRailTops.minOrNull() ?: 0)
            } else {
                0
            }
            val isVerticalShortsRail = remixShareStacked ||
                (rightRailLabels.size >= 3 && verticalRailSpread > dp(110))

            if (isShortsTabSelected || hasShortsViewId || isVerticalShortsRail) {
                stopAllMediaPlayback()
                val shortsPwd = config.optJSONObject("passwords")?.optString("shorts", "2222") ?: "2222"
                showFullScreenPopup(
                    tag = "yt_shorts",
                    badge = "YT GUARD • SHORTS BLOCKED",
                    title = "YouTube Shorts Blocked Hai",
                    message = "Pichhle page par wapas jane ke liye niche button dabayein, ya Shorts password daal kar 15 min unlock karein.",
                    expectedPassword = shortsPwd,
                    unlockDurationMinutes = 15,
                    isYouTubeMedia = true,
                    isStandaloneAppLock = false,
                    onUnlocked = {
                        shortsTempUnlockExpiry = System.currentTimeMillis() + (15 * 60 * 1000L)
                    }
                )
                return
            }
        }

        // 3B. ACTIVE NORMAL VIDEO WATCH PAGE CHECK
        if (!whitelistEnabled && !blacklistEnabled) return
        if (now < ytVideoTempUnlockExpiry) {
            unverifiedWatchStart = 0L
            return
        }

        val hasExpandedPlayer = !hasMiniplayer && allViewIds.any { id ->
            id.contains("watch_while_time_bar") ||
            id.contains("player_fragment_container") ||
            id.contains("watch_player") ||
            id.contains("player_view") ||
            id.contains("video_metadata")
        }

        var watchSignals = 0
        val lowerTexts = allTexts.map { it.lowercase(Locale.ROOT) }
        if (lowerTexts.any { it == "subscribe" || it == "subscribed" }) watchSignals++
        if (lowerTexts.any { it == "share" || it.startsWith("share ") }) watchSignals++
        if (lowerTexts.any { it.contains("like this video") || it == "dislike" || it.contains("dislike this video") }) watchSignals++
        if (lowerTexts.any { it.startsWith("comments") }) watchSignals++

        val isActuallyPlayingVideoPage = hasExpandedPlayer && watchSignals >= 2
        if (!isActuallyPlayingVideoPage) {
            unverifiedWatchStart = 0L
            return
        }

        val combinedScreenText = lowerTexts.joinToString(" | ")
        val ytPwd = config.optJSONObject("passwords")?.optString("ytAdmin", "3333") ?: "3333"

        if (blacklistEnabled) {
            val blockedArr = config.optJSONArray("blockedTitleKeywords")
            if (blockedArr != null) {
                for (i in 0 until blockedArr.length()) {
                    val word = blockedArr.optString(i, "").trim().lowercase(Locale.ROOT)
                    if (word.isNotEmpty() && combinedScreenText.contains(word)) {
                        unverifiedWatchStart = 0L
                        stopAllMediaPlayback()
                        showFullScreenPopup(
                            tag = "yt_keyword",
                            badge = "YT GUARD • VIDEO BLOCKED",
                            title = "Blocked Keyword Mila",
                            message = "Is video mein blocked shabd \"$word\" mila hai. Pichhle page par jane ke liye button dabayein.",
                            expectedPassword = ytPwd,
                            unlockDurationMinutes = 15,
                            isYouTubeMedia = true,
                            isStandaloneAppLock = false,
                            onUnlocked = {
                                ytVideoTempUnlockExpiry = System.currentTimeMillis() + (15 * 60 * 1000L)
                            }
                        )
                        return
                    }
                }
            }
        }

        if (whitelistEnabled) {
            val activeGroup = getActiveGroup(config) ?: return
            val channelsArr = activeGroup.optJSONArray("channels")
            var matched = false
            if (channelsArr != null) {
                for (i in 0 until channelsArr.length()) {
                    val ch = channelsArr.optString(i, "").trim().lowercase(Locale.ROOT)
                    if (ch.isNotEmpty() && combinedScreenText.contains(ch)) {
                        matched = true
                        break
                    }
                }
            }

            if (matched) {
                unverifiedWatchStart = 0L
            } else {
                if (unverifiedWatchStart == 0L) {
                    unverifiedWatchStart = now
                } else if (now - unverifiedWatchStart >= 1100L) {
                    unverifiedWatchStart = 0L
                    stopAllMediaPlayback()
                    val groupName = activeGroup.optString("name", "Active Category")
                    showFullScreenPopup(
                        tag = "yt_category",
                        badge = "YT GUARD • CATEGORY LOCK",
                        title = "Alag Category Ki Video",
                        message = "Ye video aapki active category [$groupName] mein nahi hai. Pichhle page par wapas jayein ya password se 15 min pass lein.",
                        expectedPassword = ytPwd,
                        unlockDurationMinutes = 15,
                        isYouTubeMedia = true,
                        isStandaloneAppLock = false,
                        onUnlocked = {
                            ytVideoTempUnlockExpiry = System.currentTimeMillis() + (15 * 60 * 1000L)
                        }
                    )
                }
            }
        }
    }

    private fun checkAndBlockApp(pkg: String, config: JSONObject, now: Long): Boolean {
        val unlockUntil = appUnlockExpiry[pkg] ?: 0L
        if (now < unlockUntil) return false

        val lockMatch = findAppLockMatch(pkg, config) ?: return false
        showFullScreenPopup(
            tag = "app:$pkg",
            badge = "APP LOCKED",
            title = lockMatch.title,
            message = lockMatch.message,
            expectedPassword = lockMatch.password,
            unlockDurationMinutes = 15,
            isYouTubeMedia = false,
            isStandaloneAppLock = true,
            onUnlocked = {
                appUnlockExpiry[pkg] = System.currentTimeMillis() + (15 * 60 * 1000L)
            }
        )
        return true
    }

    private data class AppLockMatch(val title: String, val message: String, val password: String)

    private fun findAppLockMatch(pkg: String, config: JSONObject): AppLockMatch? {
        val indArr = config.optJSONArray("individualLockedApps")
        if (indArr != null) {
            for (i in 0 until indArr.length()) {
                val obj = indArr.optJSONObject(i) ?: continue
                if (obj.optString("packageName") == pkg) {
                    val appName = obj.optString("appName", pkg)
                    val pwd = obj.optString("password", "5555")
                    return AppLockMatch(
                        title = "$appName Locked Hai",
                        message = "Is app ko kholne ke liye iska alag password dalein, ya pichhle page par wapas jayein.",
                        password = pwd
                    )
                }
            }
        }

        val catArr = config.optJSONArray("appCategories")
        if (catArr != null) {
            for (i in 0 until catArr.length()) {
                val cat = catArr.optJSONObject(i) ?: continue
                val pkgs = cat.optJSONArray("packages") ?: continue
                for (j in 0 until pkgs.length()) {
                    if (pkgs.optString(j) == pkg) {
                        val catName = cat.optString("name", "Locked Category")
                        val pwd = cat.optString("password", "5555")
                        return AppLockMatch(
                            title = "Category Locked: $catName",
                            message = "Ye app [$catName] category mein locked hai. Unlock karne ke liye is category ka password dalein.",
                            password = pwd
                        )
                    }
                }
            }
        }
        return null
    }

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.contains("settings") ||
               lower.contains("packageinstaller") ||
               lower.contains("securitypermission") ||
               lower.contains("permissioncontroller") ||
               lower.contains("accessibility")
    }

    private fun checkSettingsTamperPopup(root: AccessibilityNodeInfo, config: JSONObject) {
        val texts = ArrayList<String>()
        val ids = ArrayList<String>()
        collectNodeTree(root, texts, ids) {}

        val combined = texts.joinToString(" ").lowercase(Locale.ROOT)
        val isTargetProtectedApp = combined.contains("yt guard") || combined.contains("youtube")
        val isAccessibilityScreen = combined.contains("yt guard") && (
            combined.contains("shortcut") ||
            combined.contains("about this function") ||
            combined.contains("accessibility features") ||
            combined.contains("turn off") ||
            combined.contains("stop")
        )
        val isUninstallOrClearData = isTargetProtectedApp && (
            combined.contains("uninstall") ||
            combined.contains("force stop") ||
            combined.contains("clear data") ||
            combined.contains("clear storage") ||
            combined.contains("storage usage")
        )

        if (isAccessibilityScreen || isUninstallOrClearData) {
            val tamperPwd = config.optJSONObject("passwords")?.optString("tamper", "4444") ?: "4444"
            showFullScreenPopup(
                tag = "settings_tamper",
                badge = "TAMPER SHIELD • PROTECTED",
                title = "Uninstall / Clear Data Locked",
                message = "Settings mein badlav karne ke liye Tamper Password dalein, ya niche button se pichhle page par wapas jayein.",
                expectedPassword = tamperPwd,
                unlockDurationMinutes = 5,
                isYouTubeMedia = false,
                isStandaloneAppLock = false,
                onUnlocked = {
                    settingsUnlockExpiry = System.currentTimeMillis() + (5 * 60 * 1000L)
                }
            )
        }
    }

    private inner class BlockerOverlayLayout(context: Context) : LinearLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun showFullScreenPopup(
        tag: String,
        badge: String,
        title: String,
        message: String,
        expectedPassword: String,
        unlockDurationMinutes: Int,
        isYouTubeMedia: Boolean,
        isStandaloneAppLock: Boolean,
        onUnlocked: () -> Unit
    ) {
        handler.post {
            try {
                if (overlayView != null && activeOverlayTag == tag) return@post
                removeOverlay()

                val wm = windowManager ?: return@post
                activeOverlayTag = tag

                val rootLayout = BlockerOverlayLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#F70B1120"))
                    setPadding(dp(28), dp(40), dp(28), dp(40))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                }

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(24), dp(28), dp(24), dp(24))
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
                    setPadding(0, 0, 0, dp(20))
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
                        backCooldownUntil = System.currentTimeMillis() + 1800L
                        unverifiedWatchStart = 0L
                        lastDetectedForegroundPkg = ""
                        if (isYouTubeMedia) {
                            stopAllMediaPlayback()
                        }
                        removeOverlay()
                        handler.postDelayed({
                            if (isStandaloneAppLock) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            } else {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                if (isYouTubeMedia) {
                                    handler.postDelayed({
                                        stopAllMediaPlayback()
                                        closeYouTubeMiniplayer()
                                    }, 380L)
                                }
                            }
                        }, 120L)
                    }
                }
                val btnParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(18) }
                card.addView(goBackBtn, btnParams)

                val pwdInput = EditText(this).apply {
                    hint = "Password daal kar unlock karein..."
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
                val inputParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                card.addView(pwdInput, inputParams)

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
                    text = "Unlock ($unlockDurationMinutes Min)"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2563EB"))
                        cornerRadius = dp(12).toFloat()
                    }
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener {
                        val entered = pwdInput.text.toString().trim()
                        if (entered == expectedPassword) {
                            onUnlocked()
                            backCooldownUntil = System.currentTimeMillis() + 1000L
                            removeOverlay()
                            Toast.makeText(
                                this@YouTubeGuardService,
                                "Unlocked for $unlockDurationMinutes min",
                                Toast.LENGTH_SHORT
                            ).show()
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

                rootLayout.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(this)) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }

                wm.addView(rootLayout, params)
                overlayView = rootLayout
            } catch (_: Exception) {
            }
        }
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
        } finally {
            overlayView = null
            activeOverlayTag = null
        }
    }

    private fun stopAllMediaPlayback() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener {}
                    .build()
                am.requestAudioFocus(req)
            }
        } catch (_: Exception) {
        }
    }

    private fun closeYouTubeMiniplayer() {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != YOUTUBE_PKG) return
            clickMiniplayerClose(root)
        } catch (_: Exception) {
        }
    }

    private fun clickMiniplayerClose(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val isClose = id.contains("miniplayer_close") ||
                      id.contains("floaty_close") ||
                      desc.contains("close miniplayer") ||
                      desc == "close video"
        if (isClose && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            if (clickMiniplayerClose(node.getChild(i))) return true
        }
        return false
    }

    private fun collectNodeTree(
        node: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        viewIds: MutableList<String>,
        inspector: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null) return
        inspector(node)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { viewIds.add(it) }
        for (i in 0 until node.childCount) {
            collectNodeTree(node.getChild(i), texts, viewIds, inspector)
        }
    }

    private fun getActiveGroup(config: JSONObject): JSONObject? {
        val activeId = config.optString("activeGroupId", "study")
        val arr = config.optJSONArray("groups") ?: return null
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i)
            if (g != null && g.optString("id") == activeId) return g
        }
        return if (arr.length() > 0) arr.optJSONObject(0) else null
    }

    private fun loadConfig(): JSONObject? {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
