package com.focusguard.ocr

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 * - Duplicate in-flight requests return [OcrResult.Skipped] instead of stacking.
 * - A short attempt cooldown stops rapid retries after any frame.
 * - A long capture-failure cooldown (default 25s) kicks in after a screenshot
 *   times out or fails (e.g. secure watch pages), so subsequent scan ticks
 *   gracefully bypass OCR instead of hammering `takeScreenshot()` every 5s.
 * - A hard ceiling on a single attempt releases the mutex and resets `inFlight`
 *   even if capture or OCR hangs.
 * - ZERO-RETENTION: the captured bitmap is recycled in a `finally` the instant
 *   OCR completes; nothing is written to disk or retained between passes.
 */
class OcrPipeline(
    private val captureSource: CaptureSource,
    private val textRecognizer: TextRecognizer,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val captureFailureCooldownMs: Long = CAPTURE_FAILURE_COOLDOWN_MS
) {

    private val mutex = Mutex()
    private val inFlight = AtomicReference<String?>(null)
    private val lastAttemptNanos = AtomicLong(0L)
    private val lastCaptureFailureNanos = AtomicLong(0L)

    fun isInCaptureFailureCooldown(): Boolean =
        System.nanoTime() - lastCaptureFailureNanos.get() <
            captureFailureCooldownMs * 1_000_000L

    suspend fun recognize(packageName: String): OcrResult {
        if (inFlight.get() != null) return OcrResult.Skipped
        val now = System.nanoTime()
        if (now - lastAttemptNanos.get() < cooldownMs * 1_000_000L) {
            return OcrResult.Skipped
        }
        if (now - lastCaptureFailureNanos.get() < captureFailureCooldownMs * 1_000_000L) {
            // Graceful bypass during the failure cooldown: no capture, no spam.
            return OcrResult.Skipped
        }

        val attempt = withTimeoutOrNull(attemptTimeoutMs) {
            mutex.withLock {
                if (!inFlight.compareAndSet(null, packageName)) {
                    return@withLock OcrResult.Skipped
                }
                try {
                    val bitmap = try {
                        captureSource.capture()
                    } catch (_: Exception) {
                        null
                    }
                    if (bitmap == null) {
                        lastCaptureFailureNanos.set(System.nanoTime())
                        return@withLock OcrResult.NoText
                    }
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
                        // Zero-retention: release native pixel memory the instant
                        // OCR completes (SLOP, PRODUCTIVE or ALLOWED alike).
                        bitmap.recycle()
                    }
                } finally {
                    lastAttemptNanos.set(System.nanoTime())
                    inFlight.set(null)
                }
            }
        }
        if (attempt == null) {
            // The whole attempt timed out (hung capture or OCR) — treat as failure.
            lastCaptureFailureNanos.set(System.nanoTime())
            return OcrResult.NoText
        }
        return attempt
    }

    companion object {
        const val COOLDOWN_MS = 5_000L
        const val CAPTURE_FAILURE_COOLDOWN_MS = 25_000L
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 10_000L
    }
}
