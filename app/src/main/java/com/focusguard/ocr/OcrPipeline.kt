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

/**
 * Coordinates screen capture + OCR as a single non-blocking, serialized flow.
 *
 * - Duplicate in-flight requests are dropped instead of stacking (prevents the
 *   out-of-order completion races the old callback flow had).
 * - A cooldown stops rapid retries when OCR keeps failing (e.g. secure windows),
 *   which previously caused repeated full-res captures and battery drain.
 * - The caller suspends until a text result (or "") is available; the main
 *   thread is never blocked because capture and ML Kit run off-thread.
 */
class OcrPipeline(
    private val captureSource: CaptureSource,
    private val textRecognizer: TextRecognizer
) {

    private val mutex = Mutex()
    private val inFlight = AtomicReference<String?>(null)
    private val lastAttemptNanos = AtomicLong(0L)

    suspend fun recognize(packageName: String): String {
        if (inFlight.get() != null) return ""
        val now = System.nanoTime()
        if (now - lastAttemptNanos.get() < COOLDOWN_NANOS) return ""

        return mutex.withLock {
            if (!inFlight.compareAndSet(null, packageName)) return@withLock ""
            try {
                val bitmap = try {
                    captureSource.capture()
                } catch (_: Exception) {
                    null
                } ?: return@withLock ""
                try {
                    textRecognizer.recognize(bitmap)
                } catch (_: Exception) {
                    ""
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
