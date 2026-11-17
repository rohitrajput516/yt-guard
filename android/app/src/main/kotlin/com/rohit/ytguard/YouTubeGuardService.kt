package com.rohit.ytguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

class YouTubeGuardService : AccessibilityService() {

    private val PREFS_NAME = "ytguard_prefs"
    private val KEY_CONFIG = "config_json"
    private val YOUTUBE_PKG = "com.google.android.youtube"

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bannerView: TextView? = null

    private var lastBlockTime = 0L
    private var unverifiedWatchStartTime = 0L
    private var lastVerifiedWatchTime = 0L
    private var screenOffTimestamp = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTimestamp = System.currentTimeMillis()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (screenOffTimestamp > 0L) {
                        val elapsed = System.currentTimeMillis() - screenOffTimestamp
                        // Auto-lock Family Mode if screen was off for >= 5 minutes
                        if (elapsed >= 5 * 60 * 1000L) {
                            lockFamilyModeAfterScreenOff()
                        }
                        screenOffTimestamp = 0L
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        removeBanner()
        super.onDestroy()
    }

    override fun onInterrupt() {
        removeBanner()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val rootNode = rootInActiveWindow ?: return

        try {
            val config = loadConfig() ?: return
            val now = System.currentTimeMillis()

            val familyModeExpiry = config.optLong("familyModeExpiry", 0L)
            val isFamilyModeActive = now < familyModeExpiry

            // 1. Check Tamper Protection on Settings / Package Installer
            if (isSettingsOrInstallerPackage(pkg)) {
                val tamperProtection = config.optBoolean("tamperProtection", true)
                if (tamperProtection && !isFamilyModeActive) {
                    checkSettingsTamper(rootNode)
                }
                return
            }

            // 2. Only process YouTube package when NOT in Family Mode
            if (pkg != YOUTUBE_PKG || isFamilyModeActive) {
                unverifiedWatchStartTime = 0L
                return
            }

            val allTexts = ArrayList<String>()
            val allViewIds = ArrayList<String>()
            var isShortsTabSelected = false

            collectNodeTree(rootNode, allTexts, allViewIds) { node ->
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (node.isSelected && (desc == "shorts" || text == "shorts")) {
                    isShortsTabSelected = true
                }
            }

            // 3. Enforce Shorts Lock
            val enforceShortsBlock = config.optBoolean("enforceShortsBlock", true)
            val shortsUnlockExpiry = config.optLong("shortsUnlockExpiry", 0L)
            val isShortsLocked = enforceShortsBlock && (now >= shortsUnlockExpiry)

            if (isShortsLocked) {
                val onShortsScreen = isShortsTabSelected || allViewIds.any { id ->
                    id.contains("reel_watch") ||
                    id.contains("reel_recycler") ||
                    id.contains("reel_player") ||
                    id.contains("shorts_player") ||
                    id.contains("reel_main_container")
                }

                if (onShortsScreen) {
                    triggerBlock("YouTube Shorts is locked")
                    return
                }
            }

            // 4. Check Active Video Watch Screen (Whitelist, Title Blacklist, Interest Filter)
            val isWatchScreen = allViewIds.any { id ->
                id.contains("watch_next") ||
                id.contains("player_view") ||
                id.contains("watch_while") ||
                id.contains("video_metadata") ||
                id.contains("channel_navigation")
            } || allTexts.any { t ->
                val lower = t.lowercase(Locale.ROOT)
                lower == "subscribe" || lower == "subscribed" || lower.contains("views")
            }

            if (!isWatchScreen) {
                unverifiedWatchStartTime = 0L
                return
            }

            val combinedScreenText = allTexts.joinToString(" | ").lowercase(Locale.ROOT)

            // 4A. Title Keyword Blacklist Check
            val enforceTitleBlacklist = config.optBoolean("enforceTitleBlacklist", true)
            if (enforceTitleBlacklist) {
                val blockedArray = config.optJSONArray("blockedTitleKeywords")
                if (blockedArray != null) {
                    for (i in 0 until blockedArray.length()) {
                        val word = blockedArray.optString(i, "").trim().lowercase(Locale.ROOT)
                        if (word.isNotEmpty() && combinedScreenText.contains(word)) {
                            unverifiedWatchStartTime = 0L
                            triggerBlock("Blocked keyword detected: \"$word\"")
                            return
                        }
                    }
                }
            }

            // 4B. Emergency 1-Video Pass Check (15 Min Pass)
            val emergencyPassExpiry = config.optLong("emergencyPassExpiry", 0L)
            if (now < emergencyPassExpiry) {
                unverifiedWatchStartTime = 0L
                return
            }

            // 4C. Group Whitelist & Interest Filter Check
            val enforceWhitelist = config.optBoolean("enforceWhitelist", true)
            val enforceInterestFilter = config.optBoolean("enforceInterestFilter", false)

            if (!enforceWhitelist && !enforceInterestFilter) {
                unverifiedWatchStartTime = 0L
                return
            }

            val activeGroupId = config.optString("activeGroupId", "study")
            val groupsArray = config.optJSONArray("groups")
            var activeGroup: JSONObject? = null
            if (groupsArray != null) {
                for (i in 0 until groupsArray.length()) {
                    val g = groupsArray.optJSONObject(i)
                    if (g != null && g.optString("id") == activeGroupId) {
                        activeGroup = g
                        break
                    }
                }
            }

            if (activeGroup == null) return

            var channelMatched = !enforceWhitelist
            if (enforceWhitelist) {
                val channelsArray = activeGroup.optJSONArray("channels")
                if (channelsArray != null && channelsArray.length() > 0) {
                    for (i in 0 until channelsArray.length()) {
                        val ch = channelsArray.optString(i, "").trim().lowercase(Locale.ROOT)
                        if (ch.isNotEmpty() && combinedScreenText.contains(ch)) {
                            channelMatched = true
                            break
                        }
                    }
                }
            }

            var interestMatched = !enforceInterestFilter
            if (enforceInterestFilter) {
                val interestsArray = activeGroup.optJSONArray("interests")
                if (interestsArray != null && interestsArray.length() > 0) {
                    for (i in 0 until interestsArray.length()) {
                        val kw = interestsArray.optString(i, "").trim().lowercase(Locale.ROOT)
                        if (kw.isNotEmpty() && combinedScreenText.contains(kw)) {
                            interestMatched = true
                            break
                        }
                    }
                }
            }

            if (channelMatched && interestMatched) {
                unverifiedWatchStartTime = 0L
                lastVerifiedWatchTime = now
            } else {
                // Keep a short 1.8s grace window while video player metadata loads
                if (now - lastVerifiedWatchTime < 1500L) {
                    return
                }
                if (unverifiedWatchStartTime == 0L) {
                    unverifiedWatchStartTime = now
                } else if (now - unverifiedWatchStartTime >= 1800L) {
                    unverifiedWatchStartTime = 0L
                    val groupName = activeGroup.optString("name", "Active Group")
                    val reason = if (!channelMatched) {
                        "Channel not whitelisted in [$groupName]"
                    } else {
                        "Topic does not match [$groupName] interests"
                    }
                    triggerBlock(reason)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        return pkg.contains("settings") ||
               pkg.contains("packageinstaller") ||
               pkg.contains("securitypermission") ||
               pkg.contains("permissioncontroller")
    }

    private fun checkSettingsTamper(rootNode: AccessibilityNodeInfo) {
        val texts = ArrayList<String>()
        val ids = ArrayList<String>()
        collectNodeTree(rootNode, texts, ids) {}

        val combined = texts.joinToString(" ").lowercase(Locale.ROOT)

        val mentionsProtectedApp = combined.contains("yt guard") || combined.contains("youtube")
        val hasDangerOptions = combined.contains("force stop") ||
                               combined.contains("uninstall") ||
                               combined.contains("clear data") ||
                               combined.contains("clear storage") ||
                               combined.contains("storage usage")

        if (mentionsProtectedApp && hasDangerOptions) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            showCleanBanner("Settings Tamper Shield Active")
        }
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

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeTree(child, texts, viewIds, inspector)
            }
        }
    }

    private fun triggerBlock(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 900L) return
        lastBlockTime = now

        performGlobalAction(GLOBAL_ACTION_BACK)
        showCleanBanner(message)
    }

    private fun showCleanBanner(message: String) {
        handler.post {
            try {
                removeBanner()
                val wm = windowManager ?: return@post

                val tv = TextView(this).apply {
                    text = "YT GUARD  •  $message"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(44, 28, 44, 28)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F172A"))
                        cornerRadius = 20f
                        setStroke(2, Color.parseColor("#334155"))
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 140
                }

                wm.addView(tv, params)
                bannerView = tv

                handler.postDelayed({ removeBanner() }, 2200L)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeBanner() {
        val view = bannerView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        } finally {
            bannerView = null
        }
    }

    private fun loadConfig(): JSONObject? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun lockFamilyModeAfterScreenOff() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CONFIG, null) ?: return
            val json = JSONObject(raw)
            val currentExpiry = json.optLong("familyModeExpiry", 0L)
            if (currentExpiry > System.currentTimeMillis()) {
                json.put("familyModeExpiry", 0L)
                prefs.edit().putString(KEY_CONFIG, json.toString()).commit()
            }
        } catch (_: Exception) {
        }
    }
}
