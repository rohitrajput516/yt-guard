package com.rohit.ytguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.rohit.ytguard/control"
    private val PREFS_NAME = "ytguard_prefs"
    private val KEY_CONFIG = "config_json"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "saveConfig" -> {
                        val json = call.argument<String>("json") ?: "{}"
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CONFIG, json)
                            .commit()
                        result.success(true)
                    }
                    "loadConfig" -> {
                        val json = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_CONFIG, null)
                        result.success(json)
                    }
                    "isAccessibilityEnabled" -> {
                        result.success(isAccessibilityServiceEnabled())
                    }
                    "openAccessibilitySettings" -> {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    }
                    "getInstalledApps" -> {
                        result.success(fetchLaunchableApps())
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${packageName}/${YouTubeGuardService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val service = splitter.next()
            if (service.equals(expected, ignoreCase = true) ||
                service.endsWith("/.YouTubeGuardService", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private fun fetchLaunchableApps(): List> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        val list = ArrayList>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName || seen.contains(pkg)) continue
            seen.add(pkg)
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            list.add(mapOf("packageName" to pkg, "appName" to label))
        }
        list.sortBy { it["appName"]?.lowercase() ?: "" }
        return list
    }
}
