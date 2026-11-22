package com.rohit.ytguard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

class YouTubeGuardService : AccessibilityService() {

    private val PREFS_NAME = "ytguard_prefs"
    private val KEY_CONFIG = "config_json"
    private val YOUTUBE_PKG = "com.google.android.youtube"
    private val GOOGLE_APP_PKG = "com.google.android.googlequicksearchbox"
    private val GEMINI_PKG = "com.google.android.apps.bard"
    private val PLAY_STORE_PKG = "com.android.vending"
    private val PHONEPE_PKG = "com.phonepe.app"

    private data class AppLockMatch(
        val lockKey: String,
        val title: String,
        val message: String,
        val password: String
    )

    private data class ParsedConfig(
        val shortsBlockEnabled: Boolean,
        val ytWhitelistEnabled: Boolean,
        val ytBlacklistEnabled: Boolean,
        val tamperShieldEnabled: Boolean,
        val geminiBlockEnabled: Boolean,
        val appBlockerEnabled: Boolean,
        val shortsPwd: String,
        val ytAdminPwd: String,
        val tamperPwd: String,
        val appBlockerPwd: String,
        val blockedKeywords: List<String>,
        val activeGroupName: String,
        val activeChannels: List<String>,
        val locksByPkg: Map<String, AppLockMatch>,
        val locksByLabel: Map<String, AppLockMatch>
    )

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var activeOverlayTag: String? = null

    private var cachedConfig: ParsedConfig? = null
    private var cachedConfigVersion = -1L
    private var cachedRawJson: String? = null

