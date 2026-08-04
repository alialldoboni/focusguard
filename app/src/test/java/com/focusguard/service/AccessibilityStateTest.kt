package com.focusguard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStateTest {

    @Test
    fun flattenedComponentIsDetected() {
        assertTrue(
            FocusAccessibilityService.accessibilityServicesContain(
                "com.other/com.other.AccessibilityService:" +
                    "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }

    @Test
    fun shortFlattenedComponentIsDetected() {
        assertTrue(
            FocusAccessibilityService.accessibilityServicesContain(
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }

    @Test
    fun barePackageNameIsDetected() {
        assertTrue(
            FocusAccessibilityService.accessibilityServicesContain(
                "com.focusguard",
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }

    @Test
    fun substringMatchDoesNotCauseFalsePositive() {
        assertFalse(
            FocusAccessibilityService.accessibilityServicesContain(
                "com.focusguard2/com.focusguard2.service.FocusAccessibilityService",
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }

    @Test
    fun blankServicesMeansNotEnabled() {
        assertFalse(
            FocusAccessibilityService.accessibilityServicesContain(
                "",
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }

    @Test
    fun nullServicesMeansNotEnabled() {
        assertFalse(
            FocusAccessibilityService.accessibilityServicesContain(
                null,
                "com.focusguard/com.focusguard.service.FocusAccessibilityService",
                "com.focusguard/.service.FocusAccessibilityService",
                "com.focusguard"
            )
        )
    }
}
