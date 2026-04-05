package com.kb.blocker

import java.util.Locale

/**
 * Video player screen-এ title, description, hashtag, channel name থেকে
 * adult content detect করে।
 *
 * এটা শুধু browser + video app-এ call হবে।
 * Normal text-based detection (AdultContentDetector) থেকে আলাদা —
 * এটা shorter, tag-style text-এর জন্য optimize করা।
 */
object VideoMetaDetector {

    // ── Hard adult — video title/tag-এ এলেই block ─────────────────────────
    private val HARD_META_KEYWORDS = setOf(
        // Direct adult
        "porn", "porno", "xxx", "xnxx", "xvideos", "pornhub",
        "nude", "naked", "nudity", "nsfw",
        "sex video", "sex tape", "adult video",
        "hentai", "rule34", "nhentai",
        "onlyfans", "chaturbate", "fansly",
        "blowjob", "handjob", "cumshot", "creampie",
        "orgasm", "masturbat",
        "gangbang", "threesome", "orgy",
        "bdsm", "bondage", "fetish",
        "stripper", "striptease", "camgirl",

        // বাংলা
        "চুদাচুদি", "নেংটো", "উলঙ্গ", "নোংরা ভিডিও"
    )

    // ── Soft adult — video context-এ suspicious ───────────────────────────
    // (title বা hashtag-এ দেখলে block)
    private val SOFT_META_KEYWORDS = setOf(
        // English soft
        "hot girl", "hot girls", "sexy girl", "sexy dance",
        "bikini dance", "bikini haul", "bikini try on",
        "try on haul", "body reveal", "outfit reveal",
        "item song", "item dance", "bold scene",
        "steamy", "seductive", "intimate scene",
        "navel dance", "navel show", "cleavage",
        "no bra", "braless", "see through",
        "topless", "shirtless girl", "barely dressed",
        "strip dance", "pole dance",
        "bedroom scene", "shower scene", "bath scene",
        "uncensored", "18+", "18 plus", "adults only",
        "not for kids", "mature content",

        // Hashtags — # সহ বা ছাড়া
        "#sexy", "#hot", "#nsfw", "#nude", "#naked",
        "#bikini", "#seductive", "#uncensored",
        "#18plus", "#adultcontent", "#boldscene",
        "sexy", "sexi",  // tag হিসেবে একা থাকলে

        // Hinglish
        "nangi", "nanga", "desi hot", "desi sexy",
        "bhabhi hot", "bhabhi sexy", "aunty hot",
        "garam video", "bold desi",

        // বাংলা soft
        "হট মেয়ে", "সেক্সি", "গরম ভিডিও",
        "আইটেম গান", "আইটেম ড্যান্স", "রগরগে"
    )

    /**
     * Video metadata (title + description + tags + channel) check করো।
     * Hard অথবা soft match → true
     */
    fun isAdultMeta(metaText: String): Boolean {
        val lower = metaText.lowercase(Locale.getDefault())

        // Hard keywords — word boundary check
        if (HARD_META_KEYWORDS.any { wordMatch(lower, it.lowercase(Locale.getDefault())) }) {
            return true
        }

        // Soft keywords — phrase/tag match
        if (SOFT_META_KEYWORDS.any { kw ->
            wordMatch(lower, kw.lowercase(Locale.getDefault()))
        }) {
            return true
        }

        // Hashtag pattern: #sexy, #nude, #nsfw ইত্যাদি
        if (hasAdultHashtag(lower)) return true

        return false
    }

    /**
     * #tag format check — regex দিয়ে
     */
    private fun hasAdultHashtag(text: String): Boolean {
        val adultTags = setOf(
            "sexy", "hot", "nsfw", "nude", "naked", "porn",
            "xxx", "adult", "bikini", "seductive", "uncensored",
            "18plus", "boldscene", "itembabe", "hotgirl",
            "nangi", "desiho", "desisexy"
        )
        // # দিয়ে শুরু হওয়া word গুলো বের করো
        val hashtagRegex = Regex("#(\\w+)")
        val tags = hashtagRegex.findAll(text).map { it.groupValues[1] }.toList()
        return tags.any { tag -> adultTags.any { tag.contains(it) } }
    }

    /**
     * Word boundary match:
     * - Multi-word → contains
     * - Single word → boundary check ("sexy" ≠ "sexyland")
     */
    fun wordMatch(text: String, keyword: String): Boolean {
        if (keyword.startsWith("#")) return text.contains(keyword)
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
