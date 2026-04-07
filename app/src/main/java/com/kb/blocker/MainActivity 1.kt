package com.kb.blocker

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kb.blocker.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var keywords: MutableList<String>
    private lateinit var keywordAdapter: ArrayAdapter<String>
    private lateinit var whitelistPkgs: MutableList<String>
    private lateinit var whitelistLabels: MutableList<String>
    private lateinit var whitelistAdapter: ArrayAdapter<String>

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { importJson(it) } }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateAdminStatus() }

    private val pinSetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == PinActivity.RESULT_OK_PIN)
            toast("✅ PIN সেট হয়েছে")
        updatePinStatus()
    }

    private val pinVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == PinActivity.RESULT_OK_PIN)
            openSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        dpm            = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        // ★ Android 13+ এ notification permission দরকার
        requestNotificationPermission()

        setupKeywords()
        setupWhitelist()
        setupSwitches()
        setupPermissionButtons()
        setupAdvancedButtons()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateAdminStatus()
        updateNotifStatus()
        updatePinStatus()
        updateScheduleStatus()
        updateStatsPreview()
    }

    // ── Notification permission (Android 13+) ────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun updateServiceStatus() {
        if (KeywordService.isRunning) {
            b.tvStatus.text = "✅ চালু আছে"
            b.tvStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnAccessibility.visibility = View.GONE
        } else {
            b.tvStatus.text = "⚠️ বন্ধ"
            b.tvStatus.setTextColor(0xFFFF9800.toInt())
            b.btnAccessibility.visibility = View.VISIBLE
        }
    }

    private fun updateAdminStatus() {
        val active = dpm.isAdminActive(adminComponent)
        b.tvAdminStatus.text = if (active) "✅ চালু" else "⚠️ বন্ধ"
        b.tvAdminStatus.setTextColor(if (active) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
        b.btnAdminEnable.visibility  = if (active) View.GONE else View.VISIBLE
        b.btnAdminDisable.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun updateNotifStatus() {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val ok   = flat.contains(packageName)
        b.tvNotifStatus.text = if (ok) "✅ চালু" else "⚠️ বন্ধ"
        b.tvNotifStatus.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
        b.btnNotifAccess.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun updatePinStatus() {
        val set = PinManager.isPinSet(this)
        b.tvPinStatus.text = if (set) "✅ PIN সেট আছে" else "⚠️ PIN নেই"
        b.tvPinStatus.setTextColor(if (set) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
        b.btnPinSet.text   = if (set) "PIN পরিবর্তন" else "PIN সেট করো"
        b.btnPinRemove.visibility = if (set) View.VISIBLE else View.GONE
    }

    private fun updateScheduleStatus() {
        val enabled = ScheduleManager.isScheduleEnabled(this)
        b.switchSchedule.isChecked = enabled
        if (enabled) {
            val (sh, sm) = ScheduleManager.getStartTime(this)
            val (eh, em) = ScheduleManager.getEndTime(this)
            b.tvScheduleTime.text =
                "${ScheduleManager.formatTime(sh, sm)} — ${ScheduleManager.formatTime(eh, em)}"
            b.tvScheduleTime.visibility = View.VISIBLE
        } else {
            b.tvScheduleTime.visibility = View.GONE
        }
    }

    private fun updateStatsPreview() {
        val today = BlockLogManager.getTodayCount(this)
        val aiStatus = if (NsfwModelManager.hasAnyModel(this)) {
            if (NsfwModelManager.isEnabled(this)) " • 🤖 AI চালু" else " • 🤖 AI বন্ধ"
        } else " • 🤖 No model"
        b.tvStatsPreview.text = "আজকে $today বার block হয়েছে$aiStatus"
    }

    // ── Permission Buttons ────────────────────────────────────────────────────

    private fun setupPermissionButtons() {
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnAdminEnable.setOnClickListener {
            adminLauncher.launch(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "App কে uninstall থেকে রক্ষা করতে Admin permission দরকার।")
                }
            )
        }
        b.btnAdminDisable.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Admin বন্ধ করবে?")
                .setMessage("বন্ধ করলে যে কেউ app uninstall করতে পারবে।")
                .setPositiveButton("হ্যাঁ") { _, _ -> dpm.removeActiveAdmin(adminComponent); updateAdminStatus() }
                .setNegativeButton("না", null).show()
        }
        b.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // ── Advanced Features ─────────────────────────────────────────────────────

    private fun setupAdvancedButtons() {
        // PIN
        b.btnPinSet.setOnClickListener {
            val mode = if (PinManager.isPinSet(this)) PinActivity.MODE_CHANGE else PinActivity.MODE_SET
            pinSetLauncher.launch(
                Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, mode)
            )
        }
        b.btnPinRemove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("PIN সরাবে?")
                .setMessage("PIN সরালে settings lock থাকবে না।")
                .setPositiveButton("হ্যাঁ") { _, _ -> PinManager.removePin(this); updatePinStatus(); toast("PIN সরানো হয়েছে") }
                .setNegativeButton("না", null).show()
        }

        // Schedule
        b.switchSchedule.setOnCheckedChangeListener { _, c ->
            if (c && !ScheduleManager.isScheduleEnabled(this)) {
                showScheduleDialog()
            } else {
                ScheduleManager.setScheduleEnabled(this, c)
                updateScheduleStatus()
            }
        }
        b.btnScheduleEdit.setOnClickListener { showScheduleDialog() }

        // Usage limits
        b.btnUsageLimits.setOnClickListener { showUsageLimitDialog() }

        // Stats
        b.btnViewStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        b.btnAiModel.setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }

        // Panic (temp disable 30 min)
        b.btnPanic.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⏸️ সাময়িক বন্ধ")
                .setMessage("৩০ মিনিটের জন্য সব blocking বন্ধ করবে?")
                .setPositiveButton("হ্যাঁ") { _, _ ->
                    KeywordService.setEnabled(this, false)
                    b.switchEnabled.isChecked = false
                    toast("⏸️ ৩০ মিনিট বন্ধ")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        KeywordService.setEnabled(this, true)
                        b.switchEnabled.isChecked = true
                        toast("▶️ Blocking আবার চালু")
                    }, 30 * 60 * 1000L)
                }
                .setNegativeButton("না", null).show()
        }
    }

    private fun showScheduleDialog() {
        val (sh, sm) = ScheduleManager.getStartTime(this)
        val (eh, em) = ScheduleManager.getEndTime(this)
        AlertDialog.Builder(this)
            .setTitle("⏰ Allowed সময় সেট করো")
            .setMessage(
                "এই সময়ের মধ্যে browser ও video app চলতে পারবে।\n" +
                "বাইরে গেলে block হবে।\n\n" +
                "শুরু: ${ScheduleManager.formatTime(sh, sm)}\n" +
                "শেষ: ${ScheduleManager.formatTime(eh, em)}"
            )
            .setPositiveButton("শুরুর সময়") { _, _ ->
                TimePickerDialog(this, { _, h, m ->
                    ScheduleManager.setStartTime(this, h, m)
                    TimePickerDialog(this, { _, eh2, em2 ->
                        ScheduleManager.setEndTime(this, eh2, em2)
                        ScheduleManager.setScheduleEnabled(this, true)
                        updateScheduleStatus()
                        toast("✅ Schedule সেট হয়েছে")
                    }, eh, em, false).show()
                }, sh, sm, false).show()
            }
            .setNegativeButton("বাতিল") { _, _ ->
                if (!ScheduleManager.isScheduleEnabled(this))
                    b.switchSchedule.isChecked = false
            }
            .show()
    }

    private fun showUsageLimitDialog() {
        val pm      = packageManager
        val targets = (KeywordService.BROWSER_PACKAGES + KeywordService.VIDEO_PACKAGES).toList()
        val installed = targets.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Pair(pkg, pm.getApplicationLabel(info).toString())
            } catch (_: Exception) { null }
        }.sortedBy { it.second }

        if (installed.isEmpty()) { toast("কোনো browser/video app পাওয়া যায়নি"); return }

        val names = installed.map { (pkg, label) ->
            val limit = UsageLimitManager.getLimitMinutes(this, pkg)
            val used  = (UsageLimitManager.getUsedSeconds(this, pkg) / 60).toInt()
            if (limit > 0) "$label — $used/$limit মিনিট"
            else "$label — কোনো limit নেই"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("⏱️ Daily Usage Limit")
            .setItems(names) { _, idx ->
                val (pkg, label) = installed[idx]
                val options = arrayOf("৩০ মিনিট", "১ ঘণ্টা", "২ ঘণ্টা", "৩ ঘণ্টা", "Limit সরাও")
                val mins    = intArrayOf(30, 60, 120, 180, -1)
                AlertDialog.Builder(this)
                    .setTitle("$label এর limit")
                    .setItems(options) { _, i ->
                        if (mins[i] < 0) {
                            UsageLimitManager.removeLimit(this, pkg)
                            toast("$label — limit সরানো হয়েছে")
                        } else {
                            UsageLimitManager.setLimit(this, pkg, mins[i])
                            toast("$label — ${options[i]} limit সেট")
                        }
                    }.show()
            }.show()
    }

    private fun openSettings() {
        toast("✅ Settings unlock হয়েছে")
    }

    // ── Keywords ──────────────────────────────────────────────────────────────

    private fun setupKeywords() {
        keywords = KeywordService.loadKeywords(this)
        keywordAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, keywords)
        b.listKeywords.adapter = keywordAdapter
        b.btnAdd.setOnClickListener { addKeywordDialog() }
        b.btnImportJson.setOnClickListener {
            filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            })
        }
        b.listKeywords.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete("\"${keywords[pos]}\"") {
                keywords.removeAt(pos); keywordAdapter.notifyDataSetChanged()
                KeywordService.saveKeywords(this, keywords)
            }; true
        }
    }

    private fun addKeywordDialog() {
        val input = EditText(this).apply {
            hint = "যেমন: hot dance, item song"
            inputType = InputType.TYPE_CLASS_TEXT; setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this).setTitle("🚫 নতুন Keyword").setView(input)
            .setPositiveButton("যোগ করো") { _, _ ->
                val w = input.text.toString().trim().lowercase()
                when {
                    w.isEmpty()          -> toast("খালি হলে হবে না!")
                    keywords.contains(w) -> toast("আগেই আছে")
                    else -> { keywords.add(w); keywordAdapter.notifyDataSetChanged()
                        KeywordService.saveKeywords(this, keywords); toast("✅ যোগ হয়েছে") }
                }
            }.setNegativeButton("বাতিল", null).show()
    }

    private fun importJson(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText() } ?: return
            val arr = JSONArray(raw.trim())
            val imported = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val w = arr.getString(i).trim().lowercase()
                if (w.isNotBlank() && !keywords.contains(w)) imported.add(w)
            }
            if (imported.isEmpty()) { toast("কোনো নতুন keyword নেই"); return }
            AlertDialog.Builder(this).setTitle("${imported.size}টা import করবে?")
                .setMessage(imported.take(8).joinToString(", ") +
                    if (imported.size > 8) "... (+${imported.size - 8})" else "")
                .setPositiveButton("Import") { _, _ ->
                    keywords.addAll(imported); keywordAdapter.notifyDataSetChanged()
                    KeywordService.saveKeywords(this, keywords); toast("✅ ${imported.size}টা add হয়েছে")
                }.setNegativeButton("বাতিল", null).show()
        } catch (_: Exception) { toast("Format: [\"word1\", \"word2\"]") }
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    private fun setupWhitelist() {
        whitelistPkgs   = KeywordService.loadWhitelist(this)
        whitelistLabels = whitelistPkgs.map { getAppLabel(it) }.toMutableList()
        whitelistAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistLabels)
        b.listWhitelist.adapter = whitelistAdapter
        b.btnAddWhitelist.setOnClickListener { showAppPicker() }
        b.listWhitelist.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete("\"${whitelistLabels.getOrElse(pos) { "?" }}\"") {
                whitelistPkgs.removeAt(pos); whitelistLabels.removeAt(pos)
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L; toast("✅ সরানো হয়েছে")
            }; true
        }
    }

    private fun showAppPicker() {
        val pm   = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        if (apps.isEmpty()) { toast("কোনো app পাওয়া যায়নি"); return }
        val names   = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val checked = apps.map { whitelistPkgs.contains(it.packageName) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("✅ Whitelist App বেছে নাও")
            .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("সেভ করো") { _, _ ->
                var added = 0; var removed = 0
                apps.forEachIndexed { i, app ->
                    val pkg = app.packageName; val label = names[i]
                    if (checked[i] && !whitelistPkgs.contains(pkg)) {
                        whitelistPkgs.add(pkg); whitelistLabels.add(label); added++
                    } else if (!checked[i] && whitelistPkgs.contains(pkg)) {
                        val idx = whitelistPkgs.indexOf(pkg)
                        whitelistPkgs.removeAt(idx); whitelistLabels.removeAt(idx); removed++
                    }
                }
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L
                val msg = buildString {
                    if (added > 0)   append("✅ $added টা যোগ")
                    if (removed > 0) append(" ❌ $removed টা সরানো")
                }
                if (msg.isNotBlank()) toast(msg)
            }.setNegativeButton("বাতিল", null).show()
    }

    // ── Switches ──────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        b.switchEnabled.isChecked   = KeywordService.isEnabled(this)
        b.switchAdultText.isChecked = KeywordService.isAdultTextDetectEnabled(this)
        b.switchSoftAdult.isChecked = KeywordService.isSoftAdultEnabled(this)
        b.switchVideoMeta.isChecked = KeywordService.isVideoMetaEnabled(this)

        // ★ FIX: Usage Limit enable switch
        b.switchUsageLimitEnabled.isChecked = UsageLimitManager.isEnabled(this)

        b.switchEnabled.setOnCheckedChangeListener   { _, c -> KeywordService.setEnabled(this, c) }
        b.switchAdultText.setOnCheckedChangeListener { _, c -> KeywordService.setAdultTextDetect(this, c) }
        b.switchSoftAdult.setOnCheckedChangeListener { _, c -> KeywordService.setSoftAdult(this, c) }
        b.switchVideoMeta.setOnCheckedChangeListener { _, c -> KeywordService.setVideoMeta(this, c) }

        // ★ FIX: এই switch ছাড়া usage limit কাজ করতো না
        b.switchUsageLimitEnabled.setOnCheckedChangeListener { _, c ->
            UsageLimitManager.setEnabled(this, c)
            if (c) toast("✅ Usage limit চালু — Set করো বাটন দিয়ে limit ঠিক করো")
            else   toast("Usage limit বন্ধ")
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun confirmDelete(what: String, block: () -> Unit) {
        AlertDialog.Builder(this).setTitle("$what সরাবে?")
            .setPositiveButton("হ্যাঁ") { _, _ -> block() }
            .setNegativeButton("না", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
