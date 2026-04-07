package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Block history log — কখন, কোন app, কী কারণে block হয়েছে
 * Last 200 entry রাখে, তারপর পুরনো মুছে ফেলে
 */
object BlockLogManager {

    private const val PREFS   = "block_log_prefs"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 200

    data class LogEntry(
        val time: Long,
        val pkg: String,
        val appLabel: String,
        val reason: String
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun log(ctx: Context, pkg: String, reason: String) {
        val label = try {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }

        val entry = JSONObject().apply {
            put("time",   System.currentTimeMillis())
            put("pkg",    pkg)
            put("label",  label)
            put("reason", reason)
        }

        val raw  = prefs(ctx).getString(KEY_LOG, "[]") ?: "[]"
        val arr  = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        arr.put(entry)

        // MAX_LOG এর বেশি হলে পুরনো মুছো
        val trimmed = if (arr.length() > MAX_LOG) {
            val newArr = JSONArray()
            for (i in (arr.length() - MAX_LOG) until arr.length())
                newArr.put(arr.get(i))
            newArr
        } else arr

        prefs(ctx).edit().putString(KEY_LOG, trimmed.toString()).apply()
    }

    fun getAll(ctx: Context): List<LogEntry> {
        val raw = prefs(ctx).getString(KEY_LOG, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<LogEntry>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            list.add(LogEntry(
                time     = o.getLong("time"),
                pkg      = o.getString("pkg"),
                appLabel = o.optString("label", o.getString("pkg")),
                reason   = o.getString("reason")
            ))
        }
        return list
    }

    fun getTodayCount(ctx: Context): Int {
        val startOfDay = run {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.timeInMillis
        }
        return getAll(ctx).count { it.time >= startOfDay }
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().putString(KEY_LOG, "[]").apply()
    }

    fun formatTime(time: Long): String =
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(time))
}
