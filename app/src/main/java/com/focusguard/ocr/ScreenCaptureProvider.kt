package com.focusguard.ocr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Captures the current screen through the accessibility service and returns a
 * downscaled ARGB_8888 bitmap. Capture is non-blocking (suspends), runs on a
 * dedicated single-thread executor, and every intermediate allocation is
 * recycled and nulled in a `finally` block so no bitmap or hardware buffer is
 * retained across the call frame.
 *
 * ZERO-RETENTION PRIVACY: frames live only in volatile RAM for a single pass.
 * Nothing is written to disk, cached to storage, or held beyond the returned
 * bitmap (which the caller recycles in its own `finally`).
 *
 * FAILURE DIAGNOSTICS: every failure mode — callback `onFailure(errorCode)` and
 * a `takeScreenshot()` that never calls back (timeout) — is logged with the
 * elapsed time and reason so watch-page capture limitations are traceable.
 */
class ScreenCaptureProvider(private val service: AccessibilityService) : CaptureSource {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focusguard-capture").apply { priority = Thread.MAX_PRIORITY }
    }

    @Volatile
    private var consecutiveCaptureFailures = 0

    override suspend fun capture(): Bitmap? = captureDownscaled()

    suspend fun captureDownscaled(maxDim: Int = DEFAULT_MAX_DIM): Bitmap? =
        withContext(Dispatchers.Default) {
            val startElapsedMs = SystemClock.elapsedRealtime()
            // Hard timeout: AccessibilityService.takeScreenshot() may never invoke
            // its callback (capability not granted, secure window, OEM quirk). Without
            // this, the continuation would suspend forever and wedge the whole OCR
            // pipeline (in-flight marker + mutex held), stalling every future scan.
            val result = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                captureScreenshot()
            }
            if (result == null) {
                consecutiveCaptureFailures++
                android.util.Log.d(
                    "FocusGuard",
                    "Path B: Screenshot capture failed or timed out after " +
                        "${SystemClock.elapsedRealtime() - startElapsedMs}ms " +
                        "(limit ${SCREENSHOT_TIMEOUT_MS}ms, consecutiveFailures=" +
                        "$consecutiveCaptureFailures)"
                )
                return@withContext null
            }
            consecutiveCaptureFailures = 0

            var wrapped: Bitmap? = null
            var source: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                source = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    ?: return@withContext null
                scaled = downscale(source, maxDim)
                scaled
            } finally {
                // Zero-retention: release native pixel memory for every temporary
                // allocation immediately and drop the references so nothing survives
                // the call frame. `scaled` (when different from `source`) is owned
                // by the caller and recycled in the OCR pipeline's own finally.
                if (scaled !== source) source?.recycle()
                source = null
                wrapped?.recycle()
                wrapped = null
                scaled = null
                result.hardwareBuffer.close()
            }
        }

    fun shutdown() {
        executor.shutdownNow()
    }

    private suspend fun captureScreenshot(): ScreenshotResult? =
        suspendCancellableCoroutine { cont ->
            val callbackStartMs = SystemClock.elapsedRealtime()
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        android.util.Log.d(
                            "FocusGuard",
                            "Path B: Screenshot callback received, status: success " +
                                "(after ${SystemClock.elapsedRealtime() - callbackStartMs}ms)"
                        )
                        if (cont.isActive) {
                            cont.resume(result)
                        } else {
                            result.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.d(
                            "FocusGuard",
                            "Path B: Screenshot callback received, status: null " +
                                "(errorCode=$errorCode, reason=" +
                                "${describeScreenshotError(errorCode)}, after " +
                                "${SystemClock.elapsedRealtime() - callbackStartMs}ms)"
                        )
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

    companion object {
        const val DEFAULT_MAX_DIM = 1280
        const val SCREENSHOT_TIMEOUT_MS = 5_000L

        internal fun describeScreenshotError(errorCode: Int): String = when (errorCode) {
            1 -> "internal error"
            2 -> "no accessibility access"
            3 -> "invalid display"
            4 -> "secure window (FLAG_SECURE)"
            5 -> "capture interval too short"
            else -> "unknown"
        }

        internal fun downscaleSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= maxDim) return width to height
            val scale = maxDim.toFloat() / longest
            return (width * scale).toInt() to (height * scale).toInt()
        }

        fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
            val (width, height) = downscaleSize(bitmap.width, bitmap.height, maxDim)
            if (width == bitmap.width && height == bitmap.height) return bitmap
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
}
