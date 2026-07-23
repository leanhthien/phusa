package vn.phusa.crawl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Drives the queue on a timer. Three separate concerns, three separate schedules —
 * deliberately not one method, because they fail independently and want different
 * cadences.
 *
 * This replaces the Phase-0 IngestScheduler, which crawled every enabled source on
 * every tick and ignored `crawl_interval_sec` entirely. With 20 sources that would be
 * 20 requests every 15 minutes regardless of how often each publisher actually
 * updates — martinfowler.com posts a few times a month and would have been fetched
 * ~2,900 times. Now each source declares its own interval and the enqueuer honours it.
 */
@Component
@ConditionalOnProperty(prefix = "phusa.crawl", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CrawlScheduler(
    private val jobs: CrawlJobDao,
    private val worker: CrawlWorker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Enqueue whatever is due. Cheap — one INSERT..SELECT, and the partial unique index
     * absorbs the duplicates, so running it more often than necessary costs nothing.
     */
    @Scheduled(fixedDelayString = "\${phusa.crawl.enqueue-interval-ms:60000}", initialDelayString = "5000")
    fun enqueue() {
        val n = jobs.enqueueDueSources(Instant.now())
        if (n > 0) log.info("Enqueued {} job(s)", n)
    }

    /**
     * Claim and work a batch.
     *
     * `fixedDelay` (not `fixedRate`) on purpose: the gap is measured from the END of
     * the previous run, so a slow batch can never overlap itself. `fixedRate` would
     * queue up invocations behind a long batch and then fire them back to back.
     * SKIP LOCKED means a second worker would be *correct* — it just wouldn't be
     * something we chose.
     */
    @Scheduled(fixedDelayString = "\${phusa.crawl.poll-interval-ms:15000}", initialDelayString = "10000")
    fun work() {
        val worked = worker.runBatch()
        if (worked > 0) log.debug("Worked {} job(s)", worked)
    }

    /**
     * Reclaim leases from workers that died. Runs on its own slower schedule; there is
     * no point checking for expired leases more often than leases can expire.
     */
    @Scheduled(fixedDelayString = "\${phusa.crawl.reap-interval-ms:120000}", initialDelayString = "30000")
    fun reap() {
        val n = jobs.reapExpiredLeases(Instant.now())
        if (n > 0) log.warn("Reaped {} expired lease(s) — a worker died mid-job", n)
    }
}
