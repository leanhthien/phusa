package vn.phusa.crawl

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A claimed unit of work, joined to the source it belongs to. */
/** An article awaiting a body. */
data class PendingArticle(val id: Long, val canonicalUrl: String)

data class ClaimedJob(
    val jobId: Long,
    val sourceId: Long,
    val attempt: Int,
    val maxAttempts: Int,
    val sourceSlug: String,
    val feedUrl: String?,
    val sourceConfig: String,
    /** HTTP validators from the previous fetch; sent back to make this request conditional. */
    val httpEtag: String?,
    val httpLastModified: String?,
)

/**
 * The durable work queue. All hand-written SQL — the interesting behaviour here lives
 * in the *statements*, not in an object graph, and JPA would hide exactly the parts
 * worth being able to explain.
 *
 * WHY A TABLE AND NOT REDIS: enqueue is transactional. The job and whatever else the
 * transaction wrote commit together, so it is structurally impossible to enqueue work
 * for a row that rolled back. A Redis queue is a second system that can disagree with
 * the database, and reconciling them is a distributed-transaction problem you don't
 * need at this size. It also survives a flush and is queryable by the admin UI.
 * Kafka arrives in Phase 4 for a genuinely different job (fan-out of
 * `article.discovered` to several independent consumers).
 */
