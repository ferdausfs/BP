package com.kb.blocker

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Notification Listener — YouTube, Facebook, Instagram ইত্যাদি
 * video চলার সময় notification এ title/description পাঠায়।
 * সেখান থেকে adult content ধরা যায় যা screen এ text হিসেবে নেই।
 */
class VideoNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        val isTarget = KeywordService.VIDEO_PACKAGES.any { pkg.contains(it) } ||
                       KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) }
        if (!isTarget) return

        if (KeywordService.isWhitelisted(this, pkg)) return
        if (!KeywordService.isEnabled(this)) return
        if (!KeywordService.isVideoMetaEnabled(this)) return

        val notif   = sbn.notification ?: return
        val extras  = notif.extras    ?: return

        val title   = extras.getString("android.title") ?: ""
        val text    = extras.getString("android.text")  ?: ""
        val bigText = extras.getString("android.bigText") ?: ""
        val subText = extras.getString("android.subText") ?: ""

        val combined = "$title $text $bigText $subText"
        if (combined.isBlank()) return

        if (VideoMetaDetector.isAdultMeta(combined)) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                KeywordService.instance?.let { svc ->
                    // ★ FIX: closeAndKillPkg এর পরে BlockedActivity দেখাও
                    // আগে শুধু kill করতো, user বুঝতে পারতো না কেন বন্ধ হলো
                    svc.closeAndKillPkg(pkg)
                    val appLabel = try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Exception) { pkg }
                    BlockedActivity.launch(svc, appLabel, "📱 Adult notification detected")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
