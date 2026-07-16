package vn.phusa.ingest

import com.rometools.rome.feed.synd.SyndFeed
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import vn.phusa.domain.Source
import vn.phusa.repo.ArticleRepository
import java.time.Instant

/** [fetched] = entries in the feed; [written] = rows inserted or actually changed. */
data class IngestResult(val fetched: Int, val written: Int)

/**
 * Turns a parsed feed into `article` rows via an idempotent upsert.
 *
 * Note the split from [FeedFetcher]: the network fetch is NOT inside the DB
 * transaction — you never want to hold a Postgres transaction open across a slow,
 * flaky HTTP call. Callers fetch first, then hand the parsed feed here. That also
 * sidesteps the `@Transactional` self-invocation trap: because this method is
 * invoked from another bean ([FeedIngestOrchestrator]) it goes through the proxy
 * and the transaction actually applies.
 */
@Service
class RssIngestService(
    private val articles: ArticleRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun ingest(source: Source, feed: SyndFeed): IngestResult {
        val sourceId = requireNotNull(source.id) { "Source must be persisted before ingest" }
        var written = 0

        for (entry in feed.entries) {
            val link = entry.link?.trim().orEmpty()
            if (link.isBlank()) continue

            val title = entry.title?.trim().orEmpty().ifBlank { link }
            val summary = cleanSummary(entry.description?.value)
            val publishedAt = (entry.publishedDate ?: entry.updatedDate)?.toInstant() ?: Instant.now()

            // canonical_url == link for now. Canonicalization (strip utm_*, drop
            // fragments, sort query params) is a Phase 1 dedup layer. url_hash is
            // GENERATED from this value in the DB, so it's the upsert's conflict key.
            written += articles.upsert(sourceId, link, title, summary, publishedAt)
        }

        log.info("Ingested {}: {} entries, {} written", source.slug, feed.entries.size, written)
        return IngestResult(fetched = feed.entries.size, written = written)
    }

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
