package com.focusguard.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPipelineTest {

    @Test
    fun duplicateInFlightRequestIsDropped() = runBlocking {
        var captureCount = 0
        val gate = CompletableDeferred<Unit>()
        val pipeline = OcrPipeline(
            CaptureSource {
                captureCount++
                gate.await()
                null
            },
            TextRecognizer { "" }
        )

        val first = launch { pipeline.recognize("pkg") }
        yield()
        val second = async { pipeline.recognize("pkg") }

        assertEquals("", second.await())
        gate.complete(Unit)
        first.join()
        assertEquals(1, captureCount)
    }

    @Test
    fun failedAttemptTriggersCooldown() = runBlocking {
        var captureCount = 0
        val pipeline = OcrPipeline(
            CaptureSource {
                captureCount++
                null
            },
            TextRecognizer { "" }
        )

        assertEquals("", pipeline.recognize("pkg"))
        assertEquals("", pipeline.recognize("pkg"))
        assertEquals(1, captureCount)
    }
}
