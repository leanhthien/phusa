package vn.phusa.crawl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import vn.phusa.domain.Source
import vn.phusa.ingest.FeedFetcher
import vn.phusa.ingest.RssIngestService
import vn.phusa.ingest.SourceConfigCodec
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Claims jobs and works them.
 *
 * TRANSACTION SHAPE — the part that matters most and is easiest to get wrong:
 *
 *   1. claim      one short transaction, commits IMMEDIATELY
 *   2. fetch      NO transaction (slow, flaky, external)
 *   3. ingest     its own transaction, inside RssIngestService
 *   4. finish     one short transaction
 *
 * The temptation is to wrap the whole loop in `@Transactional` so "a job either
 * completes or it doesn't". That would be a disaster: it pins a database connection
 * for the entire HTTP fetch, holds the claim's row locks the whole time (so SKIP
 * LOCKED protects nothing — the rows are locked, not skipped-over-and-free), and turns
 * one slow source into pool exhaustion for the whole app.
 *
 * What makes it safe to commit the claim early is the LEASE. `locked_until` is an
 * application-level statement that says "this job is spoken for until time T". It
 * outlives the row lock, it is visible to every other process, and if the worker dies
 * the reaper reclaims the job when T passes. A row lock cannot do that — it vanishes
 * with the connection and tells no one why.
 *
 * This class is not `@Transactional` anywhere, deliberately. Each DAO call runs in its
 * own autocommit transaction, which is exactly the granularity described above.
 */
@Component
class CrawlWorker(
    private val jobs: CrawlJobDao,
    private val fetcher: FeedFetcher,
    private val ingest: RssIngestService,
    private val configCodec: SourceConfigCodec,
    private val props: CrawlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Identifies which worker holds a lease. Host + PID rather than a random UUID so a
     * stuck job in the admin UI points at a process you can actually go and look at.
     */
    private val workerId: String = buildString {
        append(runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown"))
        append('/')
        append(ManagementFactory.getRuntimeMXBean().name.substringBefore('@'))
    }

    /** Claims up to [CrawlProperties.batchSize] jobs and runs them. Returns how many it worked. */
    fun runBatch(): Int {
        val now = Instant.now()
        val claimed = jobs.claimBatch(workerId, props.batchSize, props.leaseSeconds, now)
        if (claimed.isEmpty()) return 0

        log.info("Claimed {} job(s) as {}", claimed.size, workerId)
        for (job in claimed) {
            // Per-job isolation: one exploding source must never abort the batch, and
            // must never leave its own job stuck in 'running' either.
            runCatching { work(job) }
                .onFailure { log.error("Job {} ({}) escaped its handler", job.jobId, job.sourceSlug, it) }
        }
        return claimed.size
    }

    private fun work(job: ClaimedJob) {
        val started = System.nanoTime()
        val feedUrl = job.feedUrl
        if (feedUrl.isNullOrBlank()) {
            finishFailed(job, "source has no feed_url", httpStatus = null, elapsedMs(started))
            return
        }

        try {
            val config = configCodec.read(sourceStub(job))
            val feed = fetcher.fetch(feedUrl, config)
            // Transaction #3 — opened and closed inside RssIngestService.
            val result = ingest.ingest(sourceStub(job).apply { id = job.sourceId }, feed, config)

            val now = Instant.now()
            jobs.markSucceeded(job.jobId, now)
            jobs.recordSourceOutcome(job.sourceId, success = true, now = now, backoffSeconds = 0)
            jobs.logAttempt(
                jobId = job.jobId,
                sourceId = job.sourceId,
                httpStatus = 200,
                durationMs = elapsedMs(started),
                itemsFound = result.fetched,
                itemsNew = result.written,
                error = null,
            )
            log.info(
                "  {} ok: {} found, {} new ({}ms)",
                job.sourceSlug, result.fetched, result.written, elapsedMs(started),
            )
        } catch (e: Exception) {
            finishFailed(job, e.message ?: e::class.simpleName ?: "unknown error", null, elapsedMs(started))
        }
    }

    private fun finishFailed(job: ClaimedJob, error: String, httpStatus: Int?, durationMs: Int) {
        val now = Instant.now()
        val retryAt = now.plusSeconds(retryDelaySeconds(job.attempt))
        jobs.markFailed(job.jobId, error, retryAt, now)

        // Source-level backoff is driven by consecutive_failures, which is a property
        // of the SOURCE across jobs — distinct from the per-job retry above. A site
        // that has been down for a day should be probed hourly, not every 15 minutes.
        val failures = jobs.consecutiveFailures(job.sourceId) + 1
        jobs.recordSourceOutcome(job.sourceId, success = false, now = now, backoffSeconds = sourceBackoffSeconds(failures))
        jobs.logAttempt(job.jobId, job.sourceId, httpStatus, durationMs, null, null, error)

        val terminal = job.attempt >= job.maxAttempts
        if (terminal) {
            log.warn("  {} FAILED permanently after {} attempts: {}", job.sourceSlug, job.attempt, error)
        } else {
            log.warn("  {} failed (attempt {}/{}), retry at {}: {}", job.sourceSlug, job.attempt, job.maxAttempts, retryAt, error)
        }
    }

    /**
     * Exponential backoff with jitter: 1m, 2m, 4m, 8m… capped.
     *
     * The jitter is not decoration. Without it, a batch of jobs that all failed at the
     * same moment (a network blip, a DNS outage) retries at the same moment, hammering
     * whatever just recovered — the thundering herd. Spreading retries over a window
     * costs nothing and removes the synchronisation.
     */
    private fun retryDelaySeconds(attempt: Int): Long {
        val exponential = props.retryBaseSeconds * 2.0.pow((attempt - 1).coerceAtLeast(0))
        val capped = min(exponential, props.retryMaxSeconds.toDouble())
        val jitter = Random.nextDouble(0.5, 1.0)
        return (capped * jitter).toLong().coerceAtLeast(1)
    }

    /** Source-level backoff on consecutive failures, capped so a source is never abandoned. */
    private fun sourceBackoffSeconds(consecutiveFailures: Int): Long {
        val exponential = props.retryBaseSeconds * 2.0.pow((consecutiveFailures - 1).coerceIn(0, 10))
        return min(exponential, props.sourceBackoffMaxSeconds.toDouble()).toLong()
    }

    private fun elapsedMs(startNanos: Long): Int =
        Duration.ofNanos(System.nanoTime() - startNanos).toMillis().toInt()

    /**
     * The ingest path takes a [Source]; the claim returns only the columns it needs.
     * Rehydrating a full JPA entity per job would be a wasted round-trip — the claim
     * already fetched slug, feed_url and config in its RETURNING clause.
     */
    private fun sourceStub(job: ClaimedJob) = Source(
        slug = job.sourceSlug,
        name = job.sourceSlug,
        homepageUrl = "",
        kind = "rss",
        feedUrl = job.feedUrl,
        config = job.sourceConfig,
    ).apply { id = job.sourceId }
}
