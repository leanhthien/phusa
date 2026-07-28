package vn.phusa.ingest

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import vn.phusa.domain.Source
import vn.phusa.repo.ArticleRepository
import java.time.Instant

/**
 * [fetched] = entries actually considered this crawl (the feed's entries, after any
 * `maxItems` cap); [written] = rows inserted or actually changed.
 */
data class IngestResult(val fetched: Int, val written: Int)

/**
 * Turns a parsed feed into `article` rows via an idempotent upsert.
 *
 * Note the split from [FeedFetcher]: the network fetch is NOT inside the DB
 * transaction — you never want to hold a Postgres transaction open across a slow,
 * flaky HTTP call. Callers fetch first, then hand the parsed feed here. That also
 * sidesteps the `@Transactional` self-invocation trap: because this method is
 * invoked from another bean (`CrawlWorker`) it goes through the proxy and the
 * transaction actually applies.
 */
@Service
class RssIngestService(
    private val articles: ArticleRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Provenance for bodies taken straight from the feed. The column's default is
         * 'jsoup-readability', which will be the *fetched-page* extractor — so recording
         * this distinctly is what lets a later pass tell "we already have the real
         * article" from "we have what the feed gave us and could do better".
         */
        const val EXTRACTOR_FEED = "feed"
    }

    @Transactional
    fun ingest(
        source: Source,
        feed: SyndFeed,
        config: SourceConfig = SourceConfig.DEFAULTS,
    ): IngestResult {
        val sourceId = requireNotNull(source.id) { "Source must be persisted before ingest" }
        var written = 0
        var bodied = 0
        var shortBodies = 0

        // Applied before the loop, not inside it: `maxItems` caps how much of the feed
        // we consider at all, so a firehose source can't dominate a crawl cycle. Feed
        // order is newest-first by convention, so a cap keeps the recent items.
        val entries = config.maxItems?.let { feed.entries.take(it) } ?: feed.entries

        for (entry in entries) {
            val link = entry.link?.trim().orEmpty()
            if (link.isBlank()) continue

            val title = entry.title?.trim().orEmpty().ifBlank { link }
            val summary = cleanSummary(entry.description?.value)
            val publishedAt = (entry.publishedDate ?: entry.updatedDate)?.toInstant() ?: Instant.now()

            // Dedup layer 1. url_hash is GENERATED from canonical_url in the DB and
            // carries the UNIQUE constraint, so this call decides the upsert's conflict
            // key — canonicalize BEFORE the insert or the row lands under the wrong
            // identity and no later layer can recover it.
            val canonicalUrl = UrlCanonicalizer.canonicalize(link)

            // Dedup layer 2, but only where the feed actually carries the article. For
            // the other 15 sources body/hash stay NULL until the extractor lands — see
            // SourceConfig.feedHasFullContent for why this is declared and not detected.
            val bodyHtml = if (config.feedHasFullContent == true) richestBody(entry) else null
            val bodyText = ContentNormalizer.normalize(bodyHtml)
            val contentHash = ContentNormalizer.hash(bodyText)
            if (bodyHtml != null && contentHash == null) shortBodies++

            written += articles.upsert(
                sourceId, canonicalUrl, title, summary, publishedAt,
                contentHash, contentHash?.let { ContentNormalizer.wordCount(bodyText) },
            )

            // Only stored when it was worth hashing. Persisting a teaser under a column
            // named `text_plain` would quietly poison every later layer that trusts it
            // to be the article — and the FTS trigger would index the teaser as the body.
            if (contentHash != null) {
                bodied += articles.upsertContent(canonicalUrl, bodyText, bodyHtml, EXTRACTOR_FEED)
            }
        }

        log.info(
            "Ingested {}: {} entries considered ({} in feed), {} written{}",
            source.slug, entries.size, feed.entries.size, written,
            if (config.feedHasFullContent == true) ", $bodied bodies stored${if (shortBodies > 0) " ($shortBodies below the hash floor)" else ""}" else "",
        )
        return IngestResult(fetched = entries.size, written = written)
    }

    /**
     * Picks the richest body element the entry offers.
     *
     * Longest wins rather than first-match-by-name, because the element name does not
     * predict the content: Dev.to puts the whole post in `<description>` while Tinh tế
     * puts a teaser in `<content:encoded>`. Rome exposes `contents` for
     * `content:encoded` / Atom `<content>` and `description` for the rest.
     */
    private fun richestBody(entry: SyndEntry): String? =
        (entry.contents.mapNotNull { it.value } + listOfNotNull(entry.description?.value))
            .maxByOrNull { it.length }

    /**
     * RSS `<description>` is usually HTML. Strip it to a plain-text preview and cap the
     * length — the feed shows a teaser, not the article. jsoup handles tags and entity
     * unescaping correctly (a regex would mangle both).
     */
    private fun cleanSummary(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = Jsoup.parse(raw).text().trim()
        return text.ifBlank { null }?.let { if (it.length > 280) it.take(279).trimEnd() + "…" else it }
    }
}
