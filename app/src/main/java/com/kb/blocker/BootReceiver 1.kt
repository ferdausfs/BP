package com.kb.blocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Phone restart হলে এই receiver চালু হয়।
 * Accessibility service বন্ধ থাকলে user কে notification দেয়।
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

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
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // ★ FIX: NotificationCompat ব্যবহার করো — deprecated Notification.Builder এর বদলে
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "boot_reminder",
                    "Service Reminder",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(ctx, "boot_reminder")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("⚠️ Content Blocker")
                .setContentText("Accessibility Service বন্ধ। চালু করতে tap করো।")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(1002, notif)
        } catch (_: Exception) {}
    }
}
