package vn.phusa.ingest

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Phase-0 ingest config. The full per-source config + crawl_job queue lands in
 * Phase 1; for now this is one scheduled source, enough to satisfy the Phase 0
 * exit ("a stranger sees articles a cron job put there").
 */
@ConfigurationProperties(prefix = "phusa.ingest")
data class IngestProperties(
    /** Master switch — turned off in tests so the scheduler/seeder don't run. */
    val enabled: Boolean = true,
    val intervalMs: Long = 900_000, // 15 min
    val initialDelayMs: Long = 8_000,
    val defaultSource: DefaultSource = DefaultSource(),
) {
    data class DefaultSource(
        val slug: String = "devto",
        val name: String = "DEV Community",
        val homepageUrl: String = "https://dev.to",
        val feedUrl: String = "https://dev.to/feed",
    )
}
