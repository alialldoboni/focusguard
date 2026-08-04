package com.focusguard.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Display
import android.view.accessibility.AccessibilityService.ScreenshotResult
import android.view.accessibility.AccessibilityService.TakeScreenshotCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Captures the current screen through the accessibility service and returns a
 * downscaled ARGB_8888 bitmap. Capture is non-blocking (suspends), runs on a
 * dedicated single-thread executor, and every intermediate allocation is
 * recycled in a `finally` block so no bitmap or hardware buffer leaks.
 *
 * Downscaling keeps OCR latency low: a 1440x3200 capture is ~18 MB, but ML Kit
 * accuracy at ~1280 px on the long edge is essentially identical and the frame
 * is several times faster.
 */
class ScreenCaptureProvider(private val service: AccessibilityService) : CaptureSource {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focusguard-capture").apply { priority = Thread.MAX_PRIORITY }
    }

    override suspend fun capture(): Bitmap? = captureDownscaled()

    suspend fun captureDownscaled(maxDim: Int = DEFAULT_MAX_DIM): Bitmap? =
        withContext(Dispatchers.Default) {
            val result = captureScreenshot() ?: return@withContext null
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
                if (scaled !== source) source?.recycle()
                wrapped?.recycle()
                result.hardwareBuffer.close()
            }
        }

    fun shutdown() {
        executor.shutdownNow()
    }

    private suspend fun captureScreenshot(): ScreenshotResult? =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        if (cont.isActive) {
                            cont.resume(result)
                        } else {
                            result.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

    companion object {
        const val DEFAULT_MAX_DIM = 1280

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
