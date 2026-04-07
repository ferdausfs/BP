package com.kb.blocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Admin Receiver
 * এটা active থাকলে app কে normal uninstall করা যাবে না।
 * Settings → Apps → Uninstall করতে গেলে block হবে।
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "✅ Admin Protection চালু হয়েছে", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ Admin বন্ধ করলে app এর protection উঠে যাবে!"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "❌ Admin Protection বন্ধ হয়েছে", Toast.LENGTH_SHORT).show()
    }
}
