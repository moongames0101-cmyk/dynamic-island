package com.moongames.dynamicisland

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/** Asks Android to re-attach our notification listener (it gets dropped after updates and reboots). */
fun requestListenerRebind(ctx: Context) {
    try {
        NotificationListenerService.requestRebind(ComponentName(ctx, NotifListener::class.java))
    } catch (e: Exception) {
    }
}

/**
 * After the phone restarts or the app updates itself, make sure notifications reconnect.
 * (The island overlay itself is an accessibility service, which Android restarts on its own.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Diag.add("Reconnecting after " + (if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "update" else "restart"))
                requestListenerRebind(context)
            }
        }
    }
}

/** The island's own timer reached zero. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = IslandService.instance
        if (s != null) s.timerFired()
    }
}
