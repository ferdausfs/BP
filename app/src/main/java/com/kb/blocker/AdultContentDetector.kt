package com.kb.blocker

object AdultContentDetector {

    private val ADULT_KEYWORDS = setOf(
        "porn", "porno", "pornography", "xxx", "xnxx", "xvideos", "xhamster",
        "pornhub", "redtube", "youporn", "tube8", "spankbang", "eporner",
        "hentai", "nhentai", "rule34",
        "nude", "nudity", "naked", "nudes",
        "nsfw", "sex video", "sex tape", "free porn", "watch porn",
        "adult video", "adult content",
        "boobs", "tits", "titty", "titties",
        "penis", "vagina", "cock", "dick", "pussy",
        "orgasm", "cumshot", "creampie",
        "blowjob", "handjob",
        "masturbate", "masturbation",
        "gangbang", "orgy", "threesome",
        "milf", "bdsm", "bondage", "fetish",
        "stripper", "striptease",
        "prostitute", "prostitution",
        "onlyfans", "fansly", "chaturbate",
        "camgirl", "camboy", "live sex",
        "erotic", "erotica",
        "nude photo", "naked photo", "hardcore sex"
    )

    private val ADULT_DOMAINS = setOf(
        "pornhub", "xvideos", "xhamster", "xnxx", "redtube",
        "youporn", "tube8", "spankbang", "eporner",
        "beeg", "faphouse", "porntrex", "porn.com",
        "nhentai", "hentaihaven", "rule34",
        "onlyfans", "fansly", "manyvids", "chaturbate",
        "livejasmin", "bongacams", "cam4", "myfreecams",
        "stripchat", "brazzers", "bangbros", "mofos"
    )

    /** Screen text বা URL-এ adult content আছে কিনা */
    fun isAdultContent(screenText: String): Boolean {
        val lower = screenText.lowercase()
        // Domain check — contains যথেষ্ট
        if (ADULT_DOMAINS.any { lower.contains(it) }) return true
        // Keyword check — word boundary
        return ADULT_KEYWORDS.any { keyword -> wordMatch(lower, keyword) }
    }

    /** "ass" যেন "class", "message", "WhatsApp" match না করে */
    private fun wordMatch(text: String, keyword: String): Boolean {
        var startIndex = 0
        while (true) {
            val idx = text.indexOf(keyword, startIndex)
            if (idx == -1) return false
            val before = if (idx > 0) text[idx - 1] else ' '
            val after = if (idx + keyword.length < text.length) text[idx + keyword.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            startIndex = idx + 1
        }
    }
}
