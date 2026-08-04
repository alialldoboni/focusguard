package com.focusguard.ai

/**
 * What the accessibility service could observe on screen: visible text and the
 * resource ids (normalized, e.g. `watch_player_overlay`) of the nodes in the
 * active window. The classifier uses both so it can tell "the Shorts feed",
 * "an active long-form player" and "YouTube's home screen" apart instead of
 * blocking just because YouTube is in the foreground.
 */
data class ScreenSignal(
    val packageName: String,
    val texts: List<String> = emptyList(),
    val viewIds: Set<String> = emptySet()
) {
    val signature: String
        get() = texts.joinToString("|") + "::" + viewIds.sorted().joinToString(",")

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

class OnDeviceClassifier {

    enum class Classification { PRODUCTIVE, SLOP, ALLOWED }

    data class Decision(
        val classification: Classification,
        val reason: String,
        val needsMoreText: Boolean = false
    )

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

    private val shortsViewIds = setOf(
        "reel_recycler",
        "reel_player_page_container",
        "shorts_player_page_container"
    )

    private val longFormPlayerViewIds = setOf(
        "watch_player_overlay",
        "player_view",
        "player_controls_view"
    )

    private val browserUrlViewIds = setOf(
        "url_bar",
        "search_box_text",
        "location_bar",
        "omnibox"
    )

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
     * Evidence-based YouTube handling. FocusGuard never blocks YouTube just for
     * being open:
     *  - Shorts: blocked only when Shorts UI is actually detected.
     *  - Long-form: blocked only while the player overlay is active, unless the
     *    visible title/channel matches useful-content keywords.
     *  - Home / browse (no player): always allowed.
     */
    private fun decideYouTube(signal: ScreenSignal, policy: BlockingPolicy): Decision {
        val fullText = signal.texts.joinToString(" ").lowercase()
        val isShorts = signal.viewIds.any { it in shortsViewIds } ||
            containsShortForm(signal.texts, fullText)
        if (isShorts) {
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

        val overlayActive = signal.viewIds.any { it in longFormPlayerViewIds }
        if (!overlayActive) {
            return Decision(
                Classification.ALLOWED,
                "No active YouTube video player detected."
            )
        }

        if (!policy.longFormBlockingEnabled) {
            return Decision(Classification.ALLOWED, "Long-form blocking is disabled.")
        }

        if (fullText.isBlank()) {
            return Decision(
                Classification.SLOP,
                "A YouTube video is playing but its title could not be read.",
                needsMoreText = true
            )
        }

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
