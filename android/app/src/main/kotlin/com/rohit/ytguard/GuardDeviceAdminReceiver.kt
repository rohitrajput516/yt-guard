package com.rohit.ytguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "YT Guard ka Uninstall Shield band karne se pehle Tamper Password verify karna zaroori hai."
    }
}
