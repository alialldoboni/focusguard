package com.focusguard.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureScaleTest {

    @Test
    fun portraitLongEdgeIsDownscaledToTarget() {
        assertEquals(
            480 to 1280,
            ScreenCaptureProvider.downscaleSize(960, 2560, 1280)
        )
    }

    @Test
    fun landscapeLongEdgeIsDownscaledToTarget() {
        assertEquals(
            1280 to 720,
            ScreenCaptureProvider.downscaleSize(2560, 1440, 1280)
        )
    }

    @Test
    fun squareIsDownscaledEvenly() {
        assertEquals(
            1000 to 1000,
            ScreenCaptureProvider.downscaleSize(2000, 2000, 1000)
        )
    }

    @Test
    fun smallerThanMaxIsUnchanged() {
        assertEquals(
            720 to 1280,
            ScreenCaptureProvider.downscaleSize(720, 1280, 1280)
        )
    }

    @Test
    fun typicalQhdScreenIsDownscaled() {
        assertEquals(
            576 to 1280,
            ScreenCaptureProvider.downscaleSize(1440, 3200, 1280)
        )
    }
}
