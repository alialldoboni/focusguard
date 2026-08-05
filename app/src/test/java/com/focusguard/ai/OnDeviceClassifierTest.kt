package com.focusguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceClassifierTest {
    private val classifier = OnDeviceClassifier()

    private fun signal(
        packageName: String,
        texts: List<String> = emptyList(),
        viewIds: Set<String> = emptySet(),
        playerFullScreen: Boolean = false
    ) = ScreenSignal(packageName, texts, viewIds, playerFullScreen)

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
    fun unverifiableEmptyTextIsDormant() {
        // Empty text tree -> the classifier never forces OCR; it stays dormant.
        // OCR arming now lives in the service (event-driven snapshot).
        val result = decide(
            signal("com.google.android.youtube", emptyList(), overlay())
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
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

    @Test
    fun playerControlsTextIndicatesActivePlayback() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Mute", "00:12 / 10:00", "Fullscreen", "A random vlog")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun playerControlsWithProductiveTitleAreAllowed() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Mute", "Pause", "Advanced Kotlin programming tutorial")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.PRODUCTIVE, result.classification)
    }

    @Test
    fun shortsWordDetectedWhilePlayingIsBlocked() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Shorts", "Mute", "Some creator name")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun shortsTabOnHomeDoesNotBlock() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Shorts", "Home", "Subscriptions", "Trending now")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun playerViewIdVariantSubstringDetectsPlayback() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Some video"),
                setOf("youtube_shorts_reel_view")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    // --- PATH B: empty/suppressed text tree (Realme ColorOS, Xiaomi MIUI) ---

    @Test
    fun emptyTextWithSlimStatusBarPlayerAloneIsDormant() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("slim_status_bar_player_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun backgroundChromeOnHomeStaysDormant() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf(
                    "action_bar_root", "content", "more_drawer_container",
                    "slim_status_bar_player_container", "reel_time_bar"
                )
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun emptyTextWithUnknownPlayerVariantStaysDormant() {
        // Empty text + an OEM player variant id: the classifier stays dormant;
        // the service arms OCR on the transition event instead.
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("realme_player_overlay_v2")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun emptyTextOnHomeWithoutPlayerIsAllowed() {
        val result = decide(
            signal("com.google.android.youtube", emptyList(), emptySet())
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun emptyTextWithShortsShelfDoesNotBlock() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("reel_shelf", "shorts_shelf")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun emptyTextWithVariantShortsContainerIsBlocked() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("shorts_reel_view_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    // --- HOME FEED / BROWSE PROTECTION (Path B OCR reading recommendation tiles) ---

    @Test
    fun homeFeedDenseTilesAreAllowedEvenWithWeakPlayerContainer() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "Home", "Subscriptions",
                    "5 Amazing Tech Gadgets 2.1M views 3 days ago",
                    "Cooking Basics 5M views 1 day ago",
                    "Gaming Highlights 800K views 2 days ago"
                ),
                setOf("player_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun homeFeedNavHeadersAreAllowedEvenWithWeakPlayerContainer() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "Home", "Subscriptions", "Library",
                    "A random video 1.2M views"
                ),
                setOf("player_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun homeFeedShortsTabWithWeakContainerIsAllowed() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "Home", "Shorts", "Subscriptions",
                    "5 Amazing Gadgets 2.1M views 3 days ago"
                ),
                setOf("player_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun watchScreenWithWeakVariantContainerButControlsStillBlocks() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Mute", "00:12 / 10:00", "A random vlog"),
                setOf("realme_player_overlay_v2")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun watchScreenWithExactContainerBlocksNonProductive() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("A random video"),
                setOf("watch_player_overlay")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    // --- Background mini-player / lingering chrome must never block the home feed ---

    @Test
    fun homeFeedWithBackgroundMiniPlayerIsAllowed() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "Home", "Shorts", "Subscriptions",
                    "5 Amazing Gadgets 2.1M views 3 days ago",
                    "Cooking Basics 5M views 1 day ago"
                ),
                setOf("slim_status_bar_player_container", "bottom_tab", "thumbnail")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun backgroundMiniPlayerOnHomeDoesNotTriggerOcr() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("slim_status_bar_player_container", "bottom_tab")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun reelTimeBarOnHomeDoesNotBlock() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("Home", "Shorts", "A random video 1M views"),
                setOf("reel_time_bar")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
    }

    @Test
    fun backgroundMiniPlayerAloneIsDormant() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("slim_status_bar_player_container")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun emptyTextFullScreenVariantStaysDormant() {
        // Full-screen geometry alone cannot make an unreadable screen blockable;
        // OCR is armed by the service from the transition event.
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("realme_player_overlay_v2"),
                playerFullScreen = true
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    // --- Full-screen geometry: the watch-transition discriminator ---

    @Test
    fun emptyTextFullScreenPlayerIsDormantNotBlocked() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("slim_status_bar_player_container", "bottom_tab", "feed"),
                playerFullScreen = true
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun fullScreenBackgroundPlayerWithTextBlocksNonProductive() {
        // Directive #2: a background chrome node with absolute full-screen
        // coordinates resolves as a real watch session once text is present.
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("A random video"),
                setOf("slim_status_bar_player_container"),
                playerFullScreen = true
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun miniPlayerBarOnHomeStaysDormantEvenWithPlayerChrome() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                emptyList(),
                setOf("slim_status_bar_player_container", "bottom_tab", "thumbnail"),
                playerFullScreen = false
            )
        )

        assertEquals(OnDeviceClassifier.Classification.ALLOWED, result.classification)
        assertEquals(false, result.needsMoreText)
    }

    @Test
    fun fullScreenWatchWithResidualHomeTextIsNotSwallowed() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "Home", "Subscriptions",
                    "A random video 1.2M views",
                    "0:12 / 10:00"
                ),
                setOf("watch_player_overlay"),
                playerFullScreen = true
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun activeTimecodeDetectsWatchState() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf("12:34 / 25:00", "A random video")
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    // --- Watch-page OCR text must never be swallowed by the browse guard ---

    @Test
    fun watchPageOcrTextIsNotSwallowedByBrowseGuard() {
        // Exact scenario from logcat: watch page OCR with channel handle,
        // likes, views/ago, comments and Subscribe — the suggestion rail below
        // also contains "views"/"ago" which used to trip the browse guard.
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "3:55 @6",
                    "NEAL FUN",
                    "Squares are 83.2% circle",
                    "@webgoatguy 20K likes 758K views 2w ago ...more",
                    "Comments 1.8K",
                    "Subscribe...",
                    "Suggested: Another video 1M views 3 days ago",
                    "More suggestions 45K views 1 month ago"
                )
            )
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun watchPageWithProductiveTitleIsAllowed() {
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "@webgoatguy 20K likes",
                    "Comments 1.8K",
                    "Subscribe",
                    "Advanced Kotlin programming tutorial"
                )
            )
        )

        assertEquals(OnDeviceClassifier.Classification.PRODUCTIVE, result.classification)
    }

    @Test
    fun channelHandleOnHomeCardIsStillBrowse() {
        // Channel handles also appear on home video cards; without likes/comments/
        // subscribe the dense-tile feed must still be treated as browse.
        val result = decide(
            signal(
                "com.google.android.youtube",
                listOf(
                    "@channel 1.2M views 2 days ago",
                    "Home", "Subscriptions",
                    "Another title 5K views 1 day ago"
                )
            )
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
            signal("com.android.chrome", listOf("watch free xxx videos online")),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun nsfwKeywordMatchingIsCaseInsensitive() {
        val policy = BlockingPolicy(nsfwProtectionEnabled = true)
        val result = decide(
            signal("com.android.chrome", listOf("WATCH FREE XXX VIDEOS")),
            policy
        )

        assertEquals(OnDeviceClassifier.Classification.SLOP, result.classification)
    }

    @Test
    fun nsfwDomainMatchingIsCaseInsensitive() {
        val policy = BlockingPolicy(nsfwProtectionEnabled = true)
        val result = decide(
            signal(
                "com.android.chrome",
                listOf("https://WWW.PornHub.COM/video/123"),
                setOf("url_bar")
            ),
            policy
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
