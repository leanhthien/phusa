package vn.phusa.ingest

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import vn.phusa.repo.SourceRepository

/**
 * The "cron job" of the Phase-0 exit criterion: on an interval, crawl every enabled
 * source that has a feed. Errors are per-source and logged, never fatal — one dead
 * feed must not stop the others.
 *
 * Deliberately simple: it scans all sources each tick. The real scheduler — due-time
 * selection, the `crawl_job` table, FOR UPDATE SKIP LOCKED, retries/backoff — is
 * Phase 1. This is the smallest thing that makes articles appear unattended.
 */
@Component
@ConditionalOnProperty(prefix = "phusa.ingest", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IngestScheduler(
    private val sources: SourceRepository,
    private val orchestrator: FeedIngestOrchestrator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${phusa.ingest.interval-ms:900000}",
        initialDelayString = "\${phusa.ingest.initial-delay-ms:8000}",
    )
    fun crawlEnabledSources() {
        val due = sources.findAll().filter { it.enabled && !it.feedUrl.isNullOrBlank() }
        if (due.isEmpty()) return
        log.info("Scheduled crawl: {} source(s)", due.size)
        for (source in due) {
            try {
                val result = orchestrator.run(source)
                log.info("  {} → {} fetched, {} written", source.slug, result.fetched, result.written)
            } catch (e: Exception) {
                log.warn("  {} → crawl failed: {}", source.slug, e.message)
            }
        }
    }
}
