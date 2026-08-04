package com.focusguard.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPipelineTest {

    @Test
    fun duplicateInFlightRequestIsSkipped() = runBlocking {
        var captureCount = 0
        val gate = CompletableDeferred<Unit>()
        val pipeline = OcrPipeline(
            object : CaptureSource {
                override suspend fun capture(): Bitmap? {
                    captureCount++
                    gate.await()
                    return null
                }
            },
            object : TextRecognizer {
                override suspend fun recognize(bitmap: Bitmap): String = ""
            }
        )

        val first = launch { pipeline.recognize("pkg") }
        yield()
        val second = async { pipeline.recognize("pkg") }

        assertEquals(OcrResult.Skipped, second.await())
        gate.complete(Unit)
        first.join()
        assertEquals(1, captureCount)
    }

    @Test
    fun failedAttemptTriggersCooldown() = runBlocking {
        var captureCount = 0
        val pipeline = OcrPipeline(
            object : CaptureSource {
                override suspend fun capture(): Bitmap? {
                    captureCount++
                    return null
                }
            },
            object : TextRecognizer {
                override suspend fun recognize(bitmap: Bitmap): String = ""
            }
        )

        assertEquals(OcrResult.NoText, pipeline.recognize("pkg"))
        assertEquals(OcrResult.Skipped, pipeline.recognize("pkg"))
        assertEquals(1, captureCount)
    }

    @Test
    fun hungCaptureTimesOutAndReleasesPipeline() = runBlocking {
        val neverCompletes = CompletableDeferred<Bitmap?>()
        val pipeline = OcrPipeline(
            object : CaptureSource {
                override suspend fun capture(): Bitmap? = neverCompletes.await()
            },
            object : TextRecognizer {
                override suspend fun recognize(bitmap: Bitmap): String = ""
            },
            attemptTimeoutMs = 200
        )

        // A capture that never invokes its callback must time out instead of
        // holding inFlight/the mutex forever.
        assertEquals(OcrResult.NoText, pipeline.recognize("pkg"))

        // The pipeline is NOT wedged: the immediate retry returns a decision
        // (cooldown -> Skipped) instead of hanging forever.
        assertEquals(OcrResult.Skipped, pipeline.recognize("pkg"))
    }
}
