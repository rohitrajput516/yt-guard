package com.rohit.ytguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Verify your Tamper Password inside YT Guard before disabling Uninstall Protection."
    }
}
