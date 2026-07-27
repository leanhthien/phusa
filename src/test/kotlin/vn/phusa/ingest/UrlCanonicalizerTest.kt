package vn.phusa.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every case here is either a real URL from the live table or the exact shape one of the
 * 20 sources produces. Container-free and network-free.
 *
 * The second half — "rules deliberately NOT applied" — matters more than the first. An
 * over-eager canonicalizer merges two distinct articles into one and the loser is
 * unrecoverable, so those tests exist to make a future "helpful" normalization fail
 * loudly instead of quietly eating content.
 */
class UrlCanonicalizerTest {

    private fun canon(u: String) = UrlCanonicalizer.canonicalize(u)

    // ---- rules that ARE applied -------------------------------------------------

    @Test
    fun `strips utm params`() {
        assertEquals(
            "https://www.infoq.com/news/2026/07/rspack-2-release",
            canon("https://www.infoq.com/news/2026/07/rspack-2-release/?utm_campaign=infoq_content&utm_source=infoq&utm_medium=feed&utm_term=global"),
        )
    }

    @Test
    fun `strips other click-tracking params`() {
        assertEquals(
            "https://example.com/a",
            canon("https://example.com/a?fbclid=x&gclid=y&mc_cid=z&igshid=w&spm=v"),
        )
    }

    /** Tracking params go; real ones stay. Dropping `?p=123` would break WordPress sites. */
    @Test
    fun `keeps meaningful params while dropping tracking ones`() {
        assertEquals("https://example.com/x?id=42", canon("https://example.com/x?utm_source=rss&id=42"))
    }

    /**
     * The live collision: martinfowler.com publishes an article incrementally and the
     * feed points at a new section each time. Three rows, one document.
     */
    @Test
    fun `strips fragments so section links collapse onto one document`() {
        val page = "https://martinfowler.com/articles/sensors-for-coding-agents.html"
        assertEquals(page, canon("$page#TheTestSuiteAsARegressionSensor"))
        assertEquals(page, canon("$page#StaticCodeAnalysisDependencyRules"))
        assertEquals(page, canon(page))
    }

    @Test
    fun `sorts query params so order does not create a second identity`() {
        assertEquals(canon("https://example.com/x?b=2&a=1"), canon("https://example.com/x?a=1&b=2"))
        assertEquals("https://example.com/x?a=1&b=2", canon("https://example.com/x?b=2&a=1"))
    }

    /** Repeated keys keep their original relative order — there, order is meaning. */
    @Test
    fun `repeated keys keep their relative order`() {
        assertEquals("https://example.com/x?tag=a&tag=b", canon("https://example.com/x?tag=a&tag=b"))
        assertEquals("https://example.com/x?tag=b&tag=a", canon("https://example.com/x?tag=b&tag=a"))
    }

    @Test
    fun `lowercases scheme and host`() {
        assertEquals("https://example.com/Path", canon("HTTPS://Example.COM/Path"))
    }

    @Test
    fun `drops an explicitly written default port`() {
        assertEquals("https://example.com/a", canon("https://example.com:443/a"))
        assertEquals("http://example.com/a", canon("http://example.com:80/a"))
        assertEquals("https://example.com:8443/a", canon("https://example.com:8443/a"))
    }

    @Test
    fun `strips a trailing slash but keeps a bare root`() {
        assertEquals("https://example.com/a/b", canon("https://example.com/a/b/"))
        // freeink.org, slashpages.net and petals.dev are all stored in this form.
        assertEquals("https://freeink.org/", canon("https://freeink.org/"))
    }

    // ---- rules deliberately NOT applied ------------------------------------------

    /**
     * 47 live rows have uppercase and every one of them is in the PATH. Paths are
     * case-sensitive; lowercasing this 404s the article.
     */
    @Test
    fun `does not lowercase the path`() {
        assertEquals(
            "https://martinfowler.com/bliki/VibeCoding.html",
            canon("https://martinfowler.com/bliki/VibeCoding.html"),
        )
        assertEquals(
            "https://spring.io/blog/2026/07/13/spring-office-hours-podcast-S5E18",
            canon("https://spring.io/blog/2026/07/13/spring-office-hours-podcast-S5E18"),
        )
    }

    /** Rewriting the scheme asserts reachability we never verified. */
    @Test
    fun `does not upgrade http to https`() {
        assertEquals(
            "http://oldvcr.blogspot.com/2026/07/john-c-dvorak-has-died.html",
            canon("http://oldvcr.blogspot.com/2026/07/john-c-dvorak-has-died.html"),
        )
    }

    /** Different DNS name, possibly different content. Not ours to merge. */
    @Test
    fun `does not strip www`() {
        assertEquals("https://www.theverge.com/a", canon("https://www.theverge.com/a"))
    }

    /**
     * Percent-encoding is preserved byte-for-byte. Decode-then-reencode round trips
     * through a different string for the same URL, and the hash is over the bytes —
     * a silent duplicate generator. Vietnamese sources encode diacritics.
     */
    @Test
    fun `preserves percent encoding exactly`() {
        val encoded = "https://viblo.asia/p/l%E1%BA%ADp-tr%C3%ACnh-kotlin"
        assertEquals(encoded, canon(encoded))
    }

    // ---- must never lose an article ---------------------------------------------

    @Test
    fun `returns odd input untouched rather than throwing`() {
        assertEquals("not a url at all", canon("  not a url at all  "))
        assertEquals("mailto:a@b.com", canon("mailto:a@b.com"))
        assertEquals("/relative/path", canon("/relative/path"))
        assertEquals("", canon("   "))
    }

    @Test
    fun `is idempotent`() {
        val messy = "HTTPS://Example.COM:443/a/b/?utm_source=x&b=2&a=1#frag"
        val once = canon(messy)
        assertEquals(once, canon(once))
        assertEquals("https://example.com/a/b?a=1&b=2", once)
    }
}
