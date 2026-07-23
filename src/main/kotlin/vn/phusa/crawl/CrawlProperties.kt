package vn.phusa.crawl

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Deployment-level crawl settings — how *this instance* runs the queue. Anything that
 * varies per source belongs in the `source` table (columns) or `source.config` (JSONB),
 * not here. See SourceConfig for that division.
 */
@ConfigurationProperties(prefix = "phusa.crawl")
data class CrawlProperties(
    /** Master switch. Off in tests so the scheduler doesn't race the test's own fixtures. */
    val enabled: Boolean = true,

    /** How many jobs one worker claims per tick. */
    val batchSize: Int = 10,

    /**
     * Lease length. Must comfortably exceed the slowest realistic job: if the lease
     * expires while the worker is still going, the reaper hands the job to someone else
     * and the same feed is fetched twice. The upsert makes that harmless rather than
     * corrupting, but it is wasted work and a doubled request to the publisher.
     */
    val leaseSeconds: Long = 300,

    /** First retry delay; doubles per attempt. */
    val retryBaseSeconds: Long = 60,

    /** Ceiling on the per-job retry delay. */
    val retryMaxSeconds: Long = 3600,

    /** Ceiling on the per-source backoff, so a source that recovers is picked up within the hour. */
    val sourceBackoffMaxSeconds: Long = 3600,
)
