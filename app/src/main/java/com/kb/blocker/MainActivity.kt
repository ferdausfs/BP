package com.kb.blocker

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kb.blocker.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var keywords: MutableList<String>
    private lateinit var keywordAdapter: ArrayAdapter<String>

    // Whitelist: package → label map
    private lateinit var whitelistPkgs: MutableList<String>
    private lateinit var whitelistLabels: MutableList<String>
    private lateinit var whitelistAdapter: ArrayAdapter<String>

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importJson(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupKeywords()
        setupWhitelist()
        setupSwitches()

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (KeywordService.isRunning) {
            b.tvStatus.text = "✅ Service চালু আছে"
            b.tvStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnAccessibility.visibility = View.GONE
        } else {
            b.tvStatus.text = "⚠️ Service বন্ধ — Accessibility চালু করো"
            b.tvStatus.setTextColor(0xFFFF9800.toInt())
            b.btnAccessibility.visibility = View.VISIBLE
        }
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
                keywords.removeAt(pos)
                keywordAdapter.notifyDataSetChanged()
                KeywordService.saveKeywords(this, keywords)
            }
            true
        }
    }

    private fun addKeywordDialog() {
        val input = EditText(this).apply {
            hint = "যেমন: hot dance"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🚫 Keyword যোগ করো")
            .setView(input)
            .setPositiveButton("যোগ করো") { _, _ ->
                val w = input.text.toString().trim().lowercase()
                when {
                    w.isEmpty()          -> toast("খালি হলে হবে না!")
                    keywords.contains(w) -> toast("আগেই আছে")
                    else -> {
                        keywords.add(w)
                        keywordAdapter.notifyDataSetChanged()
                        KeywordService.saveKeywords(this, keywords)
                        toast("✅ যোগ হয়েছে")
                    }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun importJson(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: return
            val arr = JSONArray(raw.trim())
            val imported = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val w = arr.getString(i).trim().lowercase()
                if (w.isNotBlank() && !keywords.contains(w)) imported.add(w)
            }
            if (imported.isEmpty()) { toast("কোনো নতুন keyword নেই"); return }
            AlertDialog.Builder(this)
                .setTitle("${imported.size}টা import করবে?")
                .setMessage(imported.take(8).joinToString(", ") +
                    if (imported.size > 8) "... (+${imported.size - 8})" else "")
                .setPositiveButton("Import করো") { _, _ ->
                    keywords.addAll(imported)
                    keywordAdapter.notifyDataSetChanged()
                    KeywordService.saveKeywords(this, keywords)
                    toast("✅ ${imported.size}টা add হয়েছে")
                }
                .setNegativeButton("বাতিল", null).show()
        } catch (e: Exception) {
            toast("Format: [\"word1\", \"word2\"]")
        }
    }

    // ── Whitelist — FIXED ─────────────────────────────────────────────────────

    private fun setupWhitelist() {
        // Fresh load — pkg list ও label list sync করা
        whitelistPkgs   = KeywordService.loadWhitelist(this)
        whitelistLabels = whitelistPkgs.map { getAppLabel(it) }.toMutableList()

        whitelistAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistLabels)
        b.listWhitelist.adapter = whitelistAdapter

        b.btnAddWhitelist.setOnClickListener { showAppPicker() }

        b.listWhitelist.setOnItemLongClickListener { _, _, pos, _ ->
            val label = whitelistLabels.getOrElse(pos) { "?" }
            confirmDelete("\"$label\"") {
                whitelistPkgs.removeAt(pos)
                whitelistLabels.removeAt(pos)
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                // service cache force refresh
                KeywordService.instance?.whitelistCacheTime = 0L
                toast("✅ সরানো হয়েছে")
            }
            true
        }
    }

    private fun showAppPicker() {
        val pm   = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        if (apps.isEmpty()) { toast("কোনো app পাওয়া যায়নি"); return }

        val names = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        // Already whitelisted গুলো mark করো
        val checked = apps.map { whitelistPkgs.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("✅ Whitelist-এ যোগ করো")
            .setMultiChoiceItems(names, checked) { _, idx, isChecked ->
                checked[idx] = isChecked
            }
            .setPositiveButton("সেভ করো") { _, _ ->
                var added = 0; var removed = 0
                apps.forEachIndexed { idx, app ->
                    val pkg   = app.packageName
                    val label = names[idx]
                    if (checked[idx] && !whitelistPkgs.contains(pkg)) {
                        whitelistPkgs.add(pkg)
                        whitelistLabels.add(label)
                        added++
                    } else if (!checked[idx] && whitelistPkgs.contains(pkg)) {
                        val pos = whitelistPkgs.indexOf(pkg)
                        whitelistPkgs.removeAt(pos)
                        whitelistLabels.removeAt(pos)
                        removed++
                    }
                }
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L
                val msg = buildString {
                    if (added   > 0) append("✅ $added টা যোগ হয়েছে")
                    if (removed > 0) append(" • ❌ $removed টা সরানো হয়েছে")
                }
                if (msg.isNotBlank()) toast(msg)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ── Switches ──────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        b.switchEnabled.isChecked = KeywordService.isEnabled(this)
        b.switchEnabled.setOnCheckedChangeListener { _, c ->
            KeywordService.setEnabled(this, c)
        }

        b.switchAdultText.isChecked = KeywordService.isAdultTextDetectEnabled(this)
        b.switchAdultText.setOnCheckedChangeListener { _, c ->
            KeywordService.setAdultTextDetect(this, c)
        }

        b.switchSoftAdult.isChecked = KeywordService.isSoftAdultEnabled(this)
        b.switchSoftAdult.setOnCheckedChangeListener { _, c ->
            KeywordService.setSoftAdult(this, c)
        }

        b.switchVideoMeta.isChecked = KeywordService.isVideoMetaEnabled(this)
        b.switchVideoMeta.setOnCheckedChangeListener { _, c ->
            KeywordService.setVideoMeta(this, c)
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }

    private fun confirmDelete(what: String, block: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("$what সরাবে?")
            .setPositiveButton("হ্যাঁ") { _, _ -> block() }
            .setNegativeButton("না", null).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
