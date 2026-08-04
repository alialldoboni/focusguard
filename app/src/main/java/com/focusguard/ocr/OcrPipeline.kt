package com.focusguard.ocr

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface CaptureSource {
    suspend fun capture(): Bitmap?
}

fun interface TextRecognizer {
    suspend fun recognize(bitmap: Bitmap): String
}

/** Outcome of an OCR attempt, letting callers tell "real text" from "no attempt". */
sealed interface OcrResult {
    /** OCR produced text. */
    data class Text(val value: String) : OcrResult

    /** The request was deduplicated (another OCR in flight, or cooldown). Caller should wait. */
    object Skipped : OcrResult

    /** A full attempt ran but no text could be read (capture/OCR failure or empty screen). */
    object NoText : OcrResult
}

/**
 * Coordinates screen capture + OCR as a single non-blocking, serialized flow.
 *
 * - Duplicate in-flight requests return [OcrResult.Skipped] instead of stacking,
 *   preventing both out-of-order completion races and premature classification
 *   against an empty frame while the real OCR is still running.
 * - A cooldown stops rapid retries when OCR keeps failing (e.g. secure windows),
 *   which previously caused repeated full-res captures and battery drain.
 * - The caller suspends until a result is available; the main thread is never
 *   blocked because capture and ML Kit run off-thread.
 */
class OcrPipeline(
    private val captureSource: CaptureSource,
    private val textRecognizer: TextRecognizer
) {

    private val mutex = Mutex()
    private val inFlight = AtomicReference<String?>(null)
    private val lastAttemptNanos = AtomicLong(0L)

    suspend fun recognize(packageName: String): OcrResult {
        if (inFlight.get() != null) return OcrResult.Skipped
        val now = System.nanoTime()
        if (now - lastAttemptNanos.get() < COOLDOWN_NANOS) return OcrResult.Skipped

        return mutex.withLock {
            if (!inFlight.compareAndSet(null, packageName)) {
                return@withLock OcrResult.Skipped
            }
            try {
                val bitmap = try {
                    captureSource.capture()
                } catch (_: Exception) {
                    null
                } ?: return@withLock OcrResult.NoText
                try {
                    val text = try {
                        textRecognizer.recognize(bitmap)
                    } catch (_: Exception) {
                        ""
                    }
                    if (text.isEmpty()) {
                        OcrResult.NoText
                    } else {
                        OcrResult.Text(text)
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                lastAttemptNanos.set(System.nanoTime())
                inFlight.set(null)
            }
        }
    }

    companion object {
        const val COOLDOWN_MS = 5_000L
        const val COOLDOWN_NANOS = COOLDOWN_MS * 1_000_000L
    }
}
