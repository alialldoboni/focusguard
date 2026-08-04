package com.focusguard.ocr

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val captureProvider: ScreenCaptureProvider,
    private val recognizer: OcrTextRecognizer
) {

    private val mutex = Mutex()
    private val inFlight = AtomicReference<String?>(null)
    private val lastAttemptElapsed = AtomicLong(0L)

    suspend fun recognize(packageName: String): String {
        if (inFlight.get() != null) return ""
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptElapsed.get() < COOLDOWN_MS) return ""

        return mutex.withLock {
            if (!inFlight.compareAndSet(null, packageName)) return@withLock ""
            try {
                val bitmap = captureProvider.captureDownscaled() ?: return@withLock ""
                try {
                    recognizer.recognize(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                lastAttemptElapsed.set(SystemClock.elapsedRealtime())
                inFlight.set(null)
            }
        }
    }

    fun shutdown() {
        captureProvider.shutdown()
        recognizer.close()
    }

    companion object {
        const val COOLDOWN_MS = 5_000L
    }
}
