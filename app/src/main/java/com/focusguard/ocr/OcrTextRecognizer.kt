package com.focusguard.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suspending wrapper around ML Kit Text Recognition.
 *
 * - Processes at most one frame at a time (`limitedParallelism(1)`) so results
 *   always complete in order and memory stays bounded.
 * - Each frame is copied into a private working bitmap that ML Kit may keep
 *   referencing after the caller recycles the source; the copy is recycled from
 *   a `Task` completion listener, so a timed-out frame can never crash the
 *   native OCR engine by freeing pixels it is still reading.
 * - A hard timeout prevents a hung task from permanently blocking the pipeline.
 */
class OcrTextRecognizer : TextRecognizer {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    override suspend fun recognize(bitmap: Bitmap): String = withContext(dispatcher) {
        android.util.Log.d("FocusGuard", "Path B: Passing bitmap to ML Kit OCR...")
        val working = Bitmap.createBitmap(bitmap)
        val input = InputImage.fromBitmap(working, 0)
        val task = client.process(input)
        task.addOnCompleteListener { working.recycle() }
        val text = withTimeoutOrNull(OCR_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                task.addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }.addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: ""
        if (task.isComplete) working.recycle()
        if (text.isEmpty()) {
            android.util.Log.d(
                "FocusGuard",
                "Path B: OCR finished with no text (timed out after ${OCR_TIMEOUT_MS}ms or empty frame)"
            )
        } else {
            android.util.Log.d(
                "FocusGuard",
                "Path B: OCR finished, raw text: ${text.take(200)}"
            )
        }
        text
    }

    fun close() {
        client.close()
    }

    companion object {
        const val OCR_TIMEOUT_MS = 4_000L
    }
}
