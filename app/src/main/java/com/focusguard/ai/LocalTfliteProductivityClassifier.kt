package com.focusguard.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.text.nlclassifier.BertNLClassifier
import org.tensorflow.lite.task.text.nlclassifier.NLClassifier

/**
 * On-device TensorFlow Lite text classifier for productivity detection.
 *
 * - Loads a tiny quantized `.tflite` model from assets (`productivity_classifier.tflite`)
 *   or internal storage. A missing file is handled gracefully (no native crash).
 * - Accepts either the standard NLClassifier format (input `"input"` STRING tensor,
 *   output `"scores"` FLOAT[2]) via [NLClassifier], or a BERT model via
 *   [BertNLClassifier] — the loader tries both and logs which succeeded.
 * - Every lifecycle step is logged (tag `FocusGuardAI`): load start/success/failure,
 *   tokenization start, raw output scores, and the final local AI decision.
 * - Fully fail-safe: any exception during load or inference returns `null` and the
 *   caller falls back to the heuristic baseline. A failed load is not retried.
 */
class LocalTfliteProductivityClassifier(private val context: Context) : ProductivityClassifier {

    companion object {
        private const val TAG = "FocusGuardAI"
        private const val MODEL_FILE_NAME = "productivity_classifier.tflite"
    }

    private var nlClassifier: NLClassifier? = null
    private var bertClassifier: BertNLClassifier? = null
    private var initializationAttempted = false
    private var isModelInitialized = false

    init {
        initializeModelAsync()
    }

    @Synchronized
    private fun initializeModelAsync() {
        if (initializationAttempted) return
        initializationAttempted = true
        try {
            Log.d(TAG, "AI: loading model from assets/$MODEL_FILE_NAME")

            // Check if file exists in assets or internal storage to prevent native crash exceptions.
            val assetExists = context.assets.list("")?.contains(MODEL_FILE_NAME) == true ||
                File(context.filesDir, MODEL_FILE_NAME).exists()
            if (!assetExists) {
                Log.w(
                    TAG,
                    "AI: model file $MODEL_FILE_NAME not found in assets. " +
                        "Heuristic baseline will be used until provided."
                )
                isModelInitialized = false
                return
            }

            // Attempt loading standard NLClassifier first.
            try {
                nlClassifier = NLClassifier.createFromFile(context, MODEL_FILE_NAME)
                isModelInitialized = true
                Log.d(TAG, "AI: model load SUCCESS — NLClassifier")
            } catch (nlError: Exception) {
                Log.w(
                    TAG,
                    "AI: NLClassifier load failed (${nlError.message}) — trying BertNLClassifier",
                    nlError
                )

                // Fallback to BertNLClassifier if standard NLClassifier shape isn't matched.
                try {
                    bertClassifier = BertNLClassifier.createFromFile(context, MODEL_FILE_NAME)
                    isModelInitialized = true
                    Log.d(TAG, "AI: model load SUCCESS — BertNLClassifier")
                } catch (bertError: Exception) {
                    Log.e(
                        TAG,
                        "AI: model load FAILED for both NLClassifier and BertNLClassifier. " +
                            "Heuristic baseline will be used.",
                        bertError
                    )
                    isModelInitialized = false
                }
            }
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "AI: model load FAILED with critical exception. Heuristic baseline will be used.",
                e
            )
            isModelInitialized = false
        }
    }

    override fun isReady(): Boolean {
        return isModelInitialized && (nlClassifier != null || bertClassifier != null)
    }

    override fun classify(text: String): ClassificationResult? {
        if (!isReady()) {
            Log.d(TAG, "AI: classify() called but model is not ready/loaded. Falling back to heuristic.")
            return null
        }
        if (text.isBlank()) {
            return null
        }
        try {
            Log.d(TAG, "AI: tokenization/inference start for text='${text.take(50)}...'")

            val categories: List<Category> = when {
                nlClassifier != null -> nlClassifier!!.classify(text)
                bertClassifier != null -> bertClassifier!!.classify(text)
                else -> return null
            }

            var slopScore = 0.0f
            var productiveScore = 0.0f
            for (category in categories) {
                val categoryName = category.label.lowercase(Locale.ROOT)
                val score = category.score

                if (categoryName.contains("slop") || categoryName.contains("distract") ||
                    categoryName.contains("entertainment") || categoryName.contains("bad")
                ) {
                    slopScore = score
                } else if (categoryName.contains("productiv") || categoryName.contains("educat") ||
                    categoryName.contains("study") || categoryName.contains("work") ||
                    categoryName.contains("good")
                ) {
                    productiveScore = score
                }
            }

            // Fallback mapping if label names inside tflite metadata are generic index-based
            // (e.g. "label_0", "label_1").
            if (slopScore == 0.0f && productiveScore == 0.0f && categories.size >= 2) {
                slopScore = categories[0].score
                productiveScore = categories[1].score
            }

            Log.d(TAG, "AI: raw inference output = slop=$slopScore productive=$productiveScore")
            val resolvedLabel = if (slopScore >= productiveScore) "slop" else "productive"
            Log.d(
                TAG,
                "AI: final local decision label=$resolvedLabel slop=$slopScore productive=$productiveScore"
            )
            return ClassificationResult(
                label = resolvedLabel,
                slopScore = slopScore,
                productiveScore = productiveScore
            )
        } catch (e: Exception) {
            Log.e(TAG, "AI: classify() execution threw exception, falling back to heuristic baseline.", e)
            return null
        }
    }
}