    private var lastWindowPkg = ""
    private var lastWindowClass = ""
    private var preApprovedWatchUntil = 0L
    private var pendingWatchCheckScheduled = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                GuardRuntimeState.clearAllTempUnlocks()
                preApprovedWatchUntil = 0L
                removeOverlay()
            }
        }
    }

    private val fastMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                runMultiWindowAndUsageSweep()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 200L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardRuntimeState.activeService = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        GuardForegroundService.start(this)

        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
        } catch (_: Exception) {
        }

        handler.removeCallbacks(fastMonitorRunnable)
        handler.post(fastMonitorRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(fastMonitorRunnable)
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        removeOverlay()
        GuardForegroundService.stop(this)
        if (GuardRuntimeState.activeService === this) {
            GuardRuntimeState.activeService = null
        }
        super.onDestroy()
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    fun performCleanExit(goDirectHome: Boolean, isYouTubeMedia: Boolean) {
        handler.postDelayed({
            if (goDirectHome) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (isYouTubeMedia) {
                    handler.postDelayed({
                        stopAllMediaPlayback()
                        closeYouTubeMiniplayer()
                    }, 300L)
                }
            }
        }, 120L)
    }

    // =========================================================================
    // FAST 200MS MULTI-WINDOW + USAGE STATS SWEEP (CATCHES SPLIT-SCREEN, FLOATING, CLONE & HEAVY APPS)
    // =========================================================================
    private fun runMultiWindowAndUsageSweep() {
        if (GuardRuntimeState.isGateActivityVisible || overlayView != null) return
        val cfg = getCachedConfig() ?: return
        val now = System.currentTimeMillis()

        val activeRootPkg = rootInActiveWindow?.packageName?.toString()
        if (isPhonePePackage(activeRootPkg)) return

        // 1. Scan ALL visible application windows (catches Split-Screen & Realme Floating Windows)
        var sawActiveAppWindow = false
        val appWindows = try {
            windows
        } catch (_: Exception) {
            null
        }

        if (appWindows != null) {
            for (win in appWindows) {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val wRoot = win.root ?: continue
                val wPkg = wRoot.packageName?.toString() ?: continue
                if (isPhonePePackage(wPkg) || isIgnoredSystemPackage(wPkg)) continue
                if (isLauncherPackage(wPkg)) continue

                sawActiveAppWindow = true
                val wTitle = win.title?.toString() ?: ""

                // Tamper Shield check
                if (cfg.tamperShieldEnabled && now >= GuardRuntimeState.settingsUnlockExpiry) {
                    if (checkTamperShield(wPkg, wRoot, cfg, now)) return
                }

                // Complete 4-Way Gemini Blocker check
                if (isGeminiProtectionEnabled(cfg) && now >= GuardRuntimeState.geminiTempUnlockExpiry) {
                    if (checkCompleteGeminiBlock(wPkg, wRoot, cfg, now)) return
                }

                // Custom App & Category Blocker check (Package + Clone/Alias Window Title)
                if (cfg.appBlockerEnabled) {
                    if (checkAndBlockCustomApp(wPkg, wTitle, cfg, now)) return
                }
            }
        }

        // 2. Fallback via UsageEvents for games / WebViews / secure apps that hide Accessibility windows
        if (!sawActiveAppWindow && (activeRootPkg == null || activeRootPkg == "com.android.systemui")) {
            val topPkg = queryLatestResumedPackage(now) ?: return
            if (isPhonePePackage(topPkg) || isIgnoredSystemPackage(topPkg) || isLauncherPackage(topPkg)) return

            if (isGeminiProtectionEnabled(cfg) && now >= GuardRuntimeState.geminiTempUnlockExpiry && topPkg == GEMINI_PKG) {
                triggerGeminiBlock(cfg, isStandaloneApp = true, now = now)
                return
            }

            if (cfg.appBlockerEnabled) {
                checkAndBlockCustomApp(topPkg, "", cfg, now)
            }
        }
    }

    private fun queryLatestResumedPackage(now: Long): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val events = usm.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }
            latestPkg
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // INSTANT ACCESSIBILITY EVENT HANDLER (0ms LATENCY)
    // =========================================================================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // Hard-exempt PhonePe immediately
        if (isPhonePePackage(eventPkg)) return

        // Auto-clear overlay if user navigated back to Home Launcher or YT Guard
        if ((isLauncherPackage(eventPkg) || eventPkg == packageName) && overlayView != null) {
            removeOverlay()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !isIgnoredSystemPackage(eventPkg)
        ) {
            lastWindowPkg = eventPkg
            lastWindowClass = event.className?.toString() ?: ""
        }

        if (isIgnoredSystemPackage(eventPkg)) return
        if (GuardRuntimeState.isGateActivityVisible || overlayView != null) return

        val cfg = getCachedConfig() ?: return

        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        if (isPhonePePackage(rootPkg)) return

        val pkg = rootPkg?.takeIf { !isIgnoredSystemPackage(it) && !isLauncherPackage(it) } ?: eventPkg
        if (isLauncherPackage(pkg)) return

        // Clear old per-app cooldown if user opened a DIFFERENT app
        GuardRuntimeState.clearCooldownIfDifferent("app:$pkg")

        // 1. STAGE 1 MICROSECOND YOUTUBE VIDEO TAP CHECK (Before player opens)
        if (pkg == YOUTUBE_PKG && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (handleYouTubeCardTapInstantCheck(event, cfg, now)) return
        }

        // 2. TAMPER SHIELD (Direct Home via LockGateActivity)
        if (cfg.tamperShieldEnabled && now >= GuardRuntimeState.settingsUnlockExpiry) {
            if (root != null && checkTamperShield(pkg, root, cfg, now)) return
        }

        // 3. COMPLETE 4-WAY GEMINI BLOCKER (App + Google App Chat + Power-Button Floaty + Play Store + Browser)
        if (isGeminiProtectionEnabled(cfg) && now >= GuardRuntimeState.geminiTempUnlockExpiry) {
            if (checkCompleteGeminiBlock(pkg, root, cfg, now)) return
        }

        // 4. CUSTOM APP & CATEGORY BLOCKER (Fast direct package + window title check, no heavy tree scan)
        if (cfg.appBlockerEnabled) {
            val activeTitle = getWindowTitleForPackage(pkg)
            if (checkAndBlockCustomApp(pkg, activeTitle, cfg, now)) return
        }

        // 5. YOUTUBE SHORTS & STAGE 2 WATCH PAGE CHECK
        if (pkg == YOUTUBE_PKG && root != null) {
            inspectYouTubeScreen(root, cfg, now)
        }
    }

    private fun isPhonePePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val l = pkg.lowercase(Locale.ROOT)
        return l == PHONEPE_PKG || l.startsWith("com.phonepe.")
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val l = pkg.lowercase(Locale.ROOT)
        return l == "com.android.launcher" || l.contains(".launcher") || l.endsWith("launcher")
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        if (isPhonePePackage(pkg)) return true
        val l = pkg.lowercase(Locale.ROOT)
        return pkg == packageName ||
            pkg == "com.android.systemui" ||
            l.contains("inputmethod") ||
            l.contains("gboard")
    }

    private fun getWindowTitleForPackage(pkg: String): String {
        return try {
            val wins = windows ?: return ""
            for (w in wins) {
                if (w.root?.packageName?.toString() == pkg) {
                    val t = w.title?.toString()
                    if (!t.isNullOrBlank()) return t
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    // =========================================================================
    // 1. TAMPER SHIELD -> DIRECT HOME SCREEN (NO SETTINGS FLASH)
    // =========================================================================
    private fun checkTamperShield(
        pkg: String,
        root: AccessibilityNodeInfo,
        cfg: ParsedConfig,
        now: Long
    ): Boolean {
        if (GuardRuntimeState.isInCooldown("settings_tamper", now)) return false

        val lowerPkg = pkg.lowercase(Locale.ROOT)
        val isSettings = lowerPkg.contains("settings") || lowerPkg.contains("appdetail") ||
            lowerPkg.contains("permissioncontroller") || lowerPkg.contains("securitypermission")
        val isInstaller = lowerPkg.contains("packageinstaller")
        val isStoreOrManager = pkg == PLAY_STORE_PKG ||
            lowerPkg.contains("heytap.market") ||
            lowerPkg.contains("phonemanager") ||
            lowerPkg.contains("nbu.files")

        if (!isSettings && !isInstaller && !isStoreOrManager) return false

        val texts = ArrayList<String>()
        val ids = ArrayList<String>()
        collectNodeTree(root, texts, ids) {}
        val combined = texts.joinToString(" ").lowercase(Locale.ROOT)

        var shouldLock = false
        var lockTitle = "Settings Protected Hai"

        if (isSettings) {
            val isAccessibilityYTGuard = combined.contains("yt guard") && (
                combined.contains("about this function") ||
                combined.contains("shortcut") ||
                ids.any { it.endsWith("switch_widget") }
            )
            val isProtectedAppInfo = combined.contains("app info") &&
                (combined.contains("yt guard") || combined.contains("youtube")) &&
                (combined.contains("force stop") || combined.contains("uninstall") ||
                    combined.contains("disable") || combined.contains("storage usage"))
            val isDeviceAdminScreen = combined.contains("device admin apps") ||
                combined.contains("activate device admin") ||
                (combined.contains("deactivate") && combined.contains("yt guard"))

            if (isAccessibilityYTGuard) {
                shouldLock = true
                lockTitle = "Accessibility Setting Locked"
            } else if (isProtectedAppInfo) {
                shouldLock = true
                lockTitle = "App Info / Uninstall Locked"
            } else if (isDeviceAdminScreen) {
                shouldLock = true
                lockTitle = "Device Admin Locked"
            }
        } else if (isInstaller) {
            if (combined.contains("yt guard") || combined.contains("youtube")) {
                shouldLock = true
                lockTitle = "Uninstall Blocked Hai"
            }
        } else if (isStoreOrManager) {
            if (combined.contains("yt guard") && combined.contains("uninstall")) {
                shouldLock = true
                lockTitle = "Uninstall Blocked Hai"
            }
        }

        if (shouldLock) {
            removeOverlay()
            LockGateActivity.launch(
                context = this,
                cooldownTag = "settings_tamper",
                badge = "TAMPER SHIELD • PROTECTED",
                title = lockTitle,
                message = "Tamper Shield active hai. Seedha Home Screen par jayein ya Tamper Password daal kar unlock karein.",
                expectedPassword = cfg.tamperPwd,
                unlockType = "settings",
                targetKey = "settings",
                defaultMinutes = 5,
                goDirectHome = true,
                isYouTubeMedia = false
            )
            return true
        }
        return false
    }

    // =========================================================================
    // 2. COMPLETE 4-WAY GEMINI BLOCKER (ZERO IMPACT ON GOOGLE SEARCH)
    // =========================================================================
    private fun isGeminiProtectionEnabled(cfg: ParsedConfig): Boolean {
        if (cfg.geminiBlockEnabled) return true
        if (cfg.appBlockerEnabled && cfg.locksByPkg.containsKey(GEMINI_PKG)) return true
        return false
    }

    private fun getGeminiPassword(cfg: ParsedConfig): String {
        return cfg.locksByPkg[GEMINI_PKG]?.password ?: cfg.appBlockerPwd
    }

    private fun checkCompleteGeminiBlock(
        pkg: String,
        root: AccessibilityNodeInfo?,
        cfg: ParsedConfig,
        now: Long
    ): Boolean {
        if (GuardRuntimeState.isInCooldown("gemini_block", now)) return false

        if (pkg == GEMINI_PKG) {
            triggerGeminiBlock(cfg, isStandaloneApp = true, now = now)
            return true
        }

        if (pkg == GOOGLE_APP_PKG && root != null) {
            if (isGeminiScreenInsideGoogleApp(root)) {
                triggerGeminiBlock(cfg, isStandaloneApp = false, now = now)
                return true
            }
            return false
        }

        if (pkg == PLAY_STORE_PKG && root != null) {
            if (isPlayStoreGeminiPage(root)) {
                triggerGeminiBlock(cfg, isStandaloneApp = false, now = now)
                return true
            }
            return false
        }

        if (isBrowserPackage(pkg) && root != null) {
            if (isBrowserOnGeminiUrl(root)) {
                triggerGeminiBlock(cfg, isStandaloneApp = false, now = now)
                return true
            }
        }

        return false
    }

    private fun isGeminiScreenInsideGoogleApp(root: AccessibilityNodeInfo): Boolean {
        val lowerCls = lastWindowClass.lowercase(Locale.ROOT)
        if (lastWindowPkg == GOOGLE_APP_PKG &&
            (lowerCls.contains(".robin.") || lowerCls.contains("floatyactivity"))
        ) {
            return true
        }

        try {
            val wins = windows
            if (wins != null) {
                for (w in wins) {
                    val wPkg = w.root?.packageName?.toString() ?: ""
                    val wTitle = w.title?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                    if (wPkg == GOOGLE_APP_PKG && wTitle == "gemini") {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }

        var detected = false
        scanNodesUntil(root) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val txt = node.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (id.contains("assistant_robin_") ||
                id.contains("gemini_chat_input") ||
                id.contains("gemini_floaty_") ||
                txt == "ask gemini" ||
                desc == "open gemini live"
            ) {
                detected = true
                true
            } else {
                false
            }
        }
        return detected
    }

    private fun isPlayStoreGeminiPage(root: AccessibilityNodeInfo): Boolean {
        var hasTopGeminiTitle = false
        var hasStoreAction = false
        val rect = Rect()
        val topLimit = dp(260)

        scanNodesUntil(root) { node ->
            val txt = node.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (txt == "google gemini") {
                node.getBoundsInScreen(rect)
                if (rect.top in 1..topLimit) {
                    hasTopGeminiTitle = true
                }
            }
            if (txt == "google llc" || txt == "ask play about this app" ||
                txt == "install" || txt == "update" || txt == "open" ||
                desc == "install" || desc == "update" || desc == "uninstall"
            ) {
                hasStoreAction = true
            }
            hasTopGeminiTitle && hasStoreAction
        }
        return hasTopGeminiTitle && hasStoreAction
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        val l = pkg.lowercase(Locale.ROOT)
        return l.contains("chrome") || l.contains("browser") || l.contains("firefox") ||
            l.contains("opera") || l.contains("brave") || l.contains("edge") ||
            l.contains("duckduckgo") || l.contains("studybrowser")
    }

    private fun isBrowserOnGeminiUrl(root: AccessibilityNodeInfo): Boolean {
        var found = false
        val rect = Rect()
        val urlBarLimit = dp(180)
        scanNodesUntil(root) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val txt = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (txt.contains("gemini.google.com")) {
                node.getBoundsInScreen(rect)
                if (id.contains("url") || id.contains("address") || rect.top < urlBarLimit) {
                    found = true
                    return@scanNodesUntil true
                }
            }
            false
        }
        return found
    }

    private fun triggerGeminiBlock(cfg: ParsedConfig, isStandaloneApp: Boolean, now: Long) {
        if (GuardRuntimeState.isInCooldown("gemini_block", now)) return
        val pwd = getGeminiPassword(cfg)
        showBlockPopup(
            tag = "gemini_block",
            badge = "GEMINI BLOCKER • LOCKED",
            title = "Gemini Blocked Hai",
            message = "Gemini har jagah se lock hai (Google Search normal chalta rahega). Bahar jayein ya password se unlock karein.",
            expectedPassword = pwd,
            unlockType = "gemini",
            targetKey = GEMINI_PKG,
            defaultMinutes = 15,
            isYouTubeMedia = false,
            goDirectHome = isStandaloneApp,
            useActivityGate = isStandaloneApp
        )
    }

    // =========================================================================
    // 3. CUSTOM APP & CATEGORY BLOCKER (ALL APPS EXCEPT PHONEPE -> REAL ACTIVITY GATE)
    // =========================================================================
    private fun checkAndBlockCustomApp(
        pkg: String,
        windowTitle: String,
        cfg: ParsedConfig,
        now: Long
    ): Boolean {
        if (isPhonePePackage(pkg)) return false

        val lockMatch = findAppLockMatch(pkg, windowTitle, cfg) ?: return false
        val tag = "app:${lockMatch.lockKey}"

        if (GuardRuntimeState.isInCooldown(tag, now)) return false

        val unlockUntil = GuardRuntimeState.appUnlockExpiry[lockMatch.lockKey] ?: 0L
        if (now < unlockUntil) return false

        removeOverlay()
        LockGateActivity.launch(
            context = this,
            cooldownTag = tag,
            badge = "APP LOCKED",
            title = lockMatch.title,
            message = lockMatch.message,
            expectedPassword = lockMatch.password,
            unlockType = "app",
            targetKey = lockMatch.lockKey,
            defaultMinutes = 15,
            goDirectHome = true,
            isYouTubeMedia = false
        )
        return true
    }

    private fun findAppLockMatch(pkg: String, windowTitle: String, cfg: ParsedConfig): AppLockMatch? {
        if (isPhonePePackage(pkg)) return null

        // 1. Exact package match (covers normal apps & dual-user clones sharing same package)
        cfg.locksByPkg[pkg]?.let { return it }

        // 2. Window title match for Clone/Alias OEM wrappers
        val cleanTitle = windowTitle.trim().lowercase(Locale.ROOT)
        if (cleanTitle.length >= 3 && !cleanTitle.contains("phonepe")) {
            cfg.locksByLabel[cleanTitle]?.let { return it }
            for ((label, match) in cfg.locksByLabel) {
                if (cleanTitle.startsWith("$label (")) {
                    return match
                }
            }
        }
        return null
    }

    // =========================================================================
    // 4. YOUTUBE: STAGE 1 INSTANT TAP CHECK + SHORTS + STAGE 2 WATCH PAGE CHECK
    // =========================================================================
    private fun handleYouTubeCardTapInstantCheck(
        event: AccessibilityEvent,
        cfg: ParsedConfig,
        now: Long
    ): Boolean {
        if (!cfg.ytWhitelistEnabled && !cfg.ytBlacklistEnabled) return false
        if (now < GuardRuntimeState.ytVideoTempUnlockExpiry) return false
        if (GuardRuntimeState.isInCooldown("yt_video", now)) return false

        val src = event.source ?: return false
        val srcDesc = src.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
        val srcTxt = src.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
        val srcId = src.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        val ignoredControls = setOf(
            "action menu", "more options", "more actions", "search", "clear",
            "navigate up", "home", "shorts", "create", "you", "save to library",
            "subscribe", "subscribed", "share", "ask", "voice search", "captions"
        )
        if (srcDesc in ignoredControls || srcTxt in ignoredControls ||
            srcDesc.startsWith("go to channel") || srcDesc.startsWith("notifications") ||
            srcId.contains("search_") || srcId.contains("pivot_bar") || srcId.contains("tabs_bar")
        ) {
            return false
        }

        var cardNode: AccessibilityNodeInfo? = src
        var steps = 0
        while (cardNode != null && steps < 4) {
            val parent = cardNode.parent ?: break
            val pid = parent.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            if (pid.endsWith("results") || pid.endsWith("watch_list") || pid.contains("view_pager")) {
                break
            }
            cardNode = parent
            steps++
        }
        if (cardNode == null) return false

        val cardTexts = ArrayList<String>()
        val cardIds = ArrayList<String>()
        collectNodeTree(cardNode, cardTexts, cardIds) {}

        val lowerCard = cardTexts.map { it.lowercase(Locale.ROOT) }
        val joined = lowerCard.joinToString(" | ")

        if (joined.contains("sponsored")) return false
        var channelName = ""
        for (t in lowerCard) {
            if (t.startsWith("go to channel ")) {
                channelName = t.removePrefix("go to channel ").trim()
                break
            }
        }
        val looksLikeVideoCard = channelName.isNotEmpty() ||
            (joined.contains(" views") && (joined.contains("ago") || joined.contains("minutes") || joined.contains("seconds")))

        if (!looksLikeVideoCard) return false

        if (cfg.ytBlacklistEnabled) {
            val blockedWord = findMatchedBlockedKeyword(joined, cfg)
            if (blockedWord != null) {
                stopAllMediaPlayback()
                showBlockPopup(
                    tag = "yt_video",
                    badge = "YT GUARD • VIDEO BLOCKED",
                    title = "Blocked Keyword Mila",
                    message = "Is video mein blocked shabd \"$blockedWord\" mila hai. Pichhle page par wapas jayein.",
                    expectedPassword = cfg.ytAdminPwd,
                    unlockType = "yt_video",
                    targetKey = "yt_video",
                    defaultMinutes = 15,
                    isYouTubeMedia = true,
                    goDirectHome = false,
                    useActivityGate = false
                )
                return true
            }
        }

        if (cfg.ytWhitelistEnabled) {
            val targetText = if (channelName.isNotEmpty()) "$channelName | $joined" else joined
            if (matchesActiveWhitelist(targetText, cfg)) {
                preApprovedWatchUntil = now + 2500L
                return false
            } else {
                stopAllMediaPlayback()
                showBlockPopup(
                    tag = "yt_video",
                    badge = "YT GUARD • CATEGORY LOCK",
                    title = "Alag Category Ki Video",
                    message = "Ye video aapki active category [${cfg.activeGroupName}] mein nahi hai. Pichhle page par wapas jayein ya password se unlock karein.",
                    expectedPassword = cfg.ytAdminPwd,
                    unlockType = "yt_video",
                    targetKey = "yt_video",
                    defaultMinutes = 15,
                    isYouTubeMedia = true,
                    goDirectHome = false,
                    useActivityGate = false
                )
                return true
            }
        }

        return false
    }

    private fun inspectYouTubeScreen(root: AccessibilityNodeInfo, cfg: ParsedConfig, now: Long) {
        if (!cfg.shortsBlockEnabled && !cfg.ytWhitelistEnabled && !cfg.ytBlacklistEnabled) return

        val allViewIds = HashSet<String>()
        var hasMiniplayer = false
        var hasSearchBoxFocused = false
        var watchListNode: AccessibilityNodeInfo? = null

        collectNodeIdsAndWatchNode(root, allViewIds) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            if (id.contains("miniplayer") || id.contains("floaty_bar") || desc.contains("close miniplayer")) {
                hasMiniplayer = true
            }
            if ((id.contains("search_edit_text") || id.endsWith("search_query")) && node.isFocused) {
                hasSearchBoxFocused = true
            }
            if (id.endsWith("watch_list") && watchListNode == null) {
                watchListNode = node
            }
        }

        if (hasSearchBoxFocused) return

        // A. SHORTS CHECK (Ignores stale reel_time_bar)
        if (cfg.shortsBlockEnabled &&
            now >= GuardRuntimeState.shortsTempUnlockExpiry &&
            !GuardRuntimeState.isInCooldown("yt_shorts", now)
        ) {
            val isTrueShortsPlayer = allViewIds.any { id ->
                id.endsWith("reel_watch_fragment_root") ||
                id.endsWith("reel_watch_player") ||
                id.endsWith("reel_recycler") ||
                id.endsWith("reel_player_overlay_root") ||
                id.endsWith("reel_player_page_container") ||
                id.endsWith("reel_video_interactions")
            }

            if (isTrueShortsPlayer) {
                stopAllMediaPlayback()
                showBlockPopup(
                    tag = "yt_shorts",
                    badge = "YT GUARD • SHORTS BLOCKED",
                    title = "YouTube Shorts Blocked Hai",
                    message = "Pichhle page par wapas jane ke liye button dabayein, ya Shorts password daal kar unlock karein.",
                    expectedPassword = cfg.shortsPwd,
                    unlockType = "shorts",
                    targetKey = "shorts",
                    defaultMinutes = 15,
                    isYouTubeMedia = true,
                    goDirectHome = false,
                    useActivityGate = false
                )
                return
            }
        }

        // B. STAGE 2 WATCH PAGE CHECK
        if (!cfg.ytWhitelistEnabled && !cfg.ytBlacklistEnabled) return
        if (now < GuardRuntimeState.ytVideoTempUnlockExpiry) return
        if (GuardRuntimeState.isInCooldown("yt_video", now)) return
        if (hasMiniplayer) return

        val hasBrowseOrSearch = allViewIds.any { id ->
            id.endsWith("results") ||
            id.endsWith("search_box") ||
            id.endsWith("inline_player_container") ||
            id.endsWith("tabs_bar_view")
        }
        val hasTrueWatchPage = !hasBrowseOrSearch && allViewIds.any { id ->
            id.endsWith("watch_player") || id.endsWith("watch_while_time_bar_view")
        } && allViewIds.any { id ->
            id.endsWith("video_metadata_layout") || id.endsWith("watch_list")
        }

        if (!hasTrueWatchPage) return

        val wl = watchListNode ?: return
        val headerTexts = ArrayList<String>()
        var hasChannelMarker = false
        var hitLowerSection = false

        collectWatchHeaderOnly(wl, headerTexts) { lowerText ->
            if (lowerText == "comments" || lowerText == "sponsored" || lowerText.startsWith("sponsored –")) {
                hitLowerSection = true
                return@collectWatchHeaderOnly true
            }
            if (lowerText.startsWith("subscribe to ") ||
                lowerText.contains(" subscribers") ||
                lowerText.startsWith("@")
            ) {
                hasChannelMarker = true
            }
            hitLowerSection
        }

        if (headerTexts.isEmpty() || !hasChannelMarker) {
            if (!pendingWatchCheckScheduled) {
                pendingWatchCheckScheduled = true
                handler.postDelayed({
                    pendingWatchCheckScheduled = false
                    val freshRoot = rootInActiveWindow ?: return@postDelayed
                    val freshCfg = getCachedConfig() ?: return@postDelayed
                    if (freshRoot.packageName?.toString() == YOUTUBE_PKG && overlayView == null) {
                        inspectYouTubeScreen(freshRoot, freshCfg, System.currentTimeMillis())
                    }
                }, 220L)
            }
            return
        }

        val combinedHeader = headerTexts.joinToString(" | ").lowercase(Locale.ROOT)

        if (cfg.ytBlacklistEnabled) {
            val blockedWord = findMatchedBlockedKeyword(combinedHeader, cfg)
            if (blockedWord != null) {
                stopAllMediaPlayback()
                showBlockPopup(
                    tag = "yt_video",
                    badge = "YT GUARD • VIDEO BLOCKED",
                    title = "Blocked Keyword Mila",
                    message = "Is video mein blocked shabd \"$blockedWord\" mila hai. Pichhle page par wapas jayein.",
                    expectedPassword = cfg.ytAdminPwd,
                    unlockType = "yt_video",
                    targetKey = "yt_video",
                    defaultMinutes = 15,
                    isYouTubeMedia = true,
                    goDirectHome = false,
                    useActivityGate = false
                )
                return
            }
        }

        if (cfg.ytWhitelistEnabled) {
            if (!matchesActiveWhitelist(combinedHeader, cfg)) {
                if (now < preApprovedWatchUntil) {
                    preApprovedWatchUntil = 0L
                }
                stopAllMediaPlayback()
                showBlockPopup(
                    tag = "yt_video",
                    badge = "YT GUARD • CATEGORY LOCK",
                    title = "Alag Category Ki Video",
                    message = "Ye video aapki active category [${cfg.activeGroupName}] mein nahi hai. Pichhle page par wapas jayein ya password se unlock karein.",
                    expectedPassword = cfg.ytAdminPwd,
                    unlockType = "yt_video",
                    targetKey = "yt_video",
                    defaultMinutes = 15,
                    isYouTubeMedia = true,
                    goDirectHome = false,
                    useActivityGate = false
                )
            }
        }
    }

    private fun findMatchedBlockedKeyword(text: String, cfg: ParsedConfig): String? {
        for (word in cfg.blockedKeywords) {
            if (text.contains(word)) return word
        }
        return null
    }

    private fun matchesActiveWhitelist(text: String, cfg: ParsedConfig): Boolean {
        for (ch in cfg.activeChannels) {
            if (text.contains(ch)) return true
        }
        return false
    }

    private fun collectWatchHeaderOnly(
        node: AccessibilityNodeInfo?,
        outTexts: MutableList<String>,
        shouldStop: (String) -> Boolean
    ): Boolean {
        if (node == null) return false
        val txt = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (txt.isNotEmpty()) {
            val low = txt.lowercase(Locale.ROOT)
            if (shouldStop(low)) return true
            outTexts.add(txt)
        }
        if (desc.isNotEmpty() && desc != txt) {
            val low = desc.lowercase(Locale.ROOT)
            if (shouldStop(low)) return true
            outTexts.add(desc)
        }

        for (i in 0 until node.childCount) {
            if (collectWatchHeaderOnly(node.getChild(i), outTexts, shouldStop)) {
                return true
            }
        }
        return false
    }

    // =========================================================================
    // 5. FULL-SCREEN POPUP (OVERLAY WITH 1-45 MIN TIMER + ACTIVITY FALLBACK)
    // =========================================================================
    private inner class BlockerOverlayLayout(context: Context) : LinearLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun showBlockPopup(
        tag: String,
        badge: String,
        title: String,
        message: String,
        expectedPassword: String,
        unlockType: String,
        targetKey: String,
        defaultMinutes: Int,
        isYouTubeMedia: Boolean,
        goDirectHome: Boolean,
        useActivityGate: Boolean
    ) {
        if (useActivityGate) {
            removeOverlay()
            LockGateActivity.launch(
                context = this,
                cooldownTag = tag,
                badge = badge,
                title = title,
                message = message,
                expectedPassword = expectedPassword,
                unlockType = unlockType,
                targetKey = targetKey,
                defaultMinutes = defaultMinutes,
                goDirectHome = goDirectHome,
                isYouTubeMedia = isYouTubeMedia
            )
            return
        }

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
                    setPadding(dp(24), dp(36), dp(24), dp(36))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                }

                val scroll = ScrollView(this).apply {
                    isFillViewport = true
                }

                val centerHolder = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
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

                val exitBtn = Button(this).apply {
                    text = if (goDirectHome) "Home Screen Par Jayein" else "Pichhle Page Par Jayein"
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
                        GuardRuntimeState.setCooldown(tag, 1600L)
                        if (isYouTubeMedia) stopAllMediaPlayback()
                        removeOverlay()
                        performCleanExit(goDirectHome, isYouTubeMedia)
                    }
                }
                card.addView(
                    exitBtn,
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
                    setText(defaultMinutes.coerceIn(1, 45).toString())
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
                            GuardRuntimeState.setCooldown(tag, 1000L)
                            removeOverlay()
                            Toast.makeText(
                                this@YouTubeGuardService,
                                "Unlocked for $rawMins min",
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

                centerHolder.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                scroll.addView(centerHolder)
                rootLayout.addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
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
                LockGateActivity.launch(
                    context = this,
                    cooldownTag = tag,
                    badge = badge,
                    title = title,
                    message = message,
                    expectedPassword = expectedPassword,
                    unlockType = unlockType,
                    targetKey = targetKey,
                    defaultMinutes = defaultMinutes,
                    goDirectHome = goDirectHome,
                    isYouTubeMedia = isYouTubeMedia
                )
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

    // =========================================================================
    // RAM CONFIG CACHE (ZERO JSON PARSING PER SCROLL/EVENT)
    // =========================================================================
    private fun getCachedConfig(): ParsedConfig? {
        val currentVersion = GuardRuntimeState.configVersion
        if (cachedConfig != null && cachedConfigVersion == currentVersion) {
            return cachedConfig
        }
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) ?: return null
        if (cachedConfig != null && raw == cachedRawJson) {
            cachedConfigVersion = currentVersion
            return cachedConfig
        }
        val parsed = parseConfig(raw) ?: return cachedConfig
        cachedConfig = parsed
        cachedRawJson = raw
        cachedConfigVersion = currentVersion
        return parsed
    }

    private fun parseConfig(raw: String): ParsedConfig? {
        return try {
            val json = JSONObject(raw)
            val pwds = json.optJSONObject("passwords")
            val shortsPwd = pwds?.optString("shorts", "2222") ?: "2222"
            val ytAdminPwd = pwds?.optString("ytAdmin", "3333") ?: "3333"
            val tamperPwd = pwds?.optString("tamper", "4444") ?: "4444"
            val appBlockerPwd = pwds?.optString("appBlocker", "5555") ?: "5555"

            val keywords = ArrayList<String>()
            val kwArr = json.optJSONArray("blockedTitleKeywords")
            if (kwArr != null) {
                for (i in 0 until kwArr.length()) {
                    val w = kwArr.optString(i, "").trim().lowercase(Locale.ROOT)
                    if (w.isNotEmpty()) keywords.add(w)
                }
            }

            val activeId = json.optString("activeGroupId", "study")
            var activeGroupName = "Active Category"
            val activeChannels = ArrayList<String>()
            val groupsArr = json.optJSONArray("groups")
            if (groupsArr != null) {
                var activeObj: JSONObject? = null
                for (i in 0 until groupsArr.length()) {
                    val g = groupsArr.optJSONObject(i) ?: continue
                    if (g.optString("id") == activeId) {
                        activeObj = g
                        break
                    }
                }
                if (activeObj == null && groupsArr.length() > 0) {
                    activeObj = groupsArr.optJSONObject(0)
                }
                if (activeObj != null) {
                    activeGroupName = activeObj.optString("name", "Active Category")
                    val chArr = activeObj.optJSONArray("channels")
                    if (chArr != null) {
                        for (i in 0 until chArr.length()) {
                            val ch = chArr.optString(i, "").trim().lowercase(Locale.ROOT)
                            if (ch.isNotEmpty()) activeChannels.add(ch)
                        }
                    }
                }
            }

            val locksByPkg = HashMap<String, AppLockMatch>()
            val locksByLabel = HashMap<String, AppLockMatch>()

            // App Categories first (so individual password overrides category if both set)
            val catArr = json.optJSONArray("appCategories")
            if (catArr != null) {
                for (i in 0 until catArr.length()) {
                    val cat = catArr.optJSONObject(i) ?: continue
                    val catName = cat.optString("name", "Locked Category")
                    val pwd = cat.optString("password", appBlockerPwd)
                    val pkgs = cat.optJSONArray("packages") ?: continue
                    for (j in 0 until pkgs.length()) {
                        val pkg = pkgs.optString(j, "").trim()
                        if (pkg.isEmpty() || isPhonePePackage(pkg)) continue
                        val match = AppLockMatch(
                            lockKey = pkg,
                            title = "Category Locked: $catName",
                            message = "Ye app [$catName] category mein locked hai. Home screen par jayein ya is category ka password dalein.",
                            password = pwd
                        )
                        locksByPkg[pkg] = match
                        val appLabel = resolveAppLabelLower(pkg)
                        if (appLabel.length >= 3 && !appLabel.contains("phonepe")) {
                            locksByLabel[appLabel] = match
                        }
                    }
                }
            }

            // Individual locked apps
            val indArr = json.optJSONArray("individualLockedApps")
            if (indArr != null) {
                for (i in 0 until indArr.length()) {
                    val obj = indArr.optJSONObject(i) ?: continue
                    val pkg = obj.optString("packageName", "").trim()
                    if (pkg.isEmpty() || isPhonePePackage(pkg)) continue
                    val appName = obj.optString("appName", pkg)
                    val pwd = obj.optString("password", appBlockerPwd)
                    val match = AppLockMatch(
                        lockKey = pkg,
                        title = "$appName Locked Hai",
                        message = "Is app ko kholne ke liye iska alag password dalein, ya Home screen par wapas jayein.",
                        password = pwd
                    )
                    locksByPkg[pkg] = match
                    val lowerName = appName.trim().lowercase(Locale.ROOT)
                    if (lowerName.length >= 3 && !lowerName.contains("phonepe")) {
                        locksByLabel[lowerName] = match
                    }
                }
            }

            ParsedConfig(
                shortsBlockEnabled = json.optBoolean("shortsBlockEnabled", false),
                ytWhitelistEnabled = json.optBoolean("ytWhitelistEnabled", false),
                ytBlacklistEnabled = json.optBoolean("ytBlacklistEnabled", false),
                tamperShieldEnabled = json.optBoolean("tamperShieldEnabled", false),
                geminiBlockEnabled = json.optBoolean("geminiBlockEnabled", false),
                appBlockerEnabled = json.optBoolean("appBlockerEnabled", false),
                shortsPwd = shortsPwd,
                ytAdminPwd = ytAdminPwd,
                tamperPwd = tamperPwd,
                appBlockerPwd = appBlockerPwd,
                blockedKeywords = keywords,
                activeGroupName = activeGroupName,
                activeChannels = activeChannels,
                locksByPkg = locksByPkg,
                locksByLabel = locksByLabel
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveAppLabelLower(pkg: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai)?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
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

    private fun scanNodesUntil(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null) return false
        if (predicate(node)) return true
        for (i in 0 until node.childCount) {
            if (scanNodesUntil(node.getChild(i), predicate)) return true
        }
        return false
    }

    private fun collectNodeIdsAndWatchNode(
        node: AccessibilityNodeInfo?,
        ids: MutableSet<String>,
        inspector: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null) return
        inspector(node)
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { ids.add(it.lowercase(Locale.ROOT)) }
        for (i in 0 until node.childCount) {
            collectNodeIdsAndWatchNode(node.getChild(i), ids, inspector)
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
        for (i in 0 until node.childCount) {
            collectNodeTree(node.getChild(i), texts, viewIds, inspector)
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
