package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastBlockTime = 0L

    override fun onServiceConnected() {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance = this
        isRunning = true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        instance = null
        isRunning = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isSystemPkg(pkg)) currentForegroundPkg = pkg
        }

        if (isWhitelisted(this, pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return

        // ── 1. Known adult app → সাথে সাথে block ─────────────────────────
        if (isKnownAdultApp(pkg)) {
            lastBlockTime = now
            closeAndKillPkg(pkg)
            return
        }

        val screenText = buildString { collectText(rootInActiveWindow, this) }
        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)

        // ── 2. Browser: URL check ──────────────────────────────────────────
        if (isBrowser(pkg)) {
            val url = extractUrl(rootInActiveWindow)
            if (!url.isNullOrBlank()) {
                // Hard adult URL
                if (isAdultUrl(url)) {
                    lastBlockTime = now
                    closeAndKillPkg(pkg)
                    return
                }
                // Soft adult URL (search query)
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    lastBlockTime = now
                    closeAndKillPkg(pkg)
                    return
                }
            }
        }

        if (screenText.isBlank()) return

        // ── 3. User keywords → সব app-এ চেক ──────────────────────────────
        val userKeywords = getUserKeywords()
        if (userKeywords.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            if (userKeywords.any { it.isNotBlank() && lower.contains(it.lowercase(Locale.getDefault())) }) {
                lastBlockTime = now
                closeAndKillPkg(pkg)
                return
            }
        }

        // ── 4. Hard adult text → সব app-এ চেক ────────────────────────────
        if (isAdultTextDetectEnabled(this)) {
            if (AdultContentDetector.isAdultContent(screenText)) {
                lastBlockTime = now
                closeAndKillPkg(pkg)
                return
            }
        }

        // ── 5. Soft adult keywords → শুধু browser + video app-এ ──────────
        if (isSoftAdultEnabled(this) && isBrowserOrVideo) {
            if (SoftAdultDetector.isSoftAdultContent(screenText)) {
                lastBlockTime = now
                closeAndKillPkg(pkg)
                return
            }
        }
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    private fun extractUrl(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        if (node.className?.contains("EditText") == true ||
            node.viewIdResourceName?.contains("url") == true ||
            node.viewIdResourceName?.contains("address") == true ||
            node.viewIdResourceName?.contains("omnibox") == true ||
            node.viewIdResourceName?.contains("search") == true
        ) {
            val text = node.text?.toString() ?: ""
            if (text.contains(".") && (
                text.startsWith("http") ||
                text.contains("www.") ||
                text.matches(Regex(".*\\.[a-z]{2,}.*"))
            )) return text
        }
        for (i in 0 until node.childCount) {
            val found = extractUrl(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun isAdultUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        return ADULT_DOMAINS.any { lower.contains(it) } ||
               ADULT_KEYWORDS_URL.any { lower.contains(it) }
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    fun closeAndKillPkg(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            am.appTasks.forEach { task ->
                if (task.taskInfo.baseActivity?.packageName == pkg)
                    task.finishAndRemoveTask()
            }
        } catch (e: Exception) { }
        am.killBackgroundProcesses(pkg)
        handler.postDelayed({
            try {
                am.appTasks.forEach { task ->
                    if (task.taskInfo.baseActivity?.packageName == pkg)
                        task.finishAndRemoveTask()
                }
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) { }
        }, 300)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    private fun getUserKeywords(): List<String> {
        val raw = prefs.getString(KEY_WORDS, "") ?: return emptyList()
        return raw.split(DELIMITER).filter { it.isNotBlank() }
    }

    private fun isSystemPkg(pkg: String): Boolean =
        pkg == "android" ||
        pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") ||
        pkg.contains("keyboard")

    private fun isBrowser(pkg: String): Boolean =
        BROWSER_PACKAGES.any { pkg.contains(it) }

    private fun isVideoApp(pkg: String): Boolean =
        VIDEO_PACKAGES.any { pkg.contains(it) }

    private fun isKnownAdultApp(pkg: String): Boolean =
        KNOWN_ADULT_APPS.any { pkg.contains(it) }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val PREFS            = "kb_prefs"
        const val KEY_WORDS        = "keywords"
        const val KEY_ENABLED      = "enabled"
        const val KEY_WHITELIST    = "whitelist"
        const val KEY_ADULT_TEXT   = "adult_text"
        const val KEY_SOFT_ADULT   = "soft_adult"
        const val DELIMITER        = "|||"

        var instance             : KeywordService? = null
        var isRunning              = false
        var currentForegroundPkg   = ""

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox",
            "org.mozilla.firefox_beta", "com.microsoft.emmx",
            "com.opera.browser", "com.opera.mini.native",
            "com.brave.browser", "com.kiwibrowser.browser",
            "com.sec.android.app.sbrowser", "com.UCMobile.intl",
            "com.uc.browser.en", "com.mi.globalbrowser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser",
            "com.ecosia.android", "com.yandex.browser"
        )

        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.snapchat.android",
            "tv.twitch.android.app",
            "com.mx.player",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.playerxtreme.playerx",
            "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp"
        )

        val KNOWN_ADULT_APPS = setOf(
            "pornhub", "xvideos", "xnxx", "xhamster",
            "redtube", "youporn", "tube8", "spankbang",
            "brazzers", "onlyfans", "faphouse",
            "chaturbate", "stripchat", "bongacams",
            "badoo", "adultfriendfinder",
            "sexcam", "livejasmin", "camsoda",
            "hentai", "nhentai", "e-hentai"
        )

        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "eporner.com", "tnaflix.com", "beeg.com", "drtuber.com",
            "hqporner.com", "4tube.com", "porntrex.com",
            "brazzers.com", "bangbros.com", "naughtyamerica.com",
            "realitykings.com", "mofos.com", "kink.com",
            "onlyfans.com", "fansly.com", "manyvids.com",
            "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "camsoda.com", "cam4.com",
            "myfreecams.com", "flirt4free.com",
            "rule34.xxx", "gelbooru.com", "e-hentai.org",
            "nhentai.net", "hentaihaven.xxx"
        )

        val ADULT_KEYWORDS_URL = setOf(
            "/porn", "/sex", "/xxx", "/nude", "/naked",
            "/hentai", "/nsfw", "/adult",
            "porn", "xvideo", "xnxx"
        )

        private fun p(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WORDS, list.joinToString(DELIMITER)).apply()

        fun loadKeywords(ctx: Context): MutableList<String> =
            (p(ctx).getString(KEY_WORDS, "") ?: "")
                .split(DELIMITER).filter { it.isNotBlank() }.toMutableList()

        fun saveWhitelist(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WHITELIST, list.joinToString(DELIMITER)).apply()

        fun loadWhitelist(ctx: Context): MutableList<String> =
            (p(ctx).getString(KEY_WHITELIST, "") ?: "")
                .split(DELIMITER).filter { it.isNotBlank() }.toMutableList()

        fun isWhitelisted(ctx: Context, pkg: String): Boolean =
            (p(ctx).getString(KEY_WHITELIST, "") ?: "")
                .split(DELIMITER).contains(pkg)

        fun setEnabled(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

        fun isEnabled(ctx: Context): Boolean =
            p(ctx).getBoolean(KEY_ENABLED, true)

        fun setAdultTextDetect(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()

        fun isAdultTextDetectEnabled(ctx: Context): Boolean =
            p(ctx).getBoolean(KEY_ADULT_TEXT, true)

        fun setSoftAdult(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()

        fun isSoftAdultEnabled(ctx: Context): Boolean =
            p(ctx).getBoolean(KEY_SOFT_ADULT, true)
    }
}
