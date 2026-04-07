package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Time-based blocking schedule
 * নির্দিষ্ট সময়ের বাইরে browser/video block হবে
 */
object ScheduleManager {

    private const val PREFS         = "schedule_prefs"
    private const val KEY_ENABLED   = "schedule_enabled"
    private const val KEY_START_H   = "start_hour"
    private const val KEY_START_M   = "start_min"
    private const val KEY_END_H     = "end_hour"
    private const val KEY_END_M     = "end_min"

    // Default: সকাল ৮টা থেকে রাত ৯টা — allowed
    private const val DEFAULT_START_H = 8
    private const val DEFAULT_START_M = 0
    private const val DEFAULT_END_H   = 21
    private const val DEFAULT_END_M   = 0

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isScheduleEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setScheduleEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun getStartTime(ctx: Context): Pair<Int, Int> =
        Pair(
            prefs(ctx).getInt(KEY_START_H, DEFAULT_START_H),
            prefs(ctx).getInt(KEY_START_M, DEFAULT_START_M)
        )

    fun getEndTime(ctx: Context): Pair<Int, Int> =
        Pair(
            prefs(ctx).getInt(KEY_END_H, DEFAULT_END_H),
            prefs(ctx).getInt(KEY_END_M, DEFAULT_END_M)
        )

    fun setStartTime(ctx: Context, hour: Int, min: Int) {
        prefs(ctx).edit().putInt(KEY_START_H, hour).putInt(KEY_START_M, min).apply()
    }

    fun setEndTime(ctx: Context, hour: Int, min: Int) {
        prefs(ctx).edit().putInt(KEY_END_H, hour).putInt(KEY_END_M, min).apply()
    }

    /**
     * এখন কি allowed time এ আছি?
     * allowed time এর বাইরে থাকলে browser/video block হবে
     */
    fun isCurrentlyAllowed(ctx: Context): Boolean {
        if (!isScheduleEnabled(ctx)) return true

        val cal  = Calendar.getInstance()
        val now  = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val (sh, sm) = getStartTime(ctx)
        val (eh, em) = getEndTime(ctx)

        val start = sh * 60 + sm
        val end   = eh * 60 + em

        return if (start <= end) {
            now in start..end
        } else {
            // রাত পার হওয়া schedule (যেমন রাত ১০টা থেকে সকাল ৬টা)
            now >= start || now <= end
        }
    }

    fun formatTime(hour: Int, min: Int): String {
        val ampm  = if (hour < 12) "AM" else "PM"
        val h12   = when {
            hour == 0  -> 12
            hour > 12  -> hour - 12
            else       -> hour
        }
        return "%d:%02d %s".format(h12, min, ampm)
    }
}
