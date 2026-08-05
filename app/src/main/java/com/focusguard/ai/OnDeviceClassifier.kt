package com.focusguard.ai

/**
 * What the accessibility service could observe on screen: visible text, the
 * resource ids (normalized, e.g. `watch_player_overlay`) of the nodes in the
 * active window, whether a player-like container actually occupies most of the
 * screen (`playerFullScreen`), and the subset of ids that represent player
 * layers (`playerLikeIds`, used by the service to detect a player node newly
 * mounting after a UI transition).
 */
data class ScreenSignal(
    val packageName: String,
    val texts: List<String> = emptyList(),
    val viewIds: Set<String> = emptySet(),
    val playerFullScreen: Boolean = false,
    val playerLikeIds: Set<String> = emptySet()
) {
    val signature: String
        get() = texts.joinToString("|") + "::" + viewIds.sorted().joinToString(",") +
            "::" + playerFullScreen

    fun withText(text: String): ScreenSignal =
        copy(texts = (texts + text).distinct())
}

/** Live feature toggles + block lists, fed from UserSettings on every decision. */
data class BlockingPolicy(
    val nsfwProtectionEnabled: Boolean = false,
    val shortFormBlockingEnabled: Boolean = true,
    val longFormBlockingEnabled: Boolean = true,
    val blockedApps: Set<String> = emptySet(),
    val blockedDomains: Set<String> = emptySet()
) {
    fun key(): String = listOf(
        nsfwProtectionEnabled.toString(),
        shortFormBlockingEnabled.toString(),
        longFormBlockingEnabled.toString(),
        blockedApps.sorted().joinToString(","),
        blockedDomains.sorted().joinToString(",")
    ).joinToString("|")
}

