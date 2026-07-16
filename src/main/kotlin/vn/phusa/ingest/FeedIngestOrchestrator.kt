package vn.phusa.ingest

import org.springframework.stereotype.Service
import vn.phusa.domain.Source

/**
 * Wires the network step to the persistence step: fetch (no transaction) → ingest
 * (transactional). Both are calls to *other* beans, so the proxy-based
 * `@Transactional` on [RssIngestService.ingest] takes effect — unlike a
 * self-invocation inside a single bean, which would silently bypass it.
 */
@Service
class FeedIngestOrchestrator(
    private val fetcher: FeedFetcher,
    private val ingest: RssIngestService,
) {
    fun run(source: Source): IngestResult {
        val feedUrl = requireNotNull(source.feedUrl) { "Source ${source.slug} has no feed_url" }
        return ingest.ingest(source, fetcher.fetch(feedUrl))
    }
}
