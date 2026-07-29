package vn.phusa.crawl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every fixture here is a real robots.txt from one of this project's own sources,
 * fetched 2026-07-28. They are short public policy files, quoted because the exact
 * bytes are the thing under test.
 */
class RobotsTxtParserTest {

    private fun parse(body: String) = RobotsTxtParser.parse(body, "PhuSaBot")

    /**
     * lobste.rs — THE case that motivated doing this properly. Seven consecutive
     * `User-agent:` lines share ONE group of rules; then a separate `*` group disallows
     * everything. Reading each agent line as its own group, or taking the first match,
     * both produce "allowed" — the opposite of what the file says.
     */
    @Test
    fun `lobsters disallows everyone outside its allowlist`() {
        val rules = parse(
            """
            User-agent: Applebot
            User-agent: BingBot
            User-agent: GoogleBot
            User-agent: Slurp
            Allow: /
            Disallow: /search
            Disallow: /page/

            User-agent: *
            Crawl-delay: 1
            Disallow: /
            """.trimIndent(),
        )
        assertEquals("*", rules.matchedAgent)
        assertFalse(rules.isAllowed("/rss"), "we are in the catch-all group, which is Disallow: /")
        assertFalse(rules.isAllowed("/"))
        assertEquals(1.0, rules.crawlDelaySec)
    }

    /** A bot in the allowlist gets the other group — proves grouping, not just our case. */
    @Test
    fun `an allowlisted agent gets the permissive group`() {
        val body = """
            User-agent: Applebot
            User-agent: GoogleBot
            Allow: /
            Disallow: /search

            User-agent: *
            Disallow: /
        """.trimIndent()
        val google = RobotsTxtParser.parse(body, "GoogleBot")
        assertEquals("googlebot", google.matchedAgent)
        assertTrue(google.isAllowed("/rss"))
        assertFalse(google.isAllowed("/search"))
    }

    /** hnrss.org — an EMPTY Disallow means allow everything, not block everything. */
    @Test
    fun `empty disallow means allow all`() {
        val rules = parse("User-agent: *\nDisallow:")
        assertTrue(rules.isAllowed("/frontpage"))
        assertTrue(rules.isAllowed("/"))
        assertTrue(rules.disallows.isEmpty())
    }

    /** news.ycombinator.com asks for 30 seconds — ten times our default. */
    @Test
    fun `parses crawl delay`() {
        val rules = parse("User-agent: *\nCrawl-delay: 30\nDisallow: /login")
        assertEquals(30.0, rules.crawlDelaySec)
        assertTrue(rules.isAllowed("/item?id=1"))
        assertFalse(rules.isAllowed("/login"))
    }

    @Test
    fun `absent crawl delay is null, not zero`() {
        assertNull(parse("User-agent: *\nAllow: /").crawlDelaySec)
    }

    /** stackoverflow.blog blocks only GPTBot; we fall through to no rules at all. */
    @Test
    fun `a file naming only other agents leaves us unrestricted`() {
        val rules = parse("User-agent: GPTBot\nDisallow: /")
        assertTrue(rules.isAllowed("/2026/07/24/no-dumb-questions-ai-bottleneck"))
    }

    // ---- wildcards, used by dev.to (16 lines), viblo.asia, znews.vn ----------------

    @Test
    fun `supports star wildcards`() {
        val rules = parse("User-agent: *\nDisallow: /*.json\nDisallow: /*.xml")
        assertFalse(rules.isAllowed("/api/data.json"))
        assertFalse(rules.isAllowed("/feed.xml"))
        assertTrue(rules.isAllowed("/rss"), "viblo's real feed path must survive")
        assertTrue(rules.isAllowed("/p/bai-viet"))
    }

    @Test
    fun `supports dollar anchoring`() {
        val rules = parse("User-agent: *\nDisallow: /*.php$")
        assertFalse(rules.isAllowed("/index.php"))
        assertTrue(rules.isAllowed("/index.php/not-the-end"))
    }

    /**
     * RFC 9309 2.2.2: longest matching pattern wins, Allow wins ties. Sites really do
     * write `Disallow: /` followed by `Allow: /blog/`, and file-order evaluation would
     * block the whole site.
     */
    @Test
    fun `longest match wins and allow beats an equal-length disallow`() {
        val rules = parse("User-agent: *\nDisallow: /\nAllow: /blog/")
        assertTrue(rules.isAllowed("/blog/post-1"), "specific Allow must beat the blanket Disallow")
        assertFalse(rules.isAllowed("/admin"))

        val tie = parse("User-agent: *\nDisallow: /x\nAllow: /x")
        assertTrue(tie.isAllowed("/x"), "equal length -> allow wins")
    }

    // ---- robustness --------------------------------------------------------------

    @Test
    fun `ignores comments blank lines and unknown fields`() {
        val rules = parse(
            """
            # a comment
            Sitemap: https://example.com/sitemap.xml
            Content-Signal: ai-train=no

            User-agent: *   # trailing comment
            Disallow: /private
            """.trimIndent(),
        )
        assertFalse(rules.isAllowed("/private"))
        assertTrue(rules.isAllowed("/public"))
    }

    @Test
    fun `field names are case insensitive`() {
        val rules = parse("USER-AGENT: *\nDISALLOW: /x")
        assertFalse(rules.isAllowed("/x"))
    }

    /** `User-agent: bot` must not capture PhuSaBot — matching is on the whole token. */
    @Test
    fun `agent matching is exact, not substring`() {
        val rules = parse("User-agent: bot\nDisallow: /\n\nUser-agent: *\nAllow: /")
        assertEquals("*", rules.matchedAgent)
        assertTrue(rules.isAllowed("/anything"))
    }

    @Test
    fun `an empty or garbage file allows everything`() {
        assertTrue(parse("").isAllowed("/x"))
        assertTrue(parse("<!DOCTYPE html><html>404</html>").isAllowed("/x"))
    }

    @Test
    fun `the failure sentinel blocks everything`() {
        assertFalse(RobotsRules.DISALLOW_ALL.isAllowed("/"))
        assertFalse(RobotsRules.DISALLOW_ALL.isAllowed("/anything"))
        assertTrue(RobotsRules.ALLOW_ALL.isAllowed("/anything"))
    }
}
