package com.rohit.ytguard

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

typealias AppInfoMap = Map<String, String>

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
                        GuardRuntimeState.bumpConfigVersion()
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
                    "isOverlayEnabled" -> {
                        result.success(Settings.canDrawOverlays(this))
                    }
                    "openOverlaySettings" -> {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    }
                    "isUsageAccessEnabled" -> {
                        result.success(isUsageStatsGranted())
                    }
                    "openUsageAccessSettings" -> {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    }
                    "isBatteryOptimizationIgnored" -> {
                        result.success(isBatteryUnrestricted())
                    }
                    "requestIgnoreBatteryOptimization" -> {
                        try {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } catch (_: Exception) {
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(fallback)
                        }
                        result.success(true)
                    }
                    "isDeviceAdminEnabled" -> {
                        result.success(isAdminActive())
                    }
                    "requestDeviceAdmin" -> {
                        val comp = ComponentName(this, GuardDeviceAdminReceiver::class.java)
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Ye sirf YT Guard ka Uninstall button lock karne ke liye hai. Koi wipe ya phone lock permission nahi hai."
                            )
                        }
                        startActivity(intent)
                        result.success(true)
                    }
                    "removeDeviceAdmin" -> {
                        try {
                            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val comp = ComponentName(this, GuardDeviceAdminReceiver::class.java)
                            if (dpm.isAdminActive(comp)) {
                                dpm.removeActiveAdmin(comp)
                            }
                            result.success(true)
                        } catch (_: Exception) {
                            result.success(false)
                        }
                    }
                    "getInstalledApps" -> {
                        result.success(fetchLaunchableApps())
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isBatteryUnrestricted(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun isAdminActive(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, GuardDeviceAdminReceiver::class.java)
            dpm.isAdminActive(comp)
        } catch (_: Exception) {
            false
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

    private fun isUsageStatsGranted(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchLaunchableApps(): List<AppInfoMap> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        val list = ArrayList<AppInfoMap>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val lowerPkg = pkg.lowercase()
            // Hard-exclude YT Guard itself and PhonePe
            if (pkg == packageName || lowerPkg == "com.phonepe.app" || lowerPkg.startsWith("com.phonepe.") || seen.contains(pkg)) {
                continue
            }
            seen.add(pkg)
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            list.add(mapOf("packageName" to pkg, "appName" to label))
        }
        list.sortBy { it["appName"]?.lowercase() ?: "" }
        return list
    }
}
