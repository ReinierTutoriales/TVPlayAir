package com.tvplayair.receiver.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts LAN discovery after boot or an app update.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFS = "receiver_startup"
        private const val AUTO_START = "auto_start_receiver"

        internal fun isAutoStartEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)

        internal fun setAutoStartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(AUTO_START, enabled).apply()
        }

        internal fun shouldStartReceiver(action: String?, enabled: Boolean): Boolean =
            enabled && (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (shouldStartReceiver(action, isAutoStartEnabled(context))) {
            runCatching {
                SessionService.startInBackground(context)
            }.onFailure {
                Log.w("BootReceiver", "Failed to auto-start SessionService on $action", it)
            }
        }
    }
}
