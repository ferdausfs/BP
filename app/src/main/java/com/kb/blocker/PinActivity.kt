package com.kb.blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.TextView

/**
 * PIN entry screen
 * MODE_VERIFY  — existing PIN check (settings খুলতে)
 * MODE_SET     — নতুন PIN সেট করতে
 * MODE_CHANGE  — PIN change করতে
 */
class PinActivity : Activity() {

    companion object {
        const val MODE_VERIFY  = "verify"
        const val MODE_SET     = "set"
        const val MODE_CHANGE  = "change"
        const val EXTRA_MODE   = "mode"
        const val RESULT_OK_PIN = 200

        fun launchForVerify(ctx: Context): Intent =
            Intent(ctx, PinActivity::class.java).putExtra(EXTRA_MODE, MODE_VERIFY)

        fun launchForSet(ctx: Context): Intent =
            Intent(ctx, PinActivity::class.java).putExtra(EXTRA_MODE, MODE_SET)
    }

    private var enteredPin = ""
    private var confirmPin = ""
    private var isConfirming = false
    private lateinit var mode: String
    private lateinit var tvTitle: TextView
    private lateinit var tvDots: TextView
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        mode    = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        tvTitle = findViewById(R.id.tvPinTitle)
        tvDots  = findViewById(R.id.tvPinDots)
        tvError = findViewById(R.id.tvPinError)

        updateTitle()
        setupNumpad()
    }

    private fun updateTitle() {
        tvTitle.text = when {
            mode == MODE_SET && isConfirming -> "PIN আবার দাও (confirm)"
            mode == MODE_SET                 -> "নতুন PIN দাও (৪ সংখ্যা)"
            mode == MODE_CHANGE && isConfirming -> "নতুন PIN confirm করো"
            mode == MODE_CHANGE              -> "পুরনো PIN দাও"
            else                             -> "🔒 PIN দাও"
        }
    }

    private fun setupNumpad() {
        val numIds = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3,
            R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7,
            R.id.btn8, R.id.btn9
        )
        numIds.forEachIndexed { digit, id ->
            findViewById<Button>(id).setOnClickListener { appendDigit(digit.toString()) }
        }
        findViewById<Button>(R.id.btnBackspace).setOnClickListener { backspace() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun appendDigit(d: String) {
        if (enteredPin.length >= 6) return
        enteredPin += d
        updateDots()
        if (enteredPin.length >= 4) {
            handler.postDelayed({ processPin() }, 200)
        }
    }

    private fun backspace() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            updateDots()
        }
    }

    private fun updateDots() {
        tvDots.text = "●".repeat(enteredPin.length) + "○".repeat((4 - enteredPin.length).coerceAtLeast(0))
    }

    private fun processPin() {
        when (mode) {
            MODE_VERIFY -> {
                if (PinManager.checkPin(this, enteredPin)) {
                    setResult(RESULT_OK_PIN)
                    finish()
                } else {
                    showError("ভুল PIN!")
                }
            }
            MODE_SET -> {
                if (!isConfirming) {
                    confirmPin   = enteredPin
                    enteredPin   = ""
                    isConfirming = true
                    updateTitle()
                    updateDots()
                    tvError.text = ""
                } else {
                    if (enteredPin == confirmPin) {
                        PinManager.setPin(this, enteredPin)
                        setResult(RESULT_OK_PIN)
                        finish()
                    } else {
                        showError("PIN মিলেনি! আবার চেষ্টা করো")
                        isConfirming = false
                        confirmPin   = ""
                        enteredPin   = ""
                        updateDots()
                        updateTitle()
                    }
                }
            }
            MODE_CHANGE -> {
                if (!isConfirming) {
                    if (PinManager.checkPin(this, enteredPin)) {
                        confirmPin   = ""
                        enteredPin   = ""
                        isConfirming = true
                        mode         = MODE_SET
                        updateTitle()
                        updateDots()
                        tvError.text = ""
                    } else {
                        showError("ভুল PIN!")
                    }
                }
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        enteredPin   = ""
        updateDots()
        vibrate()
    }

    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 100, 50, 100), -1)
            }
        } catch (_: Exception) {}
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
