package com.focusguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceClassifierTest {
    private val classifier = OnDeviceClassifier()

    private fun signal(
        packageName: String,
        texts: List<String> = emptyList(),
        viewIds: Set<String> = emptySet()
    ) = ScreenSignal(packageName, texts, viewIds)

    private fun decide(
        signal: ScreenSignal,
        policy: BlockingPolicy = BlockingPolicy()
    ) = classifier.decide(signal, policy)

    private fun overlay() = setOf("watch_player_overlay")
    private fun shortsFeed() = setOf("reel_recycler")

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

    // --- YouTube: opening the app must never trigger a block ---

    @Test
    fun youtubeWithoutPlayerIsAllowedEvenOnOpening() {
        val result = decide(signal("com.google.android.youtube", listOf("Shorts", "Home", "Subscriptions")))

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun youtubeWithShortsFeedViewIsBlocked() {
        val result = decide(
            signal("com.google.android.youtube", emptyList(), shortsFeed())
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
        assertEquals(true, result.reason.contains("Short", ignoreCase = true))
    }

    @Test
    fun youtubeWithSelectedShortsTextIsBlockedEvenWithUsefulWords() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("selected:Shorts", "Kotlin programming tutorial")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun shortsBlockingDisabledAllowsShorts() {
        val policy = BlockingPolicy(shortFormBlockingEnabled = false)
        val result = decide(
            signal("com.google.android.youtube", emptyList(), shortsFeed()),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun educationalVideoInPlayerIsProductive() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Advanced Kotlin programming tutorial course"),
                overlay()
            )
        )

        assertEquals(OnDeviceClassifier.Classification.PRODUCTIVE, result.classification)
    }

    @Test
    fun entertainmentVideoInPlayerIsSlop() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Funny gaming compilation"),
                overlay()
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun cartoonVideoInPlayerIsSlop() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("SpongeBob SquarePants full episode Nickelodeon"),
                overlay()
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun unverifiableLongFormRequestsOcr() {
        val result = decide(
            signal("com.google.android.youtube", emptyList(), overlay())
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
        assertEquals(true, result.needsMoreText)
    }

    @Test
    fun longFormBlockingDisabledAllowsPlayingVideo() {
        val policy = BlockingPolicy(longFormBlockingEnabled = false)
        val result = decide(
            signal("com.google.android.youtube", listOf("A random video"), overlay()),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    // --- Generic app & game blocking ---

    @Test
    fun blockedAppIsSlop() {
        val policy = BlockingPolicy(blockedApps = setOf("com.supercell.clashofclans"))
        val result = decide(
            signal("com.supercell.clashofclans", listOf("Clash of Clans")),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun unblockedGameIsAllowed() {
        val result = decide(signal("com.supercell.clashofclans", emptyList()))

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    // --- Browser / domain blocking ---

    @Test
    fun browserCustomDomainIsBlocked() {
        val policy = BlockingPolicy(blockedDomains = setOf("badgames.com"))
        val result = decide(
            signal(
                "com.android.chrome",
                listOf("https://www.badgames.com/play"),
                setOf("url_bar")
            ),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun browserSubdomainOfBlockedDomainIsBlocked() {
        val policy = BlockingPolicy(blockedDomains = setOf("badgames.com"))
        val result = decide(
            signal(
                "com.android.chrome",
                listOf("https://play.badgames.com/levels"),
                setOf("search_box_text")
            ),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun browserNsfwDomainIsBlockedWhenEnabled() {
        val policy = BlockingPolicy(nsfwProtectionEnabled = true)
        val result = decide(
            signal(
                "com.android.chrome",
                listOf("https://www.pornhub.com/video/123"),
                setOf("url_bar")
            ),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun nsfwKeywordIsBlockedWhenEnabled() {
        val policy = BlockingPolicy(nsfwProtectionEnabled = true)
        val result = decide(
            signal("com.android.chrome", listOf("watch free xxx videos online"))
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun nsfwBlockedWhenDisabledIsAllowed() {
        val result = decide(
            signal("com.android.chrome", listOf("https://www.pornhub.com/video/123"))
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun tiktokInBrowserIsShortFormBlocked() {
        val result = decide(
            signal("com.android.chrome", listOf("https://www.tiktok.com/@someone"))
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }
}
