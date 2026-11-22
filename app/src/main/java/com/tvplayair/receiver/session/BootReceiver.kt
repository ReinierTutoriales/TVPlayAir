package com.tvplayair.receiver.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts discovery at boot where Android permits a media-playback foreground service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED && Build.VERSION.SDK_INT >= 35 &&
            context.applicationInfo.targetSdkVersion >= 35
        ) return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching {
                SessionService.startInBackground(context)
            }.onFailure {
                Log.w("BootReceiver", "Failed to auto-start SessionService on $action", it)
            }
        }
    }
}
