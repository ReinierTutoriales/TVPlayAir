package com.tvplayair.receiver.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the isolated :session foreground service automatically on Android TV boot
 * or app update so AirPlay discovery stays active without manually opening the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching {
                SessionService.startInBackground(context)
            }.onFailure {
                Log.w("BootReceiver", "Failed to auto-start SessionService on $action", it)
            }
        }
    }
}
