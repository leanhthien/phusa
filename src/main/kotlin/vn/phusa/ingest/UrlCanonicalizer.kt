package vn.phusa.ingest

import java.net.URI

/**
 * Reduces a feed link to the form used as the article's identity.
 *
 * This is dedup layer 1. `article.url_hash` is `GENERATED ALWAYS AS
 * (digest(canonical_url,'sha256')) STORED` and carries the UNIQUE constraint, so
 * whatever this function returns *is* the dedup key. Two links that canonicalize to
 * the same string are the same article; two that don't, aren't. That makes the rules
 * below load-bearing rather than cosmetic.
 *
 * EVERY RULE HERE WAS CHOSEN AGAINST THE REAL TABLE (582 rows, 20 sources), not from a
 * list of "things canonicalizers usually do". The rules that were REJECTED are the more
 * interesting half and are documented at the bottom — a normalization that over-merges
 * destroys two distinct articles and there is no way to tell afterwards.
 *
 * Total by construction: a URL this cannot parse is returned trimmed but otherwise
 * untouched. Ingest must never lose an article because a link was odd.
 */
object UrlCanonicalizer {

    /**
     * Analytics parameters that identify the *referrer*, never the *resource*.
     *
     * Evidence: all 16 rows with a query string were InfoQ, and all 16 carried the same
     * four `utm_*` params. Left alone, every InfoQ article is a fresh row the moment
     * the campaign values change.
     */
    private val TRACKING_PREFIXES = listOf("utm_")

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "yclid", // ad-network click ids
        "mc_cid", "mc_eid",                             // mailchimp
        "igshid",                                       // instagram
        "ref_src", "ref_url",                           // twitter/embed referrer
        "_hsenc", "_hsmi",                              // hubspot
        "spm",                                          // alibaba-family, common on VN sites
    )

    fun canonicalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        // Relative or opaque (mailto:, magnet:) — nothing here applies. Leave it be.
        val scheme = uri.scheme?.lowercase() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed

        val path = uri.rawPath.orEmpty().let { if (it.length > 1) it.trimEnd('/') else it }
        val query = cleanQuery(uri.rawQuery)

        return buildString {
            append(scheme).append("://").append(host)
            // RFC 3986 §6.2.3: an explicit default port is equivalent to no port.
            if (uri.port != -1 && uri.port != defaultPort(scheme)) append(':').append(uri.port)
            append(path)
            query?.let { append('?').append(it) }
        }
    }

    /**
     * Drops tracking params and sorts what survives.
     *
     * Sorting is what makes `?a=1&b=2` and `?b=2&a=1` the same article. [sortedBy] is
     * stable, so repeated keys (`?tag=a&tag=b`) keep their relative order — that one
     * case where order genuinely carries meaning is preserved.
     *
     * Works on the RAW query. Decoding and re-encoding would round-trip through a
     * different byte sequence for the same URL (`%2F` vs `/`, `+` vs `%20`), and since
     * the hash is over the bytes, that is a silent duplicate generator. Vietnamese
     * sources percent-encode diacritics, so this is not hypothetical.
     */
    private fun cleanQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val kept = rawQuery.split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=').lowercase()) }
            .sortedBy { it.substringBefore('=') }
        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun isTracking(key: String): Boolean =
        key in TRACKING_PARAMS || TRACKING_PREFIXES.any { key.startsWith(it) }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}

/*
 * REJECTED RULES — each one is standard advice, and each one would have corrupted this
 * dataset. Kept in the source because "why didn't you normalize X" is the question.
 *
 * 1. LOWERCASE THE PATH. Decisive counter-evidence: 47 rows contain uppercase, and all
 *    of it is in the path — martinfowler.com/bliki/VibeCoding.html,
 *    spring.io/blog/.../podcast-S5E18. Only the scheme and host are case-insensitive
 *    (RFC 3986 §6.2.2.1); paths are case-sensitive. Lowercasing would 404 six real
 *    articles.
 *
 * 2. UPGRADE http:// TO https://. Two rows are plain http (oldvcr.blogspot.com,
 *    yummymelon.com). Rewriting the scheme asserts something about the origin that we
 *    have not verified. If it only serves http, we have stored a link that does not
 *    resolve — and a canonicalizer must never invent reachability.
 *
 * 3. STRIP `www.`. 57 rows have it; zero collide with a bare-host twin. `www.host` and
 *    `host` are different DNS names that may legitimately serve different content.
 *    Merging on a hunch risks collapsing two real articles to buy nothing measurable.
 *
 * The one rule here WITHOUT collision evidence is the trailing slash (158 rows have
 * one, none collide). It is applied anyway, on a narrower argument than the others:
 * unlike www/https it cannot change which server answers or which host is addressed —
 * it stays inside one origin — and `/foo` vs `/foo/` serving different documents is a
 * misconfiguration rather than a design. Cheap insurance against one source emitting
 * both forms. Stated plainly because it is the weakest link in the chain.
 */
