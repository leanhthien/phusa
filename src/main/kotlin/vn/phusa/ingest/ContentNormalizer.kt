package vn.phusa.ingest

import org.jsoup.Jsoup
import java.security.MessageDigest

/**
 * Turns article body markup into the plain text that gets stored and hashed.
 *
 * This is dedup layer 2's foundation. `article.content_hash` is SHA-256 over the output
 * of [normalize], so normalization decides what "the same body" means: anything this
 * function erases is a difference two copies are allowed to have, and anything it keeps
 * is a difference that makes them distinct articles. Getting it wrong is quiet — an
 * under-normalized hash simply never matches and the layer looks like it's working.
 *
 * WHY ZERO-WIDTH CHARACTERS ARE STRIPPED, which is not a rule you would guess:
 * the Stack Overflow blog feed pads every `<description>` with invisible formatting
 * characters — measured at **1160 zero-width characters around 190 characters of actual
 * text**, 86% of the payload. They are stable across fetches (checked twice, identical
 * SHA-256), so they don't destabilise the hash, but they wreck every length-based
 * judgement made about a body and they would make two otherwise-identical texts hash
 * differently if one copy passed through a system that stripped them. They carry no
 * meaning, so they go.
 */
object ContentNormalizer {

    /**
     * ZWSP, ZWNJ, ZWJ, word-joiner and BOM. Deliberately NOT including soft hyphen or
     * non-breaking space: those affect rendering of real text, and NBSP in particular is
     * collapsed as whitespace below rather than deleted, so words stay separated.
     */
    private val ZERO_WIDTH = Regex("[​‌‍⁠﻿]")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Below this many characters a body is not hashed at all — [hash] returns null.
     *
     * A SAFETY NET, NOT THE CLASSIFIER. Whether a feed carries the real article is
     * declared per source (`SourceConfig.feedHasFullContent`); this floor only limits
     * the damage when that declaration is wrong. The damage is not hypothetical: the
     * Lobsters feed ships the byte-identical body "Comments" for all 25 items, so a
     * naive body hash would mark 24 real articles as duplicates of the 25th and there
     * is no way to notice from the outside.
     *
     * 500 sits in a wide empty band in the measured data: the longest pathological body
     * is 378 chars (InfoQ) and the shortest genuine full-text source is ~5000
     * (kotlin-blog). Nothing real is anywhere near this line.
     */
    const val MIN_HASHABLE_CHARS = 500

    /** HTML (or plain text) in, canonical plain text out. Never throws. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // jsoup, not a regex: it unescapes entities and drops tags correctly, and a
        // regex mangles both. Already a dependency for summary cleaning.
        val text = runCatching { Jsoup.parse(raw).text() }.getOrElse { raw }
        return WHITESPACE.replace(ZERO_WIDTH.replace(text, ""), " ").trim()
    }

    /**
     * SHA-256 of the normalized body, or null when there isn't enough body to be
     * evidence of anything. Null is the honest answer — `article_content_hash_idx` is
     * partial (`WHERE content_hash IS NOT NULL`), so unhashed rows cost nothing and are
     * simply not candidates for exact-duplicate matching.
     */
    fun hash(normalized: String): ByteArray? {
        if (normalized.length < MIN_HASHABLE_CHARS) return null
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    }

    /** Whitespace-delimited word count. Vietnamese is space-delimited, so this holds. */
    fun wordCount(normalized: String): Int =
        if (normalized.isEmpty()) 0 else normalized.count { it == ' ' } + 1
}