@Repository
class CrawlJobDao(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /**
     * Enqueue one `fetch_feed` job per source that is due.
     *
     * `ON CONFLICT ... WHERE` is not optional here. `crawl_job_live_uk` is a PARTIAL
     * unique index (`WHERE state IN ('pending','running')`), and Postgres will only
     * infer a partial index if the ON CONFLICT clause repeats its predicate verbatim.
     * Omit it and you get "there is no unique or exclusion constraint matching the ON
     * CONFLICT specification" at runtime, not at compile time.
     *
     * What that index buys: at most one live job per (source, type). A source that has
     * been down for an hour cannot accumulate 400 pending jobs, and the guarantee is
     * enforced by the database rather than hoped for in service code that races with
     * itself the moment there are two app instances.
     */
    fun enqueueDueSources(now: Instant): Int = jdbc.update(
        """
        INSERT INTO crawl_job (source_id, job_type, state, scheduled_at)
        SELECT s.id, 'fetch_feed', 'pending', :now
          FROM source s
         WHERE s.enabled
           AND s.feed_url IS NOT NULL
           AND s.next_crawl_at <= :now
        ON CONFLICT (source_id, job_type) WHERE state IN ('pending', 'running')
        DO NOTHING
        """.trimIndent(),
        MapSqlParameterSource().addValue("now", now.atOffset(ZoneOffset.UTC)),
    )

    /**
     * Claim a batch. This is the load-bearing query of the whole phase.
     *
     * `FOR UPDATE SKIP LOCKED` makes the inner SELECT step *over* rows another worker
     * has already locked instead of blocking behind them. Without SKIP LOCKED, N
     * workers running this query serialise: each waits for the one ahead to commit,
     * and throughput collapses to a single worker's. With it, N workers claim N
     * disjoint batches concurrently with no coordination and no external lock service.
     *
     * The subquery form matters. `FOR UPDATE` cannot be attached to an UPDATE
     * directly, and it cannot appear with aggregates or DISTINCT — so the pattern is
     * SELECT-the-ids-with-locking, then UPDATE by those ids in the same statement.
     * Both halves run in one transaction, so a row cannot be claimed twice.
     *
     * `attempt < max_attempts` in the predicate is a guard against a real trap: the
     * CHECK constraint on this table is `attempt <= max_attempts`, and this statement
     * does `attempt = attempt + 1`. A job that reached max_attempts and was later
     * returned to 'pending' by the reaper would violate the CHECK on its next claim
     * and blow up the whole batch. Verified against the live schema — it errors.
     *
     * NOTE ON THE LEASE: the row lock taken here lives only until this statement's
     * transaction commits, which is immediately. It is NOT what protects the job while
     * it is being worked. That is `locked_until` — an application-level lease the
     * reaper can see and reclaim. Holding a database transaction open across a 20s
     * HTTP fetch would pin a connection, defeat SKIP LOCKED entirely, and leave rows
     * locked with no way for another process to tell whether the worker was alive.
     */
    /**
     * [jobType] is NOT optional and there is deliberately no default.
     *
     * Until `fetch_article` existed, every row in `crawl_job` was a `fetch_feed` and the
     * claim ignored `job_type` — harmless while there was one type, and a live bug the
     * instant there were two: the feed worker would happily claim an article job and try
     * to parse an HTML page as RSS. Making the parameter required means adding a third
     * job type later cannot silently reintroduce that.
     */
    fun claimBatch(worker: String, jobType: String, batchSize: Int, leaseSeconds: Long, now: Instant): List<ClaimedJob> =
        jdbc.query(
            """
            UPDATE crawl_job j
               SET state        = 'running',
                   locked_by    = :worker,
                   locked_until = :now::timestamptz + make_interval(secs => :leaseSeconds),
                   started_at   = :now,
                   attempt      = j.attempt + 1
             WHERE j.id IN (
                   SELECT c.id
                     FROM crawl_job c
                    WHERE c.state = 'pending'
                      AND c.job_type = :jobType
                      AND c.scheduled_at <= :now
                      AND c.attempt < c.max_attempts
                    ORDER BY c.scheduled_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
             )
            RETURNING j.id, j.source_id, j.attempt, j.max_attempts,
                      (SELECT s.slug               FROM source s WHERE s.id = j.source_id) AS source_slug,
                      (SELECT s.feed_url           FROM source s WHERE s.id = j.source_id) AS feed_url,
                      (SELECT s.config             FROM source s WHERE s.id = j.source_id) AS source_config,
                      (SELECT s.http_etag          FROM source s WHERE s.id = j.source_id) AS http_etag,
                      (SELECT s.http_last_modified FROM source s WHERE s.id = j.source_id) AS http_last_modified
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("worker", worker)
                .addValue("jobType", jobType)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds)
                .addValue("now", now.atOffset(ZoneOffset.UTC)),
        ) { rs, _ ->
            ClaimedJob(
                jobId = rs.getLong("id"),
                sourceId = rs.getLong("source_id"),
                attempt = rs.getInt("attempt"),
                maxAttempts = rs.getInt("max_attempts"),
                sourceSlug = rs.getString("source_slug"),
                feedUrl = rs.getString("feed_url"),
                sourceConfig = rs.getString("source_config") ?: "{}",
                httpEtag = rs.getString("http_etag"),
                httpLastModified = rs.getString("http_last_modified"),
            )
        }

    /**
     * Persist the validators a 200 response gave us, so the next fetch can be
     * conditional.
     *
     * COALESCE, not plain assignment: a server is allowed to send only one of the two,
     * and overwriting a good stored ETag with NULL because this particular response
     * omitted it would silently disable conditional GET for that source. Keep whatever
     * we had unless the server offered something newer.
     *
     * Deliberately NOT called on 304. A 304 means the stored validators are still
     * correct — and RFC 9110 lets a 304 omit the ETag entirely, so treating its
     * headers as authoritative would erase working state on every successful hit.
     */
    fun storeValidators(sourceId: Long, etag: String?, lastModified: String?): Int {
        if (etag == null && lastModified == null) return 0
        return jdbc.update(
            """
            UPDATE source
               SET http_etag          = COALESCE(:etag, http_etag),
                   http_last_modified = COALESCE(:lastModified, http_last_modified)
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", sourceId)
                .addValue("etag", etag)
                .addValue("lastModified", lastModified),
        )
    }

    /** Job finished cleanly. Clearing the lease is what lets the partial unique index free up. */
    fun markSucceeded(jobId: Long, now: Instant): Int = jdbc.update(
        """
        UPDATE crawl_job
           SET state = 'succeeded', finished_at = :now,
               locked_by = NULL, locked_until = NULL, last_error = NULL
         WHERE id = :id
        """.trimIndent(),
        MapSqlParameterSource().addValue("id", jobId).addValue("now", now.atOffset(ZoneOffset.UTC)),
    )

    /**
     * Job failed. Either schedule a retry or bury it.
     *
     * The decision is made in SQL against the row's own `attempt`/`max_attempts`
     * rather than from the value the worker happens to be holding, so a concurrent
     * reaper or a stale in-memory copy cannot talk it into one more attempt than the
     * table allows.
     *
     * 'dead' is a terminal state on purpose: no retry, no lease, and — because it is
     * outside the partial unique index's predicate — the source becomes eligible for a
     * fresh job on its next due time. A dead job is a diagnosis, not a tombstone.
     */
    fun markFailed(jobId: Long, error: String, retryAt: Instant, now: Instant): Int = jdbc.update(
        """
        UPDATE crawl_job
           SET state = CASE WHEN attempt >= max_attempts THEN 'dead' ELSE 'pending' END,
               scheduled_at = CASE WHEN attempt >= max_attempts THEN scheduled_at ELSE :retryAt END,
               finished_at  = CASE WHEN attempt >= max_attempts THEN :now ELSE NULL END,
               last_error   = :error,
               locked_by    = NULL,
               locked_until = NULL
         WHERE id = :id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", jobId)
            .addValue("error", error.take(2000))
            .addValue("retryAt", retryAt.atOffset(ZoneOffset.UTC))
            .addValue("now", now.atOffset(ZoneOffset.UTC)),
    )

    /**
     * Lease reaper: reclaim jobs whose worker died mid-run.
     *
     * Without this a worker that is OOM-killed (or a container that is rescheduled)
     * leaves its jobs in 'running' forever — and because 'running' is inside the
     * partial unique index, that source can never be enqueued again. One crash would
     * silently retire a source. This is the cheapest possible insurance against that.
     *
     * The CASE mirrors markFailed: a job that has already exhausted its attempts must
     * go to 'dead', never back to 'pending', or it re-claims into the CHECK violation
     * described on claimBatch.
     */
    fun reapExpiredLeases(now: Instant): Int = jdbc.update(
        """
        UPDATE crawl_job
           SET state = CASE WHEN attempt >= max_attempts THEN 'dead' ELSE 'pending' END,
               finished_at = CASE WHEN attempt >= max_attempts THEN :now ELSE NULL END,
               last_error = COALESCE(last_error, 'lease expired; worker presumed dead'),
               locked_by = NULL,
               locked_until = NULL
         WHERE state = 'running'
           AND locked_until < :now
        """.trimIndent(),
        MapSqlParameterSource().addValue("now", now.atOffset(ZoneOffset.UTC)),
    )

    /**
     * Push a source's next due time out WITHOUT recording a failure.
     *
     * For the robots.txt case specifically: the source is not broken and nothing failed,
     * so incrementing `consecutive_failures` would be a lie that eventually marks a
     * perfectly healthy site as dying. But re-asking every `crawl_interval_sec` is
     * pointless too — the answer cannot change until the cached robots.txt expires. So
     * this defers by the robots TTL and leaves the failure counters alone.
     *
     * Deliberately re-checked rather than permanently disabled: a site's robots.txt can
     * change, and a crawler that gives up forever on one 24-hour-old answer is as wrong
     * as one that never asks.
     */
    fun deferSource(sourceId: Long, now: Instant, seconds: Long): Int =
        jdbc.update(
            """
            UPDATE source
               SET last_crawled_at = :now,
                   next_crawl_at   = :now::timestamptz + make_interval(secs => :seconds)
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", sourceId)
                .addValue("now", now.atOffset(ZoneOffset.UTC))
                .addValue("seconds", seconds),
        )

    /**
     * Push the source's next due time out and record the outcome.
     *
     * On failure `next_crawl_at` backs off on `consecutive_failures`, so a dead site is
     * not hit every 15 minutes forever. That is politeness *and* self-interest: a
     * source that 500s all day should cost us one request an hour, not 96.
     */
    fun recordSourceOutcome(sourceId: Long, success: Boolean, now: Instant, backoffSeconds: Long): Int =
        jdbc.update(
            if (success) {
                """
                UPDATE source
                   SET last_crawled_at = :now,
                       consecutive_failures = 0,
                       next_crawl_at = :now::timestamptz + make_interval(secs => crawl_interval_sec)
                 WHERE id = :id
                """.trimIndent()
            } else {
                """
                UPDATE source
                   SET last_crawled_at = :now,
                       consecutive_failures = consecutive_failures + 1,
                       next_crawl_at = :now::timestamptz + make_interval(secs => :backoffSeconds)
                 WHERE id = :id
                """.trimIndent()
            },
            MapSqlParameterSource()
                .addValue("id", sourceId)
                .addValue("now", now.atOffset(ZoneOffset.UTC))
                .addValue("backoffSeconds", backoffSeconds),
        )

    /** Append-only audit of one HTTP attempt. What tells you a source started 403ing on Tuesday. */
    fun logAttempt(
        jobId: Long?,
        sourceId: Long,
        httpStatus: Int?,
        durationMs: Int,
        itemsFound: Int?,
        itemsNew: Int?,
        error: String?,
    ): Int = jdbc.update(
        """
        INSERT INTO crawl_log (job_id, source_id, http_status, duration_ms, items_found, items_new, error)
        VALUES (:jobId, :sourceId, :httpStatus, :durationMs, :itemsFound, :itemsNew, :error)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("jobId", jobId)
            .addValue("sourceId", sourceId)
            .addValue("httpStatus", httpStatus)
            .addValue("durationMs", durationMs)
            .addValue("itemsFound", itemsFound)
            .addValue("itemsNew", itemsNew)
            .addValue("error", error?.take(2000)),
    )

    fun consecutiveFailures(sourceId: Long): Int = jdbc.queryForObject(
        "SELECT consecutive_failures FROM source WHERE id = :id",
        MapSqlParameterSource().addValue("id", sourceId),
        Int::class.java,
    ) ?: 0

    /**
     * Enqueue one `fetch_article` job per source that still has articles worth trying.
     *
     * One job per SOURCE, not per article — `crawl_job_live_uk` is
     * `UNIQUE(source_id, job_type) WHERE state IN ('pending','running')`, so a job per
     * article is not expressible. That constraint turns out to be exactly right: one
     * live job per source serialises the work naturally, which is most of a rate limiter
     * before [HostRateLimiter] does anything.
     *
     * `EXISTS` rather than a join + DISTINCT: it stops at the first qualifying article
     * per source instead of building the whole set and deduplicating it.
     */
    fun enqueueExtractionWork(now: Instant, maxAttempts: Int): Int = jdbc.update(
        """
        INSERT INTO crawl_job (source_id, job_type, state, scheduled_at)
        SELECT s.id, 'fetch_article', 'pending', :now
          FROM source s
         WHERE s.enabled
           AND EXISTS (
                 SELECT 1 FROM article a
                  WHERE a.source_id = s.id
                    AND a.status <> 'duplicate'
                    AND a.extract_attempts < :maxAttempts
                    AND NOT EXISTS (SELECT 1 FROM article_content c WHERE c.article_id = a.id)
               )
        ON CONFLICT (source_id, job_type) WHERE state IN ('pending', 'running')
        DO NOTHING
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("now", now.atOffset(ZoneOffset.UTC))
            .addValue("maxAttempts", maxAttempts),
    )

    /**
     * The next articles to try for one source, newest first.
     *
     * Newest-first because a reader wants today's article to have a body; a three-week-
     * old backlog entry can wait. Backed by `article_extract_pending_idx`
     * `(source_id, published_at DESC) WHERE extract_attempts < 3 AND status <> 'duplicate'`.
     */
    fun articlesNeedingExtraction(sourceId: Long, limit: Int, maxAttempts: Int): List<PendingArticle> =
        jdbc.query(
            """
            SELECT a.id, a.canonical_url
              FROM article a
             WHERE a.source_id = :sourceId
               AND a.status <> 'duplicate'
               AND a.extract_attempts < :maxAttempts
               AND NOT EXISTS (SELECT 1 FROM article_content c WHERE c.article_id = a.id)
             ORDER BY a.published_at DESC
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("limit", limit)
                .addValue("maxAttempts", maxAttempts),
        ) { rs, _ -> PendingArticle(rs.getLong("id"), rs.getString("canonical_url")) }

    /** Bumped on every try, win or lose — it is what stops an unextractable page forever. */
    fun bumpExtractAttempt(articleId: Long): Int = jdbc.update(
        "UPDATE article SET extract_attempts = extract_attempts + 1 WHERE id = :id",
        MapSqlParameterSource().addValue("id", articleId),
    )

    /**
     * Store an extracted body and the hash/word count derived from it.
     *
     * Two statements rather than one, because they touch different tables; both run
     * inside the caller's transaction. The `IS DISTINCT FROM` guard on `article_content`
     * matters for the same reason as in the feed path — writing `text_plain` fires the
     * tsvector trigger, and re-indexing an unchanged body is pure waste.
     */
    fun storeExtraction(
        articleId: Long,
        textPlain: String,
        html: String?,
        extractor: String,
        contentHash: ByteArray?,
        wordCount: Int?,
    ): Int {
        jdbc.update(
            """
            INSERT INTO article_content (article_id, text_plain, html, extractor)
            VALUES (:articleId, :textPlain, :html, :extractor)
            ON CONFLICT (article_id) DO UPDATE
               SET text_plain   = EXCLUDED.text_plain,
                   html         = EXCLUDED.html,
                   extractor    = EXCLUDED.extractor,
                   extracted_at = now()
             WHERE article_content.text_plain IS DISTINCT FROM EXCLUDED.text_plain
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("articleId", articleId)
                .addValue("textPlain", textPlain)
                .addValue("html", html)
                .addValue("extractor", extractor),
        )
        return jdbc.update(
            """
            UPDATE article
               SET content_hash = COALESCE(:contentHash, content_hash),
                   word_count   = COALESCE(:wordCount, word_count)
             WHERE id = :articleId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("articleId", articleId)
                .addValue("contentHash", contentHash)
                .addValue("wordCount", wordCount),
        )
    }

    /** Queue depth by state — the number the Phase 6 Grafana dashboard graphs. */
    fun countsByState(): Map<String, Int> = jdbc.query(
        "SELECT state, count(*) AS n FROM crawl_job GROUP BY state",
        MapSqlParameterSource(),
    ) { rs, _ -> rs.getString("state") to rs.getInt("n") }.toMap()

    private fun Instant.atOffset(zone: ZoneOffset): OffsetDateTime = OffsetDateTime.ofInstant(this, zone)
}
