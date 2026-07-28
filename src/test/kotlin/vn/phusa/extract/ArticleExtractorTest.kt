package vn.phusa.extract

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are SYNTHETIC but the structures are not invented: each one reproduces a
 * layout found in the 16 real pages this extractor was tuned against. Real page dumps
 * are deliberately not committed — they are third-party article text, they are hundreds
 * of KB each, and they rot. A fixture that encodes the specific trap is a better
 * regression test than a copy of the page that happened to expose it.
 */
class ArticleExtractorTest {

    private val prose = (1..8).joinToString("") {
        "<p>Đây là một đoạn văn thật, đủ dài để tính điểm, với dấu phẩy và nhiều chữ, " +
            "bởi vì thuật toán chấm điểm dựa trên độ dài và số dấu phẩy trong đoạn.</p>"
    }

    private fun page(body: String) = "<html><head><title>T</title></head><body>$body</body></html>"

    @Test
    fun `pulls the article out of a page that is mostly chrome`() {
        val html = page(
            """
            <nav><a href="/1">Home</a><a href="/2">Tech</a><a href="/3">More</a></nav>
            <div class="sidebar"><a href="/x">Related thing</a><a href="/y">Another</a></div>
            <div class="post-body">$prose</div>
            <div class="comments"><p>Some commenter said something reasonably long here.</p></div>
            <footer><a href="/about">About</a></footer>
            """,
        )
        val ex = assertNotNull(ArticleExtractor.extract(html))
        assertContains(ex.text, "Đây là một đoạn văn thật")
        assertFalse(ex.text.contains("Related thing"), "sidebar leaked into the article")
        assertFalse(ex.text.contains("Some commenter"), "comments leaked into the article")
    }

    // ---- the two failures found against real pages ------------------------------

    /**
     * github.blog renders through WordPress, whose `<body>` class list describes the page
     * LAYOUT and contains `no-sidebar`. The first version of [ArticleExtractor.strip]
     * matched `sidebar` in that string and deleted the whole document — a class
     * announcing the ABSENCE of junk removed the article. Cost: 23,528 chars -> null.
     */
    @Test
    fun `a body class saying no-sidebar does not delete the document`() {
        val html = """
            <html><body class="wp-singular post-template-default single no-sidebar">
            <section class="post__content">$prose</section>
            </body></html>
        """.trimIndent()
        val ex = assertNotNull(ArticleExtractor.extract(html), "body class deleted the article")
        assertContains(ex.text, "Đây là một đoạn văn thật")
    }

    /**
     * vnexpress.net nests `<article class="fck_detail">` inside `<div class="sidebar-1">`,
     * which holds 92% of the page text. The class name is simply wrong about its
     * contents, and removing the wrapper removed the article with it.
     */
    @Test
    fun `an article nested inside a div named sidebar survives`() {
        val html = page("""<div class="sidebar-1"><article class="fck_detail">$prose</article></div>""")
        val ex = assertNotNull(ArticleExtractor.extract(html), "misnamed wrapper deleted the article")
        assertContains(ex.text, "Đây là một đoạn văn thật")
    }

    /** The guard must not disable junk removal for genuinely small junk blocks. */
    @Test
    fun `small junk blocks are still removed`() {
        val html = page("""<div class="article-body">$prose</div><div class="newsletter-signup"><p>Subscribe to our newsletter for weekly updates today.</p></div>""")
        val ex = assertNotNull(ArticleExtractor.extract(html))
        assertFalse(ex.text.contains("Subscribe to our newsletter"), "small junk should still go")
    }

    // ---- link density ------------------------------------------------------------

    @Test
    fun `a long link list never wins over shorter prose`() {
        val links = (1..40).joinToString("") { "<p><a href='/a$it'>Some other article headline number $it here</a></p>" }
        val html = page("""<div class="related">$links</div><div class="body">$prose</div>""")
        val ex = assertNotNull(ArticleExtractor.extract(html))
        assertContains(ex.text, "Đây là một đoạn văn thật")
        assertTrue(ex.linkDensity < 0.5, "picked a link list: density=${ex.linkDensity}")
    }

    // ---- refusing to answer --------------------------------------------------------

    /**
     * Ars Technica answers the bot with HTTP 202 and a 157-char challenge page; InfoQ
     * with 405. Both must extract to null rather than to a plausible-looking stub that
     * would then be hashed and stored as the article.
     */
    @Test
    fun `a bot-challenge page yields null, not a stub`() {
        assertNull(ArticleExtractor.extract(page("<div>Please enable JavaScript to continue.</div>")))
        assertNull(ArticleExtractor.extract(page("<p>405 Not Allowed</p>")))
    }

    @Test
    fun `never throws on junk input`() {
        assertNull(ArticleExtractor.extract(""))
        assertNull(ArticleExtractor.extract("not html"))
        assertNull(ArticleExtractor.extract("<html><body></body></html>"))
    }

    @Test
    fun `reports how the content was found`() {
        val ex = assertNotNull(ArticleExtractor.extract(page("""<div class="body">$prose</div>""")))
        assertTrue(ex.method.isNotBlank())
        assertTrue(ex.text.length >= 200)
    }

    /** Vietnamese diacritics must survive intact — they are the point of the project. */
    @Test
    fun `preserves vietnamese diacritics`() {
        val ex = assertNotNull(ArticleExtractor.extract(page("""<div class="body">$prose</div>""")))
        assertContains(ex.text, "một")
        assertContains(ex.text, "bởi vì")
    }
}
