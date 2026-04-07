package com.kb.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Phone restart হলে এই receiver চালু হয়।
 * Accessibility service টা Android restart এ automatically চালু থাকে
 * যদি user আগে enable করে থাকে — কিন্তু কিছু OEM এ বন্ধ হয়।
 * এই receiver user কে reminder দেয় যদি service বন্ধ থাকে।
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        // Accessibility service চালু আছে কিনা check করো
        // যদি বন্ধ থাকে তাহলে notification দেখাও
        if (!isAccessibilityEnabled(context)) {
            showReminderNotification(context)
        }
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val service = "${ctx.packageName}/.KeywordService"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }

    private fun showReminderNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

            // Android 8+ এ channel লাগে
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "boot_reminder",
                    "Service Reminder",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }

            val pendingIntent = android.app.PendingIntent.getActivity(
                ctx, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notif = android.app.Notification.Builder(ctx, "boot_reminder")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("⚠️ Content Blocker")
                .setContentText("Accessibility Service বন্ধ। চালু করতে tap করো।")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(1001, notif)
        } catch (_: Exception) {}
    }
}
