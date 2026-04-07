package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-app daily usage limit
 * নির্দিষ্ট সময়ের বেশি app ব্যবহার করলে block
 */
object UsageLimitManager {

    private const val PREFS         = "usage_prefs"
    private const val KEY_ENABLED   = "usage_enabled"
    private const val KEY_LIMITS    = "limits"      // JSON: {pkg: minutes}
    private const val KEY_USAGE     = "usage_today" // JSON: {pkg: seconds_used}
    private const val KEY_LAST_DATE = "last_date"   // today's date string

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    // Daily reset check
    private fun checkAndResetDaily(ctx: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val last  = prefs(ctx).getString(KEY_LAST_DATE, "") ?: ""
        if (last != today) {
            prefs(ctx).edit()
                .putString(KEY_LAST_DATE, today)
                .putString(KEY_USAGE, "{}")
                .apply()
        }
    }

    // Limit set করো (minutes)
    fun setLimit(ctx: Context, pkg: String, minutes: Int) {
        val limits = getLimitsMap(ctx).toMutableMap()
        limits[pkg] = minutes
        saveLimitsMap(ctx, limits)
    }

    fun removeLimit(ctx: Context, pkg: String) {
        val limits = getLimitsMap(ctx).toMutableMap()
        limits.remove(pkg)
        saveLimitsMap(ctx, limits)
    }

    fun getLimitMinutes(ctx: Context, pkg: String): Int =
        getLimitsMap(ctx)[pkg] ?: -1

    fun getAllLimits(ctx: Context): Map<String, Int> = getLimitsMap(ctx)

    // Usage track করো (seconds)
    fun addUsage(ctx: Context, pkg: String, seconds: Long) {
        checkAndResetDaily(ctx)
        val usage = getUsageMap(ctx).toMutableMap()
        usage[pkg] = (usage[pkg] ?: 0L) + seconds
        saveUsageMap(ctx, usage)
    }

    fun getUsedSeconds(ctx: Context, pkg: String): Long {
        checkAndResetDaily(ctx)
        return getUsageMap(ctx)[pkg] ?: 0L
    }

    fun isLimitExceeded(ctx: Context, pkg: String): Boolean {
        if (!isEnabled(ctx)) return false
        val limitMins = getLimitMinutes(ctx, pkg)
        if (limitMins < 0) return false
        val usedSecs = getUsedSeconds(ctx, pkg)
        return usedSecs >= limitMins * 60L
    }

    fun getRemainingMinutes(ctx: Context, pkg: String): Int {
        val limitMins = getLimitMinutes(ctx, pkg)
        if (limitMins < 0) return -1
        val usedMins  = (getUsedSeconds(ctx, pkg) / 60).toInt()
        return (limitMins - usedMins).coerceAtLeast(0)
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun getLimitsMap(ctx: Context): Map<String, Int> {
        val raw = prefs(ctx).getString(KEY_LIMITS, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(raw)
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { map[it] = obj.getInt(it) }
            map
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveLimitsMap(ctx: Context, map: Map<String, Int>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(ctx).edit().putString(KEY_LIMITS, obj.toString()).apply()
    }

    private fun getUsageMap(ctx: Context): Map<String, Long> {
        val raw = prefs(ctx).getString(KEY_USAGE, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            obj.keys().forEach { map[it] = obj.getLong(it) }
            map
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveUsageMap(ctx: Context, map: Map<String, Long>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(ctx).edit().putString(KEY_USAGE, obj.toString()).apply()
    }
}
