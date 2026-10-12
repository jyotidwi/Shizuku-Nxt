package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings

class WatchdogToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The runtime package: a hidden copy answers to its own name, not the build-time one.
        val id = context.packageName
        val enable = when (intent.action) {
            "$id.WATCHDOG_ON" -> true
            "$id.WATCHDOG_OFF" -> false
            "$id.WATCHDOG_TOGGLE" -> !ShizukuSettings.getWatchdog()
            else -> return
        }
        ShizukuSettings.setWatchdog(context, enable)
    }
}
