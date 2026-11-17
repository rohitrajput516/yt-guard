package com.rohit.ytguard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.rohit.ytguard/channel"
    private val PREFS_NAME = "ytguard_prefs"
    private val KEY_CONFIG = "config_json"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                when (call.method) {
                    "getState" -> {
                        val configJson = prefs.getString(KEY_CONFIG, null)
                        val isServiceEnabled = isAccessibilityServiceEnabled()
                        val response = mapOf(
                            "configJson" to configJson,
                            "isServiceEnabled" to isServiceEnabled
                        )
                        result.success(response)
                    }
                    "saveConfig" -> {
                        val configJson = call.argument<String>("configJson")
                        if (configJson != null) {
                            prefs.edit().putString(KEY_CONFIG, configJson).commit()
                            result.success(true)
                        } else {
                            result.error("INVALID_ARG", "Config JSON is null", null)
                        }
                    }
                    "openAccessibilitySettings" -> {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${YouTubeGuardService::class.java.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
