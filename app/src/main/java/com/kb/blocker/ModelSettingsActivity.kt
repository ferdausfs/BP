package com.kb.blocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var tvModelName   : TextView
    private lateinit var tvModelInfo   : TextView
    private lateinit var tvModelStatus : TextView
    private lateinit var switchAiScan  : Switch
    private lateinit var spinnerType   : Spinner
    private lateinit var seekThreshold : SeekBar
    private lateinit var tvThreshold   : TextView
    private lateinit var btnImport     : Button
    private lateinit var btnRemove     : Button
    private lateinit var btnTest       : Button
    private lateinit var btnBack       : Button
    private lateinit var btnGuide      : Button
    private lateinit var progressBar   : ProgressBar
    private lateinit var tvProgress    : TextView

    private val bgExecutor  = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { importModelFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

        tvModelName   = findViewById(R.id.tvModelName)
        tvModelInfo   = findViewById(R.id.tvModelInfo)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        switchAiScan  = findViewById(R.id.switchAiScan)
        spinnerType   = findViewById(R.id.spinnerModelType)
        seekThreshold = findViewById(R.id.seekThreshold)
        tvThreshold   = findViewById(R.id.tvThreshold)
        btnImport     = findViewById(R.id.btnImportModel)
        btnRemove     = findViewById(R.id.btnRemoveModel)
        btnTest       = findViewById(R.id.btnTestScan)
        btnBack       = findViewById(R.id.btnBack)
        btnGuide      = findViewById(R.id.btnDownloadInfo)
        progressBar   = findViewById(R.id.progressBar)
        tvProgress    = findViewById(R.id.tvProgress)

        setupSpinner()
        setupThreshold()
        setupButtons()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val hasModel = NsfwModelManager.hasAnyModel(this)
        val isLoaded = NsfwModelManager.isModelLoaded()
        val isEnabled = NsfwModelManager.isEnabled(this)

        tvModelName.text = NsfwModelManager.getActiveModelName(this)

        when {
            !hasModel -> {
                tvModelStatus.text = "❌ কোনো model নেই — import করো"
                tvModelStatus.setTextColor(0xFFFF5252.toInt())
            }
            isLoaded -> {
                tvModelStatus.text = "✅ Model ready — scanning চলছে"
                tvModelStatus.setTextColor(0xFF4CAF50.toInt())
            }
            else -> {
                tvModelStatus.text = "⚠️ Model আছে কিন্তু load হয়নি"
                tvModelStatus.setTextColor(0xFFFF9800.toInt())
            }
        }

        if (hasModel) {
            val type = NsfwModelManager.getModelType(this)
            val size = NsfwModelManager.getInputSize(this)
            val typeLabel = when (type) {
                NsfwModelManager.TYPE_5CLASS -> "5-class (drawings/hentai/neutral/porn/sexy)"
                NsfwModelManager.TYPE_2CLASS -> "2-class (sfw/nsfw)"
                else -> "Custom"
            }
            tvModelInfo.text = "Type: $typeLabel\nInput: ${size}×${size}px"
        } else {
            tvModelInfo.text = "নিচের guide দেখো"
        }

        // Switch — listener সরিয়ে set করি, তারপর আবার লাগাই
        switchAiScan.setOnCheckedChangeListener(null)
        switchAiScan.isChecked = isEnabled
        switchAiScan.setOnCheckedChangeListener { _, checked -> onAiScanToggle(checked) }

        btnRemove.visibility = if (NsfwModelManager.hasCustomModel(this)) View.VISIBLE else View.GONE
        btnTest.visibility   = if (isLoaded) View.VISIBLE else View.GONE

        val typeIdx = when (NsfwModelManager.getModelType(this)) {
            NsfwModelManager.TYPE_5CLASS -> 0
            NsfwModelManager.TYPE_2CLASS -> 1
            else -> 2
        }
        (spinnerType as? Spinner)?.setSelection(typeIdx)

        val thresh = NsfwModelManager.getThreshold(this)
        seekThreshold.progress = (thresh * 100).toInt()
        tvThreshold.text = "%.0f%%".format(thresh * 100)
    }

    private fun showLoading(msg: String) {
        progressBar.visibility = View.VISIBLE
        tvProgress.text = msg
        tvProgress.visibility = View.VISIBLE
        btnImport.isEnabled = false
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE
        btnImport.isEnabled = true
    }

    // ── Spinner ───────────────────────────────────────────────────────────────

    private fun setupSpinner() {
        val options = arrayOf(
            "5-class (drawings/hentai/neutral/porn/sexy)",
            "2-class (sfw/nsfw)",
            "Custom"
        )
        spinnerType.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, options)

        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val type = when (pos) {
                    0 -> NsfwModelManager.TYPE_5CLASS
                    1 -> NsfwModelManager.TYPE_2CLASS
                    else -> NsfwModelManager.TYPE_CUSTOM
                }
                if (type != NsfwModelManager.getModelType(this@ModelSettingsActivity)) {
                    NsfwModelManager.setModelType(this@ModelSettingsActivity, type)
                    NsfwModelManager.unloadModel()
                    toast("Model type পরিবর্তন হয়েছে। Reload হবে।")
                    reloadModelInBackground()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ── Threshold ─────────────────────────────────────────────────────────────

    private fun setupThreshold() {
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThreshold.text = "$progress%"
                if (fromUser) {
                    NsfwModelManager.setThreshold(
                        this@ModelSettingsActivity,
                        (progress / 100f).coerceIn(0.1f, 1.0f)
                    )
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }

        btnImport.setOnClickListener {
            modelPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            })
        }

        btnRemove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Model সরাবে?")
                .setPositiveButton("হ্যাঁ") { _, _ ->
                    NsfwModelManager.removeCustomModel(this)
                    NsfwScanService.stop()
                    toast("Custom model সরানো হয়েছে")
                    refreshUI()
                }
                .setNegativeButton("না", null).show()
        }

        btnTest.setOnClickListener { runTestScan() }

        btnGuide.setOnClickListener { showDownloadGuide() }
    }

    // ── AI Scan Toggle ────────────────────────────────────────────────────────

    private fun onAiScanToggle(enable: Boolean) {
        if (enable) {
            if (!NsfwModelManager.hasAnyModel(this)) {
                toast("⚠️ আগে model import করো!")
                switchAiScan.isChecked = false
                return
            }
            NsfwModelManager.setEnabled(this, true)
            reloadModelInBackground()
        } else {
            NsfwModelManager.setEnabled(this, false)
            NsfwScanService.stop()
            toast("AI scan বন্ধ হয়েছে")
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun importModelFromUri(uri: Uri) {
        showLoading("⏳ Validating model...")

        bgExecutor.execute {
            val info = NsfwModelManager.validateModel(this, uri)

            mainHandler.post {
                if (info == null) {
                    hideLoading()
                    toast("❌ Invalid file! .tflite model দাও।")
                    return@post
                }

                hideLoading()

                val typeLabel = when (info.suggestedType) {
                    NsfwModelManager.TYPE_5CLASS -> "5-class ✅ (recommended)"
                    NsfwModelManager.TYPE_2CLASS -> "2-class"
                    else -> "Custom (${info.outputSize} outputs)"
                }

                AlertDialog.Builder(this)
                    .setTitle("✅ Valid Model!")
                    .setMessage(
                        "Input: ${info.inputSize}×${info.inputSize}px\n" +
                        "Output: ${info.outputSize} class\n" +
                        "Size: ${"%.1f".format(info.fileSizeMb)} MB\n" +
                        "Type: $typeLabel\n\n" +
                        "Import করবে?"
                    )
                    .setPositiveButton("Import করো") { _, _ ->
                        doImport(uri, info)
                    }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
        }
    }

    private fun doImport(uri: Uri, info: NsfwModelManager.ModelInfo) {
        showLoading("⏳ Importing model...")

        bgExecutor.execute {
            val ok = NsfwModelManager.importModel(
                this, uri, info.suggestedType, info.inputSize)

            if (ok) {
                val loaded = NsfwModelManager.loadModel(this)
                mainHandler.post {
                    hideLoading()
                    if (loaded) {
                        toast("✅ Model imported ও loaded!")
                        KeywordService.instance?.onNsfwModelChanged()
                    } else {
                        toast("⚠️ Imported কিন্তু load হয়নি। App restart করো।")
                    }
                    refreshUI()
                }
            } else {
                mainHandler.post {
                    hideLoading()
                    toast("❌ Import failed!")
                }
            }
        }
    }

    // ── Reload in background ──────────────────────────────────────────────────

    private fun reloadModelInBackground() {
        bgExecutor.execute {
            val loaded = NsfwModelManager.loadModel(this)
            mainHandler.post {
                if (loaded) {
                    KeywordService.instance?.onNsfwModelChanged()
                    toast("✅ Model loaded!")
                } else {
                    toast("⚠️ Model load হয়নি")
                }
                refreshUI()
            }
        }
    }

    // ── Test Scan ─────────────────────────────────────────────────────────────

    private fun runTestScan() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            toast("⚠️ Test scan requires Android 11+")
            return
        }
        KeywordService.instance?.requestTestScan()
            ?: toast("⚠️ Accessibility Service চালু নেই")
    }

    // ── Guide ─────────────────────────────────────────────────────────────────

    private fun showDownloadGuide() {
        AlertDialog.Builder(this)
            .setTitle("📥 Model কোথায় পাবে?")
            .setMessage(
                "✅ Option 1 — GantMan MobileNetV2 (5-class):\n" +
                "github.com/GantMan/nsfw_model\n" +
                "→ Releases → mobilenet_v2_140_224.tflite\n" +
                "→ Rename করো: nsfw_model.tflite\n\n" +
                "✅ Option 2 — Yahoo Open NSFW (2-class):\n" +
                "github.com/mdietrichstein/tensorflow-open_nsfw\n" +
                "→ tflite export script run করো\n\n" +
                "📲 Import steps:\n" +
                "1. Phone এ .tflite file download করো\n" +
                "2. 'Model Import করো' বাটন চাপো\n" +
                "3. File Manager থেকে file select করো\n" +
                "4. App auto-detect করবে 2-class না 5-class\n\n" +
                "⚠️ File extension .tflite হতে হবে"
            )
            .setPositiveButton("বুঝেছি", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
