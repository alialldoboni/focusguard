package com.focusguard.ai

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.util.Locale
import org.tensorflow.lite.Interpreter

/**
 * On-device TensorFlow Lite text classifier for productivity detection.
 *
 * The model is an INTEGER-INPUT classifier: input `input` int32 [1, SEQ_LEN]
 * (token ids), output `scores` float32 [1, 2] (slop, productive). The app does
 * the tokenization (lowercase + split into letters/digits/underscore runs) and
 * maps tokens to ids using `productivity_vocab.txt` (line index = id, line 0 =
 * [UNK]). This keeps the `.tflite` to pure TFLite builtins (Embedding + pooling
 * + Dense) — no flex ops, tiny (≈10 KB), works with English and Arabic.
 *
 * - Loads lazily once on first use and never retries a failed load.
 * - Every lifecycle step is logged (tag `FocusGuardAI`): load start/success/
 *   failure, tokenization start, raw output scores, and the final local decision.
 * - Fully fail-safe: any exception returns `null` and the caller falls back to
 *   the heuristic baseline.
 */
class LocalTfliteProductivityClassifier(private val context: Context) : ProductivityClassifier {

    companion object {
        private const val TAG = "FocusGuardAI"
        private const val MODEL_FILE_NAME = "productivity_classifier.tflite"
        private const val VOCAB_FILE_NAME = "productivity_vocab.txt"
        private const val SEQ_LEN = 24
    }

    private var interpreter: Interpreter? = null
    private var vocab: Map<String, Int> = emptyMap()
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

            val assetManager = context.assets
            val assetNames = assetManager.list("").orEmpty().toSet()
            if (MODEL_FILE_NAME !in assetNames || VOCAB_FILE_NAME !in assetNames) {
                Log.w(
                    TAG,
                    "AI: $MODEL_FILE_NAME or $VOCAB_FILE_NAME missing from assets. " +
                        "Heuristic baseline will be used until both are provided."
                )
                isModelInitialized = false
                return
            }

            interpreter = Interpreter(
                ByteBuffer.wrap(context.assets.open(MODEL_FILE_NAME).use { it.readBytes() })
            )
            vocab = loadVocab()
            if (vocab.isEmpty()) {
                Log.w(TAG, "AI: vocab file empty — heuristic baseline.")
                isModelInitialized = false
                return
            }
            isModelInitialized = true
            Log.d(
                TAG,
                "AI: model load SUCCESS — Interpreter (seqLen=$SEQ_LEN, vocab=${vocab.size})"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "AI: model load FAILED — heuristic baseline will be used.", e)
            isModelInitialized = false
        }
    }

    private fun loadVocab(): Map<String, Int> {
        val map = HashMap<String, Int>()
        context.assets.open(VOCAB_FILE_NAME).bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, token ->
                if (token.isNotBlank()) map[token] = index
            }
        }
        return map
    }

    override fun isReady(): Boolean {
        return isModelInitialized && interpreter != null && vocab.isNotEmpty()
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
            val ids = tokenize(text)
            val input = Array(1) { ids }
            val output = Array(1) { FloatArray(2) }
            interpreter!!.run(input, output)

            val slopScore = output[0][0]
            val productiveScore = output[0][1]
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

    /** Tokenizer must match the Python training tokenizer: `[\w]+` on lowercase text. */
    private fun tokenize(text: String): IntArray {
        val tokens = Regex("[\\p{L}\\p{N}_]+")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .toList()
        return IntArray(SEQ_LEN) { i ->
            if (i < tokens.size) vocab[tokens[i]] ?: 0 else 0
        }
    }
}
