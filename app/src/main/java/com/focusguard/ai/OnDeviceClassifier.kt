package com.focusguard.ai

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

    fun isAlwaysBlockedPackage(packageName: String): Boolean =
        packageName in socialMediaPackages

    fun isYouTubePackage(packageName: String): Boolean =
        packageName in youtubePackages

    fun classify(packageName: String, textNodes: List<String>): Classification {
        return decide(packageName, textNodes).classification
    }

    fun decide(packageName: String, textNodes: List<String>): Decision {
        if (isAlwaysBlockedPackage(packageName)) {
            return Decision(
                Classification.SLOP,
                "This social-media app is blocked by your FocusGuard policy."
            )
        }
        if (packageName in productivePackages) {
            return Decision(Classification.PRODUCTIVE, "This is a productive app.")
        }
        if (packageName in safePackages) {
            return Decision(Classification.ALLOWED, "This app is allowed.")
        }

        if (isYouTubePackage(packageName)) {
            return decideYouTube(textNodes)
        }

        if (packageName in mixedBrowserPackages) {
            val fullText = textNodes.joinToString(" ").lowercase()
            if (containsShortForm(textNodes, fullText)) {
                return Decision(
                    Classification.SLOP,
                    "Short-form content is blocked by your FocusGuard policy."
                )
            }
            val usefulScore = usefulKeywords.count(fullText::contains)
            val distractingScore = distractingKeywords.count(fullText::contains)
            return when {
                usefulScore > distractingScore && usefulScore > 0 ->
                    Decision(Classification.PRODUCTIVE, "Useful content signals detected.")
                distractingScore >= 2 ->
                    Decision(Classification.SLOP, "Distracting content signals detected.")
                else -> Decision(Classification.ALLOWED, "No blocked content detected.")
            }
        }

        return Decision(Classification.ALLOWED, "This app is allowed.")
    }

    private fun decideYouTube(textNodes: List<String>): Decision {
        val fullText = textNodes.joinToString(" ").lowercase()
        if (containsShortForm(textNodes, fullText)) {
            return Decision(
                Classification.SLOP,
                "YouTube Shorts and other short-form videos are always blocked."
            )
        }

        val usefulScore = usefulKeywords.count(fullText::contains)
        val distractingScore = distractingKeywords.count(fullText::contains)

        return when {
            usefulScore >= 2 && usefulScore > distractingScore ->
                Decision(
                    Classification.PRODUCTIVE,
                    "The YouTube title or description contains useful content signals."
                )
            distractingScore > 0 ->
                Decision(
                    Classification.SLOP,
                    "The YouTube title or description contains entertainment signals."
                )
            else ->
                Decision(
                    Classification.SLOP,
                    "FocusGuard could not verify a useful video title from the text " +
                        "YouTube exposed, so strict mode blocked it.",
                    needsMoreText = true
                )
        }
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
}
