package com.kb.blocker

import android.app.Activity
import android.os.Bundle
import android.widget.*

/**
 * Block history + stats dashboard
 */
class StatsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var btnClear: Button
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        listView     = findViewById(R.id.listBlockLog)
        tvTodayCount = findViewById(R.id.tvTodayCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        btnClear     = findViewById(R.id.btnClearLog)
        btnBack      = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        btnClear.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Log মুছে ফেলবে?")
                .setPositiveButton("হ্যাঁ") { _, _ ->
                    BlockLogManager.clearAll(this)
                    loadData()
                }
                .setNegativeButton("না", null)
                .show()
        }

        loadData()
    }

    private fun loadData() {
        val logs  = BlockLogManager.getAll(this)
        val today = BlockLogManager.getTodayCount(this)

        tvTodayCount.text = "আজকে: $today বার blocked"
        tvTotalCount.text = "মোট:  ${logs.size} টা log"

        if (logs.isEmpty()) {
            val adapter = ArrayAdapter(this,
                android.R.layout.simple_list_item_1,
                listOf("কোনো block history নেই"))
            listView.adapter = adapter
            return
        }

        val items = logs.map { entry ->
            "${BlockLogManager.formatTime(entry.time)}\n" +
            "📱 ${entry.appLabel}\n" +
            "🚫 ${entry.reason}"
        }

        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, items
        ) {
            override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(pos, v, parent) as TextView
                view.setTextColor(0xFFCCCCCC.toInt())
                view.textSize = 12f
                view.setPadding(24, 16, 24, 16)
                view.setBackgroundColor(if (pos % 2 == 0) 0xFF141414.toInt() else 0xFF1A1A1A.toInt())
                return view
            }
        }
        listView.adapter = adapter
    }
}
