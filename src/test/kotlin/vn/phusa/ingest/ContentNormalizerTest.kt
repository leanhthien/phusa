package vn.phusa.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The cases here come from what the 20 real feeds actually ship, not from imagined HTML.
 * Container-free and network-free.
 */
class ContentNormalizerTest {

    private fun body(n: Int) = "wordy ".repeat(n).trim()

    @Test
    fun `strips tags and unescapes entities`() {
        assertEquals(
            "Xin chào & welcome",
            ContentNormalizer.normalize("<p>Xin ch&#xE0;o <b>&amp;</b> welcome</p>"),
        )
    }

    @Test
    fun `collapses all whitespace to single spaces`() {
        assertEquals("a b c", ContentNormalizer.normalize("  a\n\n\tb   \r\n c  "))
    }

    /**
     * The Stack Overflow blog pads every description with invisible characters —
     * measured at 1160 of them around 190 characters of real text.
     */
    @Test
    fun `strips zero-width padding`() {
        val padded = "real text" + "​‌‍﻿⁠".repeat(200)
        assertEquals("real text", ContentNormalizer.normalize(padded))
    }

    /** Two copies of one article that differ only in invisible padding are one article. */
    @Test
    fun `padding does not change the hash`() {
        val clean = body(200)
        val padded = clean.replace(" ", "​ ")
        assertContentEquals(
            ContentNormalizer.hash(ContentNormalizer.normalize(clean)),
            ContentNormalizer.hash(ContentNormalizer.normalize(padded)),
        )
    }

    @Test
    fun `same body hashes the same, different body does not`() {
        val a = ContentNormalizer.hash(ContentNormalizer.normalize("<p>${body(200)}</p>"))!!
        val b = ContentNormalizer.hash(ContentNormalizer.normalize(body(200)))!!
        val c = ContentNormalizer.hash(ContentNormalizer.normalize(body(200) + " extra"))!!
        assertContentEquals(a, b, "markup must not affect the hash")
        assertNotEquals(a.toList(), c.toList(), "different text must hash differently")
    }

    @Test
    fun `hash is sha-256, so 32 bytes`() {
        assertEquals(32, ContentNormalizer.hash(body(200))!!.size)
    }

    // ---- the floor -------------------------------------------------------------

    /**
     * The case this floor exists for: the Lobsters feed ships the identical body
     * "Comments" for all 25 items. Hashing it would mark 24 real articles as duplicates
     * of the 25th.
     */
    @Test
    fun `refuses to hash the lobsters boilerplate body`() {
        assertNull(ContentNormalizer.hash(ContentNormalizer.normalize("Comments")))
    }

    @Test
    fun `refuses to hash anything below the floor`() {
        val justUnder = "x".repeat(ContentNormalizer.MIN_HASHABLE_CHARS - 1)
        val justOver = "x".repeat(ContentNormalizer.MIN_HASHABLE_CHARS)
        assertNull(ContentNormalizer.hash(justUnder))
        assertEquals(32, ContentNormalizer.hash(justOver)!!.size)
    }

    @Test
    fun `refuses to hash empty or blank`() {
        assertNull(ContentNormalizer.hash(ContentNormalizer.normalize(null)))
        assertNull(ContentNormalizer.hash(ContentNormalizer.normalize("   ")))
        assertNull(ContentNormalizer.hash(ContentNormalizer.normalize("<div>  </div>")))
    }

    /** The floor applies to the NORMALIZED length — padding must not buy its way past. */
    @Test
    fun `padding cannot inflate a teaser past the floor`() {
        val teaser = "short teaser"
        val inflated = teaser + "​".repeat(2000)
        assertNull(ContentNormalizer.hash(ContentNormalizer.normalize(inflated)))
    }

    // ---- word count ------------------------------------------------------------

    @Test
    fun `counts words including vietnamese`() {
        assertEquals(4, ContentNormalizer.wordCount(ContentNormalizer.normalize("<p>mã nguồn mở rộng</p>")))
        assertEquals(0, ContentNormalizer.wordCount(""))
        assertEquals(1, ContentNormalizer.wordCount("one"))
    }

    /**
     * Feeds ship broken markup constantly, so the contract is "never throws" rather
     * than any particular repair. jsoup closes unclosed tags, and keeps `<<<>>>` as
     * literal text because it is not markup — right call, since a body that merely
     * contains angle brackets must not be silently emptied.
     */
    @Test
    fun `never throws on malformed markup`() {
        assertEquals("unclosed", ContentNormalizer.normalize("<p><b>unclosed"))
        assertEquals("<<<>>>", ContentNormalizer.normalize("<<<>>>"))
        assertEquals("a < b and c > d", ContentNormalizer.normalize("a &lt; b and c &gt; d"))
    }
}
