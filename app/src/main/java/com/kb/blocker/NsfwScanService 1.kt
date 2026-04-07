package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

object NsfwScanService {

    private const val TAG              = "NsfwScanService"
    // ★ FIX: ৪ সেকেন্ড → ৫০০ms — detection অনেক দ্রুত হবে
    private const val SCAN_INTERVAL_MS = 500L
    private const val SCAN_COOLDOWN_MS = 2_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor  = Executors.newSingleThreadExecutor()

    @Volatile private var isRunning     = false
    @Volatile private var lastScanTime  = 0L
    @Volatile private var lastBlockTime = 0L

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Started (interval=${SCAN_INTERVAL_MS}ms)")
        scheduleNext(service)
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped")
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private fun scheduleNext(service: AccessibilityService) {
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            doScanOnMainThread(service)
            scheduleNext(service)
        }, SCAN_INTERVAL_MS)
    }

    private fun doScanOnMainThread(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ctx = service.applicationContext

        if (!NsfwModelManager.isEnabled(ctx)) return
        if (!NsfwModelManager.isModelLoaded()) return

        val now = System.currentTimeMillis()
        if (now - lastScanTime  < SCAN_INTERVAL_MS) return
        if (now - lastBlockTime < SCAN_COOLDOWN_MS) return

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) return

        // ★ FIX: শুধু browser বা video app এ AI scan করো — CPU waste কমবে
        val isBrowserOrVideo = KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) } ||
                               KeywordService.VIDEO_PACKAGES.any  { pkg.contains(it) }
        if (!isBrowserOrVideo) return

        // Whitelist check
        val svc = KeywordService.instance
        if (svc != null) {
            svc.refreshWhitelistCache()
            if (svc.whitelistCache.contains(pkg)) return
        } else {
            if (KeywordService.loadWhitelistSet(ctx).contains(pkg)) return
        }

        lastScanTime = now

        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                bgExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        processShot(ctx, shot, pkg)
                    }
                    override fun onFailure(code: Int) {
                        Log.d(TAG, "Screenshot fail: $code")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error: ${e.message}")
        }
    }

    // ── Process — bg thread ───────────────────────────────────────────────────

    private fun processShot(
        ctx: android.content.Context,
        shot: AccessibilityService.ScreenshotResult,
        pkg: String
    ) {
        var bitmap: Bitmap? = null
        try {
            val hw = shot.hardwareBuffer ?: return
            bitmap = Bitmap.wrapHardwareBuffer(hw, null)
                ?.copy(Bitmap.Config.ARGB_8888, false)
            hw.close()
            bitmap ?: return

            val (isAdult, conf) = NsfwModelManager.scan(ctx, bitmap)

            if (isAdult) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime > SCAN_COOLDOWN_MS) {
                    lastBlockTime = now
                    val pct = "%.0f".format(conf * 100)
                    Log.d(TAG, "Adult in $pkg: $pct%")
                    mainHandler.post {
                        KeywordService.instance?.triggerBlock(
                            pkg, "🤖 AI: Adult image ($pct%)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }
}
