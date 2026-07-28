package vn.phusa.extract

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import vn.phusa.ingest.ContentNormalizer

/**
 * What a successful extraction produced. [method] records HOW it was found, so a bad
 * result can be traced to the rule that produced it instead of guessed at.
 */
data class Extraction(
    val text: String,
    val html: String,
    val method: String,
    val linkDensity: Double,
)

/**
 * Readability-style main-content extraction with jsoup.
 *
 * THE PROBLEM: an article page is mostly not the article. Navigation, share buttons,
 * "related stories", newsletter prompts, comments and footers routinely outweigh the
 * body. Taking `body.text()` gives a blob where the article is a minority of the text,
 * which then poisons `content_hash` (two different articles on the same site share most
 * of their chrome, so their hashes are dominated by identical boilerplate) and makes
 * full-text search rank on menu labels.
 *
 * THE APPROACH, which is Arc90's readability heuristic in miniature:
 *  1. delete the elements that are never article text
 *  2. score every paragraph-ish block by how much it reads like prose
 *  3. propagate each block's score to its parent — the container holding the most prose
 *     is the article
 *  4. penalise by LINK DENSITY, which is the single sharpest signal: navigation and
 *     "related" blocks are almost entirely anchor text, real prose is almost none
 *
 * Deliberately NOT a dependency. crawler4j/boilerpipe are unmaintained, and the
 * Readability4J port is a thin wrapper over exactly this. The heuristic is ~100 lines,
 * it is the interesting part of the problem, and owning it means it can be tuned against
 * this project's actual sources — half of which are Vietnamese and none of which are in
 * anyone's tuning corpus.
 */
object ArticleExtractor {

    /** Never article text, regardless of where they appear. */
    private const val STRUCTURAL_JUNK =
        "script, style, noscript, iframe, form, svg, button, input, select, textarea, " +
            "nav, aside, header, footer, figure figcaption, [aria-hidden=true]"

    /**
     * Matched against class and id. Deliberately anchored to word boundaries: a bare
     * `contains(nav)` also matches "navigation" (fine) but equally "navrátil" or a
     * hashed CSS class like `x1nav8kq`, and silently deleting the article because its
     * wrapper class was minified is the worst possible failure — invisible and total.
     */
    private val JUNK_ATTR = Regex(
        "(^|[^a-z])(nav|navbar|menu|sidebar|breadcrumb|share|social|comment|disqus|" +
            "related|recommend|popular|trending|newsletter|subscribe|signup|promo|advert|" +
            "sponsor|cookie|consent|banner|modal|popup|paywall|footer|masthead|" +
            "author-bio|tags?-list|pagination)([^a-z]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Blocks shorter than this are noise, not prose. */
    private const val MIN_BLOCK_CHARS = 25

    /** Below this, the result is chrome rather than an article; caller gets null. */
    private const val MIN_ARTICLE_CHARS = 200

    /**
     * A candidate above this link density is a link list, not prose — even if it is long.
     * 0.5 is deliberately permissive: Vietnamese news bodies often carry many in-text
     * links, and a tighter bound started discarding real articles.
     */
    private const val MAX_LINK_DENSITY = 0.5

    fun extract(html: String, baseUri: String = ""): Extraction? {
        val doc = runCatching { Jsoup.parse(html, baseUri) }.getOrNull() ?: return null
        strip(doc)

        val candidate = bestCandidate(doc) ?: return null
        val text = ContentNormalizer.normalize(candidate.element.html())
        if (text.length < MIN_ARTICLE_CHARS) return null

        return Extraction(
            text = text,
            html = candidate.element.html(),
            method = candidate.method,
            linkDensity = linkDensity(candidate.element),
        )
    }

    /**
     * An element holding at least this share of the page's text is the page's main
     * column, whatever its class says. See [strip].
     */
    private const val STRUCTURAL_SHARE = 0.4

    /**
     * Deletes what is never article text.
     *
     * THE GUARD ON THE CLASS RULE IS LOAD-BEARING, and it was added because the naive
     * version silently destroyed two of fourteen real pages:
     *
     *  - github.blog renders through WordPress, whose `<body>` class list describes the
     *    page LAYOUT — and it contains `no-sidebar`. Matching `sidebar` anywhere in that
     *    string deleted the entire document. A class announcing the ABSENCE of junk
     *    removed the article.
     *  - vnexpress.net nests `<article class="fck_detail">` inside
     *    `<div class="sidebar-1">`, which holds 92% of the page's text. The name is
     *    simply wrong about what it contains.
     *
     * The general lesson: a class name describes an element's role in the layout, not
     * the nature of its contents, and removing an element removes everything inside it.
     * So a junk-looking class is only acted on when the element is also SMALL — real
     * article containers are never a small share of the page. `html`/`body` are never
     * candidates at all.
     */
    private fun strip(doc: Document) {
        doc.select(STRUCTURAL_JUNK).remove()

        val pageChars = doc.text().length.coerceAtLeast(1)
        // Two passes: removing a junk container can leave children that were only
        // meaningful inside it, e.g. a "related" list whose wrapper is gone.
        repeat(2) {
            doc.select("[class], [id]")
                .filter { el ->
                    el.tagName() !in setOf("html", "body") &&
                        el.text().length.toDouble() / pageChars < STRUCTURAL_SHARE &&
                        (JUNK_ATTR.containsMatchIn(el.className()) || JUNK_ATTR.containsMatchIn(el.id()))
                }
                .forEach { it.remove() }
        }
    }

    private class Candidate(val element: Element, val score: Double, val method: String)

    private fun bestCandidate(doc: Document): Candidate? {
        val scores = mutableMapOf<Element, Double>()

        for (block in doc.select("p, pre, blockquote, td, li > div, article > div")) {
            val len = block.text().length
            if (len < MIN_BLOCK_CHARS) continue

            // Commas approximate sentence complexity — prose has them, UI labels don't.
            var score = 1.0 + block.text().count { it == ',' || it == '，' }
            score += minOf(len / 100.0, 3.0)

            // Ancestors accumulate their children's prose. Halving at each level is what
            // makes the immediate container win over <body>, which would otherwise
            // always score highest simply by containing everything.
            var weight = 1.0
            var parent = block.parent()
            var depth = 0
            while (parent != null && depth < 3) {
                scores.merge(parent, score * weight, Double::plus)
                weight /= 2.0
                parent = parent.parent()
                depth++
            }
        }

        val best = scores
            .mapValues { (el, s) -> s * (1.0 - linkDensity(el)) }
            .filterKeys { linkDensity(it) <= MAX_LINK_DENSITY }
            .maxByOrNull { it.value }

        if (best != null) return Candidate(best.key, best.value, "readability")

        // Fallback for pages whose body is one unbroken block with no <p> at all.
        val semantic = doc.selectFirst("article, main, [role=main]") ?: return null
        return Candidate(semantic, 0.0, "semantic-fallback")
    }

    /**
     * Fraction of the element's text that sits inside anchors.
     *
     * The sharpest single discriminator in the whole heuristic: a nav block is ~1.0, a
     * "related articles" list is ~0.9, an article body with a few citations is <0.15.
     */
    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 1.0
        val linked = el.select("a").sumOf { it.text().length }
        return (linked.toDouble() / total).coerceIn(0.0, 1.0)
    }
}
