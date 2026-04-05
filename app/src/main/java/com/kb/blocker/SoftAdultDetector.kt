package com.kb.blocker

import java.util.Locale

/**
 * Browser ও video/streaming app-এ যে ধরনের "soft adult" বা "suggestive" keyword
 * দিয়ে adult-type image/video এসে যায় — সেগুলো এখানে।
 *
 * এগুলো শুধু BROWSER_PACKAGES বা VIDEO_PACKAGES-এ detect করলে block হবে।
 * WhatsApp, SMS, অন্য app-এ এগুলো দিয়ে block হবে না।
 */
object SoftAdultDetector {

    private val SOFT_KEYWORDS = setOf(
        // বাংলা soft-adult
        "চুদা", "চুদি", "চুদাচুদি", "খানকি", "মাগি",
        "গরম মেয়ে", "নেংটা", "নেংটো", "উলঙ্গ",
        "ছোট কাপড়", "ছোট জামা", "বুক দেখানো",
        "রগরগে", "গরম ভিডিও", "হট ভিডিও", "হট মেয়ে",
        "আইটেম মেয়ে", "আইটেম ড্যান্স",
        "সেক্সি মেয়ে", "সেক্সি ভিডিও", "সেক্সি ছবি",
        "নোংরা ভিডিও", "নোংরা ছবি",

        // English — search terms that bring adult content
        "hot girl", "hot girls", "hot woman", "hot women",
        "hot video", "hot videos", "hot dance", "hot scene",
        "item song", "item dance", "item girl",
        "bold scene", "bold video", "bold song",
        "sexy girl", "sexy girls", "sexy woman", "sexy dance",
        "sexy video", "sexy song", "sexy scene",
        "bikini girl", "bikini dance", "bikini video",
        "skimpy", "revealing dress", "revealing outfit",
        "see through", "no bra", "braless",
        "busty", "big boobs", "cleavage show",
        "navel show", "navel song", "navel dance",
        "seductive", "seduction video",
        "wet song", "wet dance", "wet video",
        "steamy", "steamy scene", "steamy video",
        "intimate scene", "romance scene",
        "18+ video", "18+ song", "adults only",
        "uncensored", "uncensored video",
        "strip dance", "pole dance video",
        "bra show", "bra removed",
        "undressed", "undressing",
        "topless", "shirtless girl",
        "short dress", "mini skirt dance",
        "body show", "body reveal",

        // Hinglish / Hindi transliteration
        "garam ladki", "garam video", "garam song",
        "nanga video", "nanga photo", "nangi",
        "nangi ladki", "nangi aurat",
        "desi hot", "desi sexy", "desi maal",
        "bhabhi hot", "bhabhi sexy", "bhabhi video",
        "aunty hot", "aunty sexy", "aunty video",
        "desi bhabhi", "desi aunty",
        "jism song", "jism video",
        "bold desi", "desi bold",

        // Video platform suggestive searches
        "hottest", "sexiest", "most revealing",
        "barely dressed", "almost naked",
        "bedroom scene", "kissing scene hot",
        "shower scene", "bath scene"
    )

    // URL query patterns — browser search বা redirect URL-এ থাকলে block
    private val SOFT_URL_PATTERNS = setOf(
        "hot+girl", "hot+video", "hot+song", "hot+dance",
        "sexy+girl", "sexy+video", "sexy+dance",
        "item+song", "item+dance",
        "bikini+girl", "bikini+dance",
        "bold+scene", "bold+video",
        "nangi", "nangi+ladki", "nanga+video",
        "desi+hot", "desi+sexy",
        "bhabhi+hot", "aunty+hot",
        "18%2b", "uncensored",
        "hot%20girl", "sexy%20girl",
        "nude+dance", "strip+dance"
    )

    /**
     * Browser বা video app-এ screen text-এ soft adult keyword আছে কিনা।
     * শুধু BROWSER বা VIDEO package হলে call করো।
     */
    fun isSoftAdultContent(screenText: String): Boolean {
        val lower = screenText.lowercase(Locale.getDefault())
        return SOFT_KEYWORDS.any { keyword ->
            wordMatch(lower, keyword.lowercase(Locale.getDefault()))
        }
    }

    /**
     * URL-এ soft adult search query আছে কিনা
     */
    fun isSoftAdultUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        return SOFT_URL_PATTERNS.any { lower.contains(it) }
    }

    /**
     * Word boundary match:
     * - Single word: "hot" → "hotel" বা "photo" match করবে না
     * - Multi-word phrase: "hot girl" → contains check (already specific)
     */
    fun wordMatch(text: String, keyword: String): Boolean {
        if (keyword.contains(" ")) return text.contains(keyword)
        var start = 0
        while (true) {
            val idx = text.indexOf(keyword, start)
            if (idx == -1) return false
            val before = if (idx > 0) text[idx - 1] else ' '
            val after  = if (idx + keyword.length < text.length) text[idx + keyword.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            start = idx + 1
        }
    }
}
