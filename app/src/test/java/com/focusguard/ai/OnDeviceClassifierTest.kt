package com.focusguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceClassifierTest {
    private val classifier = OnDeviceClassifier()

    @Test
    fun knownDistractingPackageIsSlop() {
        assertEquals(
            OnDeviceClassifier.Classification.SLOP,
            classifier.classify("com.instagram.android", emptyList())
        )
    }

    @Test
    fun knownProductivePackageIsProductive() {
        assertEquals(
            OnDeviceClassifier.Classification.PRODUCTIVE,
            classifier.classify("org.wikipedia", emptyList())
        )
    }

    @Test
    fun educationalYouTubeContentIsProductive() {
        assertEquals(
            OnDeviceClassifier.Classification.PRODUCTIVE,
            classifier.classify(
                "com.google.android.youtube",
                listOf("Advanced Kotlin programming tutorial course")
            )
        )
    }

    @Test
    fun entertainmentYouTubeContentIsSlop() {
        assertEquals(
            OnDeviceClassifier.Classification.SLOP,
            classifier.classify(
                "com.google.android.youtube",
                listOf("Funny gaming compilation")
            )
        )
    }

    @Test
    fun cartoonYouTubeContentIsSlop() {
        assertEquals(
            OnDeviceClassifier.Classification.SLOP,
            classifier.classify(
                "com.google.android.youtube",
                listOf("SpongeBob SquarePants full episode Nickelodeon")
            )
        )
    }

    @Test
    fun oneUiHomeIsAlwaysAllowed() {
        assertEquals(
            OnDeviceClassifier.Classification.ALLOWED,
            classifier.classify(
                "com.sec.android.app.launcher",
                listOf("YouTube", "Instagram", "Shorts")
            )
        )
    }

    @Test
    fun unknownYouTubeContentIsBlockedByStrictMode() {
        val decision = classifier.decide(
            "com.google.android.youtube",
            listOf("A day in my life")
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, decision.classification)
        assertEquals(true, decision.needsMoreText)
        assertEquals(
            true,
            decision.reason.contains("could not verify", ignoreCase = true)
        )
    }

    @Test
    fun selectedYouTubeShortsFeedIsBlockedEvenWithUsefulWords() {
        assertEquals(
            OnDeviceClassifier.Classification.SLOP,
            classifier.classify(
                "com.google.android.youtube",
                listOf("selected:Shorts", "Kotlin programming tutorial")
            )
        )
    }

    @Test
    fun socialMediaIsBlockedRegardlessOfVisibleContent() {
        assertEquals(
            OnDeviceClassifier.Classification.SLOP,
            classifier.classify(
                "com.facebook.katana",
                listOf("University lecture and research")
            )
        )
    }

    @Test
    fun unknownContentDefaultsToAllowed() {
        assertEquals(
            OnDeviceClassifier.Classification.ALLOWED,
            classifier.classify("com.example.reader", listOf("A normal article"))
        )
    }
}
