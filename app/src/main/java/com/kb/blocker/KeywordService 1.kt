package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    internal lateinit var prefs: SharedPreferences
    internal val handler   = Handler(Looper.getMainLooper())
    internal val bgPool    = Executors.newSingleThreadExecutor()
    private var lastBlockTime = 0L

    // ── Whitelist cache — 30s timeout (আগে 3s ছিল, disk read কমবে) ──────────
    internal var whitelistCache     : Set<String> = emptySet()
    internal var whitelistCacheTime  = 0L
    private  val WHITELIST_CACHE_TTL = 30_000L

    // ── Usage tracking ─────────────────────────────────────────────────────────
    private var fgPkg       = ""
    private var fgStartTime = 0L

    // ── Debounce — CONTENT_CHANGED event এর জন্য ──────────────────────────────
    private var contentChangedRunnable: Runnable? = null
    private var pendingCheckPkg = ""

    // ── URL cache — প্রতি event এ full tree scan এড়াতে ─────────────────────
    private var cachedUrl    : String? = null
    private var cachedUrlPkg  = ""

    // ── Notification ──────────────────────────────────────────────────────────
    private val NOTIF_CHANNEL = "blocker_service_channel"
    private val NOTIF_ID      = 1001

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        prefs    = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance = this
        isRunning = true
        refreshWhitelistCache()
        showServiceNotification()

        if (NsfwModelManager.isEnabled(this)) {
            bgPool.execute {
                if (NsfwModelManager.loadModel(this)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        handler.post { NsfwScanService.start(this) }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        flushUsage()
        NsfwScanService.stop()
        NsfwModelManager.unloadModel()
        handler.removeCallbacksAndMessages(null)
        cancelServiceNotification()
        instance  = null
        isRunning = false
    }

    // ── Service Notification ───────────────────────────────────────────────────

    private fun showServiceNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIF_CHANNEL,
                    "Content Blocker Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Service চলছে এই notification দেখাচ্ছে"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("🛡️ Content Blocker চালু আছে")
                .setContentText("Adult content থেকে সুরক্ষিত")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()

            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    private fun cancelServiceNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    // ── Whitelist cache ────────────────────────────────────────────────────────

    internal fun refreshWhitelistCache() {
        val now = System.currentTimeMillis()
        if (now - whitelistCacheTime > WHITELIST_CACHE_TTL) {
            whitelistCache    = loadWhitelistSet(this)
            whitelistCacheTime = now
        }
    }

    // ── Accessibility Events ───────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Foreground tracking
                if (!isSystemPkg(pkg)) {
                    if (fgPkg != pkg) {
                        flushUsage()
                        fgPkg       = pkg
                        fgStartTime = System.currentTimeMillis()
                    }
                    currentForegroundPkg = pkg
                }
                // URL cache clear — নতুন window এলে পুরনো URL invalid
                cachedUrl    = null
                cachedUrlPkg = ""

                // Window state change → immediate check (delay ছাড়া)
                processCheck(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // ★ FIX: Debounce — ১৫০ms এ একবার মাত্র check হবে
                // প্রতি millisecond এ আসা events handle করা CPU waste ছিল
                contentChangedRunnable?.let { handler.removeCallbacks(it) }
                pendingCheckPkg = pkg
                val r = Runnable { processCheck(pendingCheckPkg) }
                contentChangedRunnable = r
                handler.postDelayed(r, 150L)
            }
        }
    }

    // ── Main check logic ───────────────────────────────────────────────────────

    private fun processCheck(pkg: String) {
        if (pkg.isBlank()) return

        refreshWhitelistCache()
        if (whitelistCache.contains(currentForegroundPkg)) return
        if (whitelistCache.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return

        // ── 1. Known adult app ─────────────────────────────────────────────────
        if (isKnownAdultApp(pkg)) {
            block(pkg, "🔞 Known adult app"); return
        }

        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)
        val root = rootInActiveWindow

        // ── 2. Schedule check ──────────────────────────────────────────────────
        if (isBrowserOrVideo && !ScheduleManager.isCurrentlyAllowed(this)) {
            block(pkg, "⏰ সময়সীমার বাইরে"); return
        }

        // ── 3. Usage limit ─────────────────────────────────────────────────────
        if (UsageLimitManager.isLimitExceeded(this, pkg)) {
            block(pkg, "⏱️ দৈনিক সীমা শেষ"); return
        }

        // ── 4. Browser URL check ───────────────────────────────────────────────
        if (isBrowser(pkg)) {
            val url = getCachedUrl(root, pkg)
            if (!url.isNullOrBlank()) {
                if (isHardAdultUrl(url)) { block(pkg, "🔞 Adult site"); return }
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    block(pkg, "🌶️ Suggestive search"); return
                }
            }
        }

        // ── 5. Video metadata ──────────────────────────────────────────────────
        if (isBrowserOrVideo && isVideoMetaEnabled(this)) {
            val meta = extractVideoMetadata(root)
            if (meta.isNotBlank() && VideoMetaDetector.isAdultMeta(meta)) {
                block(pkg, "🎬 Adult video content"); return
            }
        }

        // ── 6. Screen text ─────────────────────────────────────────────────────
        // ★ FIX: depth limit 8, text limit 3000 chars — বড় page এ CPU spike আটকাবে
        val sb = StringBuilder()
        collectText(root, sb, 0)
        val screenText = sb.toString()
        if (screenText.isBlank()) return

        val userKeywords = getUserKeywords()
        if (userKeywords.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            val hit   = userKeywords.firstOrNull {
                it.isNotBlank() && lower.contains(it.lowercase(Locale.getDefault()))
            }
            if (hit != null) { block(pkg, "🚫 Keyword: \"$hit\""); return }
        }

        if (isAdultTextDetectEnabled(this) && AdultContentDetector.isAdultContent(screenText)) {
            block(pkg, "🔞 Adult text"); return
        }

        if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
            SoftAdultDetector.isSoftAdultContent(screenText)) {
            block(pkg, "🌶️ Suggestive content")
        }
    }

    // ── Block trigger ──────────────────────────────────────────────────────────

    internal fun triggerBlock(pkg: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return
        lastBlockTime = now
        block(pkg, reason)
    }

    private fun block(pkg: String, reason: String) {
        lastBlockTime = System.currentTimeMillis()
        val label = getAppLabel(pkg)
        BlockLogManager.log(this, pkg, reason)
        closeAndKillPkg(pkg)
        BlockedActivity.launch(this, label, reason)
    }

    // ── Kill app ───────────────────────────────────────────────────────────────

    fun closeAndKillPkg(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        fun kill() = try {
            am.appTasks.forEach { task ->
                if (task.taskInfo.baseActivity?.packageName == pkg)
                    task.finishAndRemoveTask()
            }
            am.killBackgroundProcesses(pkg)
        } catch (_: Exception) {}
        kill()
        handler.postDelayed(::kill, 400)
    }

    // ── URL cache ──────────────────────────────────────────────────────────────

    /** ★ FIX: URL cache — same pkg এ বারবার full tree scan না করে cached value return */
    private fun getCachedUrl(root: AccessibilityNodeInfo?, pkg: String): String? {
        if (pkg == cachedUrlPkg && cachedUrl != null) return cachedUrl
        val url = extractUrl(root)
        cachedUrl    = url
        cachedUrlPkg = pkg
        return url
    }

    // ── Video metadata ─────────────────────────────────────────────────────────

    private fun extractVideoMetadata(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        val ids = setOf(
            "title", "video_title", "media_title", "item_title",
            "content_title", "player_title", "description",
            "video_description", "channel_name", "author_name",
            "tag", "hashtag", "caption", "subtitle",
            "watch_title", "media_caption", "reel_title",
            "video_desc", "author_username", "story_title"
        )
        collectMetaDeep(root, ids, sb, 0)
        return sb.toString()
    }

    private fun collectMetaDeep(
        node: AccessibilityNodeInfo?, ids: Set<String>,
        sb: StringBuilder, depth: Int
    ) {
        if (node == null || depth > 10) return
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""

        if (ids.any { resId.contains(it) }) {
            if (text.isNotBlank()) sb.append(text).append(" ")
            if (desc.isNotBlank()) sb.append(desc).append(" ")
        }
        if (text.contains("#") || text.startsWith("@"))
            sb.append(text).append(" ")
        if (text.length in 10..200 && depth <= 4)
            sb.append(text).append(" ")

        for (i in 0 until node.childCount)
            collectMetaDeep(node.getChild(i), ids, sb, depth + 1)
    }

    // ── URL extraction ─────────────────────────────────────────────────────────

    private fun extractUrl(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        if (node.className?.contains("EditText") == true ||
            resId.contains("url") || resId.contains("address") ||
            resId.contains("omnibox") || resId.contains("search")) {
            val text = node.text?.toString() ?: ""
            if (text.contains(".") && (text.startsWith("http") ||
                text.contains("www.") || text.matches(Regex(".*\\.[a-z]{2,}.*"))))
                return text
        }
        for (i in 0 until node.childCount)
            extractUrl(node.getChild(i))?.let { return it }
        return null
    }

    private fun isHardAdultUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        return ADULT_DOMAINS.any { lower.contains(it) } ||
               ADULT_KEYWORDS_URL.any { lower.contains(it) }
    }

    // ── Usage tracking ─────────────────────────────────────────────────────────

    private fun flushUsage() {
        if (fgPkg.isNotBlank() && fgStartTime > 0) {
            val secs = (System.currentTimeMillis() - fgStartTime) / 1000
            if (secs > 0) UsageLimitManager.addUsage(this, fgPkg, secs)
            fgStartTime = 0L
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * ★ FIX: depth limit 8, text limit 3000 chars
     * আগে unlimited ছিল — বড় page এ হাজার হাজার node traverse করতো
     */
    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 8 || sb.length > 3000) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount)
            collectText(node.getChild(i), sb, depth + 1)
    }

    private fun getUserKeywords(): List<String> =
        (prefs.getString(KEY_WORDS, "") ?: "")
            .split(DELIMITER).filter { it.isNotBlank() }

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }

    private fun isSystemPkg(pkg: String) =
        pkg == "android" || pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isBrowser(pkg: String)       = BROWSER_PACKAGES.any { pkg.contains(it) }
    private fun isVideoApp(pkg: String)      = VIDEO_PACKAGES.any  { pkg.contains(it) }
    private fun isKnownAdultApp(pkg: String) = KNOWN_ADULT_APPS.any { pkg.contains(it) }

    // ── Companion ──────────────────────────────────────────────────────────────

    companion object {
        const val PREFS          = "kb_prefs"
        const val KEY_WORDS      = "keywords"
        const val KEY_ENABLED    = "enabled"
        const val KEY_WHITELIST  = "whitelist"
        const val KEY_ADULT_TEXT = "adult_text"
        const val KEY_SOFT_ADULT = "soft_adult"
        const val KEY_VIDEO_META = "video_meta"
        const val DELIMITER      = "|||"

        var instance           : KeywordService? = null
        var isRunning            = false
        var currentForegroundPkg = ""

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
            "com.brave.browser", "com.kiwibrowser.browser", "com.sec.android.app.sbrowser",
            "com.UCMobile.intl", "com.uc.browser.en", "com.mi.globalbrowser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser",
            "com.ecosia.android", "com.yandex.browser"
        )
        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube", "com.instagram.android",
            "com.facebook.katana", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.reddit.frontpage", "com.pinterest", "com.snapchat.android",
            "tv.twitch.android.app", "com.mx.player", "org.videolan.vlc",
            "com.mxtech.videoplayer.ad", "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp"
        )
        val KNOWN_ADULT_APPS = setOf(
            "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
            "tube8", "spankbang", "brazzers", "onlyfans", "faphouse",
            "chaturbate", "stripchat", "bongacams", "badoo", "adultfriendfinder",
            "sexcam", "livejasmin", "camsoda", "hentai", "nhentai", "e-hentai"
        )
        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "eporner.com", "tnaflix.com", "beeg.com", "drtuber.com",
            "hqporner.com", "4tube.com", "porntrex.com", "brazzers.com",
            "bangbros.com", "naughtyamerica.com", "realitykings.com",
            "mofos.com", "kink.com", "onlyfans.com", "fansly.com",
            "manyvids.com", "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "camsoda.com", "cam4.com", "myfreecams.com",
            "flirt4free.com", "rule34.xxx", "gelbooru.com", "e-hentai.org",
            "nhentai.net", "hentaihaven.xxx"
        )
        val ADULT_KEYWORDS_URL = setOf(
            "/porn", "/sex", "/xxx", "/nude", "/naked",
            "/hentai", "/nsfw", "/adult", "porn", "xvideo", "xnxx"
        )

        private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveWhitelist(ctx: Context, list: List<String>) {
            p(ctx).edit().putString(KEY_WHITELIST,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
            instance?.let {
                it.whitelistCacheTime = 0L
                it.refreshWhitelistCache()
            }
        }
        fun loadWhitelist(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WHITELIST, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }
        fun loadWhitelistSet(ctx: Context): Set<String> {
            val raw = p(ctx).getString(KEY_WHITELIST, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toHashSet()
        }
        fun isWhitelisted(ctx: Context, pkg: String) = loadWhitelistSet(ctx).contains(pkg)

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WORDS,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
        fun loadKeywords(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WORDS, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }

        fun setEnabled(ctx: Context, v: Boolean)         = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
        fun isEnabled(ctx: Context)                      = p(ctx).getBoolean(KEY_ENABLED, true)
        fun setAdultTextDetect(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()
        fun isAdultTextDetectEnabled(ctx: Context)       = p(ctx).getBoolean(KEY_ADULT_TEXT, true)
        fun setSoftAdult(ctx: Context, v: Boolean)       = p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()
        fun isSoftAdultEnabled(ctx: Context)             = p(ctx).getBoolean(KEY_SOFT_ADULT, true)
        fun setVideoMeta(ctx: Context, v: Boolean)       = p(ctx).edit().putBoolean(KEY_VIDEO_META, v).apply()
        fun isVideoMetaEnabled(ctx: Context)             = p(ctx).getBoolean(KEY_VIDEO_META, true)
    }
}

// ── Extension functions ────────────────────────────────────────────────────────

/**
 * ★ FIX: নতুন Executor তৈরি না করে existing bgPool use করো — executor leak ঠিক
 */
fun KeywordService.onNsfwModelChanged() {
    if (NsfwModelManager.isEnabled(this)) {
        if (!NsfwModelManager.isModelLoaded()) {
            bgPool.execute {
                if (NsfwModelManager.loadModel(this)) {
                    handler.post {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            NsfwScanService.start(this)
                    }
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                NsfwScanService.start(this)
        }
    } else {
        NsfwScanService.stop()
    }
}

fun KeywordService.requestTestScan() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        android.widget.Toast.makeText(this,
            "Test scan needs Android 11+", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val svc = this
    handler.post {
        try {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        bgPool.execute {
                            var bitmap: android.graphics.Bitmap? = null
                            try {
                                val hw = shot.hardwareBuffer ?: return@execute
                                bitmap = android.graphics.Bitmap
                                    .wrapHardwareBuffer(hw, null)
                                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                hw.close()
                                bitmap ?: return@execute

                                val (isAdult, conf) = NsfwModelManager.scan(svc, bitmap)
                                val detailed        = NsfwModelManager.scanDetailed(svc, bitmap)
                                val threshold       = NsfwModelManager.getThreshold(svc)

                                handler.post {
                                    // ★ FIX: Toast এর বদলে AlertDialog — দ্রুত মিলিয়ে যায় না
                                    val msg = buildString {
                                        append("Adult: ${if (isAdult) "✅ YES" else "❌ NO"}\n")
                                        append("Score: ${"%.1f".format(conf * 100)}%\n")
                                        append("Threshold: ${"%.0f".format(threshold * 100)}%\n\n")
                                        detailed?.entries
                                            ?.sortedByDescending { it.value }
                                            ?.forEach { (lbl, score) ->
                                                append("$lbl: ${"%.1f".format(score * 100)}%\n")
                                            }
                                    }
                                    android.app.AlertDialog.Builder(svc)
                                        .setTitle("🔬 AI Test Result")
                                        .setMessage(msg)
                                        .setPositiveButton("বন্ধ করো", null)
                                        .show()
                                }
                            } catch (e: Exception) {
                                handler.post {
                                    android.widget.Toast.makeText(
                                        svc, "Test failed: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } finally {
                                try { bitmap?.recycle() } catch (_: Exception) {}
                            }
                        }
                    }
                    override fun onFailure(code: Int) {
                        android.widget.Toast.makeText(
                            svc, "Screenshot failed (code $code)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                svc, "takeScreenshot error: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
