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
    private val configCodec: SourceConfigCodec,
) {
    fun run(source: Source): IngestResult {
        val feedUrl = requireNotNull(source.feedUrl) { "Source ${source.slug} has no feed_url" }
        // Parsed once per crawl and threaded through both steps, so the fetch and the
        // ingest cannot disagree about what this source's settings are.
        val config = configCodec.read(source)
        return ingest.ingest(source, fetcher.fetch(feedUrl, config), config)
    }
}
