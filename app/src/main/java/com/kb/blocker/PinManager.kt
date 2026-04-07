package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * PIN management — settings ও app lock এর জন্য
 * PIN SHA-256 hash হিসেবে store হয়, plaintext না
 */
object PinManager {

    private const val PREFS     = "pin_prefs"
    private const val KEY_PIN   = "pin_hash"
    private const val KEY_SET   = "pin_set"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPinSet(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SET, false)

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit()
            .putString(KEY_PIN, hash(pin))
            .putBoolean(KEY_SET, true)
            .apply()
    }

    fun removePin(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_PIN)
            .putBoolean(KEY_SET, false)
            .apply()
    }

    fun checkPin(ctx: Context, input: String): Boolean {
        if (!isPinSet(ctx)) return true
        val stored = prefs(ctx).getString(KEY_PIN, "") ?: return false
        return stored == hash(input)
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
