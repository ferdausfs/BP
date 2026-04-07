package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object NsfwModelManager {

    private const val TAG            = "NsfwModelManager"
    private const val PREFS          = "nsfw_prefs"
    private const val KEY_ENABLED    = "ai_enabled"
    private const val KEY_MODEL_TYPE = "model_type"
    private const val KEY_THRESHOLD  = "threshold"
    private const val KEY_INPUT_SIZE = "input_size"
    private const val KEY_HAS_CUSTOM = "has_custom"

    private const val MODEL_FILE     = "nsfw_model.tflite"
    private const val DEFAULT_SIZE   = 224
    // ★ SUM mode: hentai+porn+sexy যোগ করা হয়, তাই 0.60f (60%) ভালো default
    private const val DEFAULT_THRESH = 0.60f

    const val TYPE_5CLASS = "5class"
    const val TYPE_2CLASS = "2class"
    const val TYPE_CUSTOM = "custom"

    @Volatile private var interpreter: Interpreter? = null
    private val lock = Any()

    data class ModelInfo(
        val inputSize: Int,
        val outputSize: Int,
        val fileSizeMb: Float,
        val suggestedType: String
    )

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context)    = p(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun getThreshold(ctx: Context) = p(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESH)
    fun setThreshold(ctx: Context, v: Float) = p(ctx).edit().putFloat(KEY_THRESHOLD, v).apply()

    fun getModelType(ctx: Context) = p(ctx).getString(KEY_MODEL_TYPE, TYPE_5CLASS) ?: TYPE_5CLASS
    fun setModelType(ctx: Context, t: String) = p(ctx).edit().putString(KEY_MODEL_TYPE, t).apply()

    fun getInputSize(ctx: Context) = p(ctx).getInt(KEY_INPUT_SIZE, DEFAULT_SIZE)
    fun setInputSize(ctx: Context, s: Int) = p(ctx).edit().putInt(KEY_INPUT_SIZE, s).apply()

    fun hasCustomModel(ctx: Context) = p(ctx).getBoolean(KEY_HAS_CUSTOM, false)

    // ── Model file path ───────────────────────────────────────────────────────

    private fun customModelFile(ctx: Context) = File(ctx.filesDir, MODEL_FILE)

    fun hasAssetModel(ctx: Context): Boolean = try {
        ctx.assets.open(MODEL_FILE).use { true }
    } catch (_: Exception) { false }

    fun hasAnyModel(ctx: Context): Boolean =
        customModelFile(ctx).exists() || hasAssetModel(ctx)

    fun getActiveModelName(ctx: Context): String = when {
        customModelFile(ctx).exists() -> "Custom: ${MODEL_FILE}"
        hasAssetModel(ctx)            -> "Built-in: ${MODEL_FILE}"
        else                          -> "কোনো model নেই"
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { interpreter != null }

    // ── Load ──────────────────────────────────────────────────────────────────

    fun loadModel(ctx: Context): Boolean = synchronized(lock) {
        unloadModelInternal()

        val modelFile = when {
            customModelFile(ctx).exists() -> customModelFile(ctx)
            hasAssetModel(ctx)            -> copyAssetToCache(ctx) ?: return false
            else                          -> return false
        }

        return try {
            val mappedBuffer = loadMappedBuffer(modelFile) ?: return false
            val options = Interpreter.Options().apply {
                numThreads = 2
                useNNAPI   = false
            }
            interpreter = Interpreter(mappedBuffer, options)
            Log.d(TAG, "Model loaded: ${modelFile.name}, size=${modelFile.length()/1024}KB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}")
            interpreter = null
            false
        }
    }

    fun unloadModel() = synchronized(lock) { unloadModelInternal() }

    private fun unloadModelInternal() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }

    // ── Asset → Cache copy ────────────────────────────────────────────────────

    private fun copyAssetToCache(ctx: Context): File? {
        return try {
            val dest = File(ctx.cacheDir, MODEL_FILE)
            if (dest.exists() && dest.length() > 1024) return dest
            ctx.assets.open(MODEL_FILE).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Asset copied to cache: ${dest.length()/1024}KB")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Asset copy failed: ${e.message}")
            null
        }
    }

    private fun loadMappedBuffer(file: File): MappedByteBuffer? = try {
        FileInputStream(file).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Buffer load failed: ${e.message}")
        null
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun importModel(ctx: Context, uri: Uri, type: String, inputSize: Int): Boolean {
        return try {
            unloadModel()
            val dest = customModelFile(ctx)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return false
            if (!dest.exists() || dest.length() < 100) { dest.delete(); return false }
            setModelType(ctx, type)
            setInputSize(ctx, inputSize)
            p(ctx).edit().putBoolean(KEY_HAS_CUSTOM, true).apply()
            Log.d(TAG, "Model imported: ${dest.length()/1024}KB, type=$type, size=$inputSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}")
            false
        }
    }

    fun removeCustomModel(ctx: Context) {
        unloadModel()
        try { customModelFile(ctx).delete() } catch (_: Exception) {}
        p(ctx).edit().putBoolean(KEY_HAS_CUSTOM, false).apply()
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    fun validateModel(ctx: Context, uri: Uri): ModelInfo? {
        var tmpFile: File? = null
        return try {
            tmpFile = File(ctx.cacheDir, "validate_${System.currentTimeMillis()}.tflite")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            } ?: return null
            if (tmpFile.length() < 100) return null

            val buffer  = loadMappedBuffer(tmpFile) ?: return null
            val options = Interpreter.Options().apply { numThreads = 1; useNNAPI = false }
            val interp  = Interpreter(buffer, options)

            val inShape  = interp.getInputTensor(0).shape()
            val outShape = interp.getOutputTensor(0).shape()
            val outSize  = if (outShape.size >= 2) outShape[1] else outShape[0]
            val inSize   = if (inShape.size >= 3) inShape[1] else DEFAULT_SIZE
            interp.close()

            ModelInfo(
                inputSize     = inSize,
                outputSize    = outSize,
                fileSizeMb    = tmpFile.length() / (1024f * 1024f),
                suggestedType = when (outSize) { 5 -> TYPE_5CLASS; 2 -> TYPE_2CLASS; else -> TYPE_CUSTOM }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Validate failed: ${e.message}")
            null
        } finally {
            try { tmpFile?.delete() } catch (_: Exception) {}
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * ★ FIX: SUM mode — hentai + porn + sexy যোগ করা হয়, MAX নয়।
     *
     * কেন SUM ভালো:
     * - বিড়ালের ছবিতে hentai=38% একা দেখলে MAX এ block হতো।
     *   কিন্তু SUM এ hentai(38)+porn(2)+sexy(5)=45% → 60% threshold এ block হয় না। ✅
     * - Semi-naked image এ porn(30)+sexy(35)+hentai(5)=70% → block হয়। ✅
     *
     * Threshold 60% (0.60f) recommended for SUM mode.
     */
    fun scan(ctx: Context, bitmap: Bitmap): Pair<Boolean, Float> {
        val interp = synchronized(lock) { interpreter } ?: return Pair(false, 0f)

        return try {
            val size      = getInputSize(ctx)
            val resized   = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val inputBuf  = bitmapToByteBuffer(resized, size)
            resized.recycle()

            val outSize   = getOutputSize(interp)
            val output    = Array(1) { FloatArray(outSize) }

            synchronized(lock) {
                if (interpreter == null) return Pair(false, 0f)
                interp.run(inputBuf, output)
            }

            val probs     = output[0]
            val threshold = getThreshold(ctx)

            when (getModelType(ctx)) {
                TYPE_5CLASS -> {
                    // [0]drawings [1]hentai [2]neutral [3]porn [4]sexy
                    // ★ SUM: তিনটা adult class একসাথে যোগ
                    val adultScore =
                        probs.getOrElse(1) { 0f } +   // hentai
                        probs.getOrElse(3) { 0f } +   // porn
                        probs.getOrElse(4) { 0f }     // sexy
                    Pair(adultScore >= threshold, adultScore)
                }
                TYPE_2CLASS -> {
                    // [0]sfw [1]nsfw — এখানে SUM দরকার নেই
                    val nsfw = probs.getOrElse(1) { 0f }
                    Pair(nsfw >= threshold, nsfw)
                }
                else -> {
                    val max    = probs.maxOrNull() ?: 0f
                    val maxIdx = probs.indexOfFirst { it == max }
                    Pair(maxIdx != 0 && max >= threshold, max)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed: ${e.message}")
            Pair(false, 0f)
        }
    }

    /** Detailed result — debug/test এর জন্য (SUM score ও দেখায়) */
    fun scanDetailed(ctx: Context, bitmap: Bitmap): Map<String, Float>? {
        val interp = synchronized(lock) { interpreter } ?: return null
        return try {
            val size     = getInputSize(ctx)
            val resized  = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val inputBuf = bitmapToByteBuffer(resized, size)
            resized.recycle()

            val outSize  = getOutputSize(interp)
            val output   = Array(1) { FloatArray(outSize) }
            synchronized(lock) { interp.run(inputBuf, output) }

            val probs = output[0]
            val labels = when (getModelType(ctx)) {
                TYPE_5CLASS -> listOf("drawings", "hentai", "neutral", "porn", "sexy")
                TYPE_2CLASS -> listOf("sfw", "nsfw")
                else        -> (0 until outSize).map { "class_$it" }
            }
            val baseMap = labels.zip(probs.toList()).toMap().toMutableMap()

            // ★ SUM score ও দেখাও (5-class এর জন্য)
            if (getModelType(ctx) == TYPE_5CLASS && probs.size >= 5) {
                val sumScore = probs[1] + probs[3] + probs[4]
                baseMap["⚡ adult_sum"] = sumScore
            }
            baseMap
        } catch (e: Exception) {
            Log.e(TAG, "Detailed scan failed: ${e.message}")
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bitmapToByteBuffer(bitmap: Bitmap, size: Int): ByteBuffer {
        val buf    = ByteBuffer.allocateDirect(4 * size * size * 3)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buf.putFloat(((pixel shr  8) and 0xFF) / 255.0f)
            buf.putFloat(( pixel         and 0xFF) / 255.0f)
        }
        return buf
    }

    private fun getOutputSize(interp: Interpreter): Int {
        val shape = interp.getOutputTensor(0).shape()
        return if (shape.size >= 2) shape[1] else shape[0]
    }
}