class OnDeviceClassifier(
    private val localClassifier: ProductivityClassifier? = null
) {

    enum class Classification { PRODUCTIVE, SLOP, ALLOWED }

    data class Decision(
        val classification: Classification,
        val reason: String,
        val needsMoreText: Boolean = false
    )

    companion object {
        /** Min slop probability for the local AI to force a SLOP decision. */
        const val AI_SLOP_THRESHOLD = 0.6f

        /** Min productive probability for the local AI to force a PRODUCTIVE decision. */
        const val AI_PRODUCTIVE_THRESHOLD = 0.6f
    }

    private val usefulKeywords = setOf(
        "tutorial", "lecture", "course", "lesson", "learn", "study", "education",
        "explain", "explained", "teach", "class", "university", "college", "school",
        "math", "physics", "chemistry", "biology", "history", "science",
        "programming", "code", "coding", "python", "java", "kotlin", "javascript",
        "react", "flutter", "android", "ios", "swift", "rust", "golang",
        "how to", "beginner", "advanced", "guide", "walkthrough",
        "homework", "exam", "quiz", "assignment", "research", "paper",
        "github", "stackoverflow", "documentation", "wikipedia",
        "tedx", "khan academy", "coursera", "udemy", "edx",
        "seminar", "workshop", "training", "bootcamp",
        "documentary", "case study", "analysis", "audiobook", "full course",
        "masterclass", "conference", "keynote", "engineering", "design",
        "تعليم", "شرح", "دورة", "درس", "تعلم", "محاضرة", "برمجة",
        "علوم", "رياضيات", "وثائقي", "كيفية"
    )

    private val distractingKeywords = setOf(
        "speedrun", "speed run", "minecraft", "gaming", "gameplay", "playthrough",
        "funny", "comedy", "prank", "challenge", "try not to laugh",
        "viral", "trending", "fyp", "memes", "meme", "fail", "fails",
        "compilation", "asmr", "reaction", "reacts", "vlog", "haul",
        "gossip", "drama", "tea", "beef", "exposed", "unboxing",
        "stream", "twitch", "fortnite", "roblox", "pubg", "valorant",
        "call of duty", "among us", "fall guys", "genshin", "apex", "overwatch",
        "oddly satisfying", "satisfying", "brain rot", "doomscroll",
        "cartoon", "full episode", "episode", "movie clip", "tv clip",
        "spongebob", "nickelodeon", "anime", "music video", "trailer",
        "celebrity", "highlights", "best moments",
        "مضحك", "مقلب", "تحدي", "ميمز", "ألعاب", "العاب", "كرتون",
        "حلقة كاملة", "سبونج بوب", "دراما"
    )

    private val shortFormIndicators = setOf(
        "#shorts", "shorts player", "youtube shorts", "swipe up for next video",
        "swipe for next", "use this sound", "reels", "reel player", "tiktok",
        "شورتس", "فيديوهات قصيرة", "ريلز"
    )

    private val nsfwKeywords = setOf(
        "porn", "xxx", "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "onlyfans", "chaturbate", "stripchat", "nsfw", "erotic", "hentai",
        "sex video", "adult video", "cam girl", "camgirl", "escort", "babestation",
        "成人", "エロ", "سكس", "إباحي", "بورن"
    )

    private val nsfwDomains = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "onlyfans.com", "chaturbate.com", "stripchat.com",
        "hentaihaven.org", "brazzers.com", "bangbros.com", "x3mag.com"
    )

    private val safePackages = setOf(
        "com.whatsapp", "com.google.android.gm", "com.google.android.apps.messaging",
        "com.android.contacts", "com.android.dialer", "com.google.android.apps.maps",
        "com.google.android.apps.photos", "com.spotify.music", "com.netflix.mediaclient",
        "com.android.settings", "com.android.systemui", "com.android.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.calendar", "com.google.android.apps.tachyon",
        "com.skype.raider", "com.microsoft.teams", "com.discord",
        "org.telegram.messenger", "com.evernote", "com.zoho.notebook",
        "notion.id", "com.ubercab", "com.headspace.android",
        "com.fitbit.FitBitMobile", "com.google.android.apps.fitness"
    )

    private val productivePackages = setOf(
        "com.github", "org.wikipedia", "com.khanacademy.android",
        "com.coursera.android", "com.udemy.app", "com.google.android.apps.docs",
        "com.google.android.apps.sheets", "com.google.android.apps.slides",
        "com.microsoft.office.onenote", "com.studysmarter.app",
        "com.brill.brillreference", "com.researchgate.app"
    )

    private val socialMediaPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",
        "com.snapchat.android",
        "com.pinterest",
        "com.pinterest.android",
        "com.pinterest.twa",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.linkedin.android",
        "xyz.blueskyweb.app",
        "com.vkontakte.android",
        "com.tumblr"
    )

    private val youtubePackages = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube"
    )

    private val mixedBrowserPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox"
    )

    private val browserUrlViewIds = setOf(
        "url_bar",
        "search_box_text",
        "location_bar",
        "omnibox"
    )

    /**
     * FULL-SCREEN watch chrome. Only these prove a video/shorts session actually
     * fills the screen — sufficient to trigger Path B OCR and to satisfy the
     * "focused player interface" requirement for a SLOP decision.
     */
    private val focusedPlayerContainerIds = setOf(
        "watch_player_overlay",
        "player_view",
        "player_controls_view",
        "reel_recycler",
        "shorts_player_page_container",
        "reel_player_page_container"
    )

    /**
     * Substrings identifying non-focused chrome (mini-player bars, Shorts
     * progress bars, shelves, thumbnails, previews). Excluded from both the
     * Path B trigger and the Shorts-container detector so static home-screen
     * elements never look like an active watch/shorts session.
     */
    private val backgroundChromeTokens = listOf(
        "status_bar", "mini_player", "time_bar", "shelf", "thumbnail", "progress", "preview"
    )

    /**
     * Substrings identifying browse/home-feed chrome (bottom navigation, tab
     * bars). Used to detect that a lingering background mini-player belongs to
     * the home feed rather than a focused watch page.
     */
    private val browseViewTokens = listOf(
        "bottom_tab", "bottom_nav", "nav_bar", "main_nav", "home_tab", "pill_bar"
    )

    /**
     * Substring tokens that identify an *active* Shorts container. Deliberately
     * narrower than a bare "reel"/"shorts" substring so static home-screen nodes
     * such as `reel_shelf` / `shorts_shelf` never look like a playing Short.
     */
    private val shortsContainerTokens = listOf(
        "reel_recycler", "reel_player", "reel_container", "reel_page", "reel_view",
        "shorts_reel", "shorts_player", "shorts_recycler", "shorts_container",
        "shorts_page", "shorts_feed", "shorts_immersive", "shorts_view"
    )

    /**
     * Player-control signals exposed by YouTube's UI tree. These appear while a
     * video is actually playing and are used as evidence of active playback,
     * because resource ids vary across YouTube versions and OEM builds.
     */
    private val playerKeywords = listOf(
        "pause", "replay", "mute", "fullscreen", "seek bar", "00:",
        "shorts player", "swipe up for next video", "use this sound"
    )

    /** Active video duration timestamps such as `0:00 / 10:00` or `12:34 / 25:00`. */
    private val timecodePattern = Regex("""\d{1,2}:\d{2}\s*/\s*\d{1,2}:\d{2}""")

    fun isAlwaysBlockedPackage(packageName: String): Boolean =
        packageName in socialMediaPackages

    fun isYouTubePackage(packageName: String): Boolean =
        packageName in youtubePackages

    fun classify(packageName: String, textNodes: List<String>): Classification =
        decide(packageName, textNodes).classification

    fun decide(packageName: String, textNodes: List<String>): Decision =
        decide(ScreenSignal(packageName, textNodes), BlockingPolicy())

    fun decide(signal: ScreenSignal, policy: BlockingPolicy): Decision {
        val packageName = signal.packageName
        if (isAlwaysBlockedPackage(packageName)) {
            return Decision(
                Classification.SLOP,
                "This social-media app is blocked by your FocusGuard policy."
            )
        }
        if (packageName in policy.blockedApps) {
            return Decision(
                Classification.SLOP,
                "This app or game is blocked by your FocusGuard app & game list."
            )
        }
        if (policy.nsfwProtectionEnabled && containsNsfw(signal)) {
            return Decision(
                Classification.SLOP,
                "NSFW or adult content is blocked by your FocusGuard policy."
            )
        }
        if (packageName in productivePackages) {
            return Decision(Classification.PRODUCTIVE, "This is a productive app.")
        }
        if (packageName in safePackages) {
            return Decision(Classification.ALLOWED, "This app is allowed.")
        }
        if (isYouTubePackage(packageName)) {
            return decideYouTube(signal, policy)
        }
        if (packageName in mixedBrowserPackages) {
            return decideBrowser(signal, policy)
        }
        return Decision(Classification.ALLOWED, "This app is allowed.")
    }

    /**
     * Dual-path YouTube decision engine.
     *
     * PATH A (text tree available — Samsung/Pixel/stock AOSP): titles, Shorts
     * keywords and the productive whitelist are evaluated directly from the
     * accessibility text, no OCR needed.
     *
     * PATH B (text tree empty/suppressed — Realme ColorOS, Xiaomi MIUI/HyperOS):
     * OCR is armed by the SERVICE from real UI transitions (window-state / pane
     * changes and player-node mounts), never by this classifier. Here we only
     * make decisions we can be certain of without reading the screen: an
     * unambiguous Shorts container blocks directly, everything else stays
     * DORMANT (ALLOWED) so an unreadable screen can never cause a false block.
     */
    private fun decideYouTube(signal: ScreenSignal, policy: BlockingPolicy): Decision {
        val fullText = signal.texts.joinToString(" ").lowercase()

        // ---- PATH A: accessibility text tree is available ----
        if (fullText.isNotBlank()) {
            return decideYouTubeFromText(signal, fullText, policy)
        }

        // ---- PATH B: text tree empty/suppressed ----
        if (isShortsContainer(signal)) {
            return if (policy.shortFormBlockingEnabled) {
                Decision(
                    Classification.SLOP,
                    "YouTube Shorts and short-form content are blocked by your " +
                        "FocusGuard policy."
                )
            } else {
                Decision(Classification.ALLOWED, "Short-form blocking is disabled.")
            }
        }
        return Decision(
            Classification.ALLOWED,
            "No readable content; awaiting snapshot."
        )
    }

    private fun decideYouTubeFromText(
        signal: ScreenSignal,
        fullText: String,
        policy: BlockingPolicy
    ): Decision {
        // A watch page is present when we have either real playback evidence
        // (container/controls/timecode) OR unambiguous watch-page text markers
        // ("likes", "comments", "subscribe") that never appear on home cards.
        val watchContext = hasActivePlayer(signal, fullText) || hasWatchPageText(fullText)

        // HOME FEED & BROWSE PROTECTION: the browse feed ALSO contains many
        // "views"/"ago" recommendation tiles, so never treat dense tiles as a
        // watch session. But a genuine watch page must never be swallowed by this
        // guard — its suggestion rail trips the same "dense tiles" heuristic.
        if (!watchContext && isBrowseOrHomeText(fullText)) {
            return Decision(
                Classification.ALLOWED,
                "YouTube home or browse feed — nothing to block."
            )
        }

        if (isShortsActive(signal, fullText)) {
            return if (policy.shortFormBlockingEnabled) {
                Decision(
                    Classification.SLOP,
                    "YouTube Shorts and short-form content are blocked by your " +
                        "FocusGuard policy."
                )
            } else {
                Decision(Classification.ALLOWED, "Short-form blocking is disabled.")
            }
        }

        if (!watchContext) {
            return Decision(
                Classification.ALLOWED,
                "No active YouTube video player detected."
            )
        }

        if (!policy.longFormBlockingEnabled) {
            return Decision(Classification.ALLOWED, "Long-form blocking is disabled.")
        }

        // SLOP is strictly reserved for real watch screens. A mere substring
        // container (e.g. a home autoplay preview's `player_container`) with no
        // control keywords, known player chrome or watch-page text is not enough
        // to block.
        if (!hasFocusedPlayerInterface(signal, fullText)) {
            return Decision(
                Classification.ALLOWED,
                "No confirmed video player interface."
            )
        }

        // AUTOPLAY PREVIEW GUARD: the home feed autoplays muted previews that
        // mount a real player container (`player_view`-family) inside a card,
        // which can make the focused-interface checks above pass on the browse
        // screen. If the text is browse-like (dense tiles/nav), there are NO
        // watch-page markers, and the player is not geometrically full-screen,
        // this is a card preview — never block, no matter what player ids are
        // present. A genuine watch page always has likes/comments/subscribe or
        // full-screen geometry, so it still reaches the scoring below.
        val browseContext = isBrowseOrHomeText(fullText)
        if (browseContext && !hasWatchPageText(fullText) && !signal.playerFullScreen) {
            return Decision(
                Classification.ALLOWED,
                "Home feed autoplay preview — nothing to block."
            )
        }

        // LOCAL AI FIRST: when a model is loaded and confident, its decision wins.
        // Otherwise fall back to the legacy keyword baseline. This point is shared
        // by PATH A (accessibility text) and PATH B (OCR text), so the AI applies
        // to both evaluation paths.
        val ai = runAiClassification(fullText)
        if (ai != null) {
            return when {
                ai.label == "slop" && ai.slopScore >= AI_SLOP_THRESHOLD ->
                    Decision(
                        Classification.SLOP,
                        "On-device AI flagged this content as distracting."
                    )
                ai.label == "productive" && ai.productiveScore >= AI_PRODUCTIVE_THRESHOLD ->
                    Decision(
                        Classification.PRODUCTIVE,
                        "On-device AI classified this content as useful."
                    )
                else -> scoreYouTubeByHeuristic(fullText)
            }
        }
        return scoreYouTubeByHeuristic(fullText)
    }

    /**
     * Runs the local AI classifier (if loaded). Never throws: any failure yields
     * `null` and the caller uses the heuristic baseline. This keeps the pure
     * classifier unit-testable — no Android/Log calls are made here.
     */
    private fun runAiClassification(fullText: String): ClassificationResult? {
        val classifier = localClassifier ?: return null
        if (!classifier.isReady()) return null
        return try {
            classifier.classify(fullText)
        } catch (_: Exception) {
            null
        }
    }

    /** Legacy keyword baseline, used when no AI model is loaded or it is uncertain. */
    private fun scoreYouTubeByHeuristic(fullText: String): Decision {
        val usefulScore = usefulKeywords.count(fullText::contains)
        val distractingScore = distractingKeywords.count(fullText::contains)

        return when {
            usefulScore >= 2 && usefulScore > distractingScore ->
                Decision(
                    Classification.PRODUCTIVE,
                    "The video title or channel contains useful content signals."
                )
            distractingScore > 0 ->
                Decision(
                    Classification.SLOP,
                    "The video title or channel contains entertainment signals."
                )
            else ->
                Decision(
                    Classification.SLOP,
                    "Long-form video could not be verified as useful."
                )
        }
    }

    /**
     * Unambiguous watch-page text markers that never appear on the home/browse
     * feed: the like count, the comment section, and the Subscribe button.
     * (Channel handles like `@channel` are NOT used here — they also appear on
     * home video cards.)
     */
    private fun hasWatchPageText(fullText: String): Boolean =
        fullText.contains("likes") ||
            fullText.contains("comments") ||
            fullText.contains("subscribe")

    /**
     * Recognises the YouTube home/browse feed from its text: a dense grid of
     * recommendation tiles (multiple titles each carrying "views" + "ago") or a
     * set of bottom-navigation headers ("Home", "Subscriptions", "Library", ...).
     */
    private fun isBrowseOrHomeText(fullText: String): Boolean {
        val agoCount = countOccurrences(fullText, "ago")
        val viewsCount = countOccurrences(fullText, "views")
        val denseRecommendations = agoCount >= 2 && viewsCount >= 2

        val navHeaders = listOf("home", "subscriptions", "library", "create")
        val navHeaderCount = navHeaders.count { header ->
            Regex("""\b${Regex.escape(header)}\b""").containsMatchIn(fullText)
        }
        return denseRecommendations || navHeaderCount >= 2
    }

    private fun countOccurrences(text: String, word: String): Int =
        Regex("""\b${Regex.escape(word)}\b""").findAll(text).count()

    /**
     * Strong, unambiguous evidence of a FOCUSED video player interface:
     * a full-screen player container, an active Shorts container, player-control
     * keywords, or watch-page text markers ("likes"/"comments"/"subscribe").
     * Background chrome (mini-player bars, progress bars, shelves) does NOT
     * qualify on its own, so a lingering background mini-player on the home feed
     * can neither defeat the home-feed protection nor satisfy the Shorts gate.
     */
    private fun hasFocusedPlayerInterface(
        signal: ScreenSignal,
        fullText: String
    ): Boolean {
        if (hasFocusedPlayerContainer(signal)) return true
        if (hasPlayerControls(fullText)) return true
        if (hasWatchPageText(fullText)) return true
        return false
    }

    private fun hasFocusedPlayerContainer(signal: ScreenSignal): Boolean {
        // A player container with absolute full-screen coordinates is a valid
        // watch marker — it lets even a background-ish player node resolve state
        // instead of permanently locking the pipeline into playerFullScreen=false.
        if (signal.playerFullScreen && hasAnyPlayerChrome(signal)) return true
        if (focusedPlayerContainerIds.any { it in signal.viewIds }) return true
        if (isShortsContainer(signal)) return true
        return false
    }

    private fun hasPlayerControls(fullText: String): Boolean {
        if (hasActiveTimecode(fullText)) return true
        if (playerKeywords.any { fullText.contains(it) }) return true
        if ((fullText.contains("play") || fullText.contains("pause")) &&
            (fullText.contains("mute") || fullText.contains("seek") ||
                fullText.contains("fullscreen") || fullText.contains("00:"))
        ) return true
        return false
    }

    /** Active video duration timestamps, e.g. `0:00 / 10:00` or `12:34 / 25:00`. */
    private fun hasActiveTimecode(fullText: String): Boolean =
        timecodePattern.containsMatchIn(fullText)

    /**
     * Shorts detection without relying on specific resource ids. Any node text,
     * contentDescription or active Shorts-container view id containing "shorts",
     * "reel" or "short" counts. A bare "shorts"/"short" text hit only counts
     * while playback is present, so the home screen's "Shorts" tab never blocks
     * on open.
     */
    private fun isShortsActive(signal: ScreenSignal, fullText: String): Boolean {
        if (isShortsContainer(signal)) return true
        if (signal.texts.any {
            val normalized = it.lowercase()
            normalized.startsWith("selected:") && normalized.contains("short")
        }) return true
        if (shortFormIndicators.any { fullText.contains(it) }) return true
        // Require an actual Shorts WORD ("shorts", "reels", "reel"), never a bare
        // "short" substring — a comment or description saying "this video is short"
        // must not flip a normal long-form watch page into a Shorts block.
        if (Regex("""\b(reel|reels|shorts)\b""").containsMatchIn(fullText)) {
            // A Shorts word (nav tab, shelf header, suggestion) only counts as an
            // actual Shorts session when a focused Shorts player interface is present.
            return hasFocusedPlayerInterface(signal, fullText)
        }
        return false
    }

    /** True when a rendered Shorts player/feed container is present in the tree. */
    private fun isShortsContainer(signal: ScreenSignal): Boolean =
        signal.viewIds.any { id ->
            !isBackgroundChrome(id) && shortsContainerTokens.any { id.contains(it) }
        }

    private fun isBackgroundChrome(id: String): Boolean =
        backgroundChromeTokens.any { it in id }

    private fun hasBrowseChrome(signal: ScreenSignal): Boolean =
        signal.viewIds.any { id -> browseViewTokens.any { it in id } }

    /**
     * True when the current screen shows a focused player session. Requires
     * DEDICATED foreground watch evidence: a player container with absolute
     * full-screen coordinates, a full-screen player resource id, or an active
     * Shorts container. OEM/version variant ids mentioning player-ish tokens
     * only count when the screen is not browse/home. A background mini-player or
     * a reels bar (`slim_status_bar_player_container`, `reel_time_bar`) NEVER
     * counts on its own — it can only resolve state when paired with genuine
     * full-screen geometry.
     */
    private fun hasActivePlayerContainer(signal: ScreenSignal): Boolean {
        if (signal.playerFullScreen && hasAnyPlayerChrome(signal)) return true
        if (focusedPlayerContainerIds.any { it in signal.viewIds }) return true
        if (isShortsContainer(signal)) return true
        val playerChrome = signal.viewIds.any { id ->
            !isBackgroundChrome(id) &&
                (id.contains("player") || id.contains("overlay") || id.contains("controls"))
        }
        if (playerChrome && !hasBrowseChrome(signal)) return true
        return false
    }

    private fun hasAnyPlayerChrome(signal: ScreenSignal): Boolean =
        signal.viewIds.any { id ->
            id in focusedPlayerContainerIds ||
                id.contains("player") || id.contains("overlay") || id.contains("controls") ||
                id.contains("reel") || id.contains("shorts")
        }

    /**
     * Active-playback evidence: a rendered player container or player-control
     * keywords in text/content descriptions ("Pause", "Replay", "Mute",
     * "Fullscreen", "Seek bar", "00:" timecodes, ...). "Play"/"Pause" alone is
     * only trusted when paired with another control signal, so home-feed cards
     * don't false-positive.
     */
    private fun hasActivePlayer(signal: ScreenSignal, fullText: String): Boolean {
        if (hasActivePlayerContainer(signal)) return true
        if (hasPlayerControls(fullText)) return true
        if (hasWatchPageText(fullText)) return true
        return false
    }

    /**
     * Browser handling: reads the URL bar node (`url_bar`, `search_box_text`) for
     * domain-level blocking, plus NSFW keywords and short-form indicators.
     */
    private fun decideBrowser(signal: ScreenSignal, policy: BlockingPolicy): Decision {
        val fullText = signal.texts.joinToString(" ").lowercase()
        val domain = extractDomain(fullText)
        val urlBarPresent = signal.viewIds.any { it in browserUrlViewIds }

        if (domain != null) {
            if (policy.nsfwProtectionEnabled &&
                nsfwDomains.any { domainMatches(domain, it) }
            ) {
                return Decision(
                    Classification.SLOP,
                    "This website is blocked by your NSFW protection policy."
                )
            }
            if (policy.blockedDomains.any { domainMatches(domain, it) }) {
                return Decision(
                    Classification.SLOP,
                    "This website is blocked by your FocusGuard policy."
                )
            }
        }

        if (policy.nsfwProtectionEnabled && nsfwKeywords.any { fullText.contains(it) }) {
            return Decision(
                Classification.SLOP,
                "NSFW or adult content is blocked by your FocusGuard policy."
            )
        }

        if (policy.shortFormBlockingEnabled &&
            (fullText.contains("tiktok.com") || fullText.contains("youtube.com/shorts") ||
                containsShortForm(signal.texts, fullText))
        ) {
            return Decision(
                Classification.SLOP,
                "Short-form content is blocked by your FocusGuard policy."
            )
        }

        val usefulScore = usefulKeywords.count(fullText::contains)
        val distractingScore = distractingKeywords.count(fullText::contains)
        return when {
            distractingScore >= 2 ->
                Decision(Classification.SLOP, "Distracting content signals detected.")
            usefulScore > distractingScore && usefulScore > 0 ->
                Decision(Classification.PRODUCTIVE, "Useful content signals detected.")
            else ->
                Decision(Classification.ALLOWED, "No blocked content detected.")
        }
    }

    private fun containsNsfw(signal: ScreenSignal): Boolean {
        val fullText = signal.texts.joinToString(" ").lowercase()
        if (nsfwKeywords.any { fullText.contains(it) }) return true
        val domain = extractDomain(fullText)
        return domain != null && nsfwDomains.any { domainMatches(domain, it) }
    }

    private fun containsShortForm(
        textNodes: List<String>,
        fullText: String
    ): Boolean {
        val selectedShorts = textNodes.any {
            val normalized = it.lowercase()
            normalized.startsWith("selected:") && normalized.contains("short")
        }
        return selectedShorts || shortFormIndicators.any(fullText::contains)
    }

    private fun extractDomain(text: String): String? {
        val match = Regex("""(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)""")
            .find(text)
            ?: return null
        return match.groupValues[1].lowercase()
    }

    private fun domainMatches(host: String, blocked: String): Boolean {
        val normalizedHost = host.lowercase()
        val normalizedBlocked = blocked.lowercase()
        return normalizedHost == normalizedBlocked ||
            normalizedHost.endsWith(".$normalizedBlocked")
    }
}
