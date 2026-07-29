package vn.phusa.dedup

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What one sweep did. [flattened] is chain repair, which should almost always be 0. */
data class ResolveResult(val demoted: Int, val flattened: Int)

/**
 * Promotes DETECTED duplicates into RESOLVED ones.
 *
 * `content_hash` only says "these two bodies are byte-identical after normalization".
 * Until something acts on that, both copies still render in the feed — the layer detects
 * and nothing more. This is the acting.
 *
 * A SWEEP RATHER THAN AN INLINE CHECK AT WRITE TIME, for three reasons. Hashes arrive
 * from two different paths (feed bodies in RssIngestService, extracted bodies in
 * ArticleExtractionWorker) and one sweep covers both without duplicating the rule.
 * Rows that were hashed before this code existed need resolving too, and a sweep is
 * the backfill as well as the steady state. And an inline check races: two workers
 * hashing identical bodies concurrently can each see no existing match and both insert.
 * The sweep is a single statement whose view of the world is one snapshot.
 *
 * EARLIEST `published_at` WINS, id as tie-break — the same rule V5 used for URL
 * collisions, deliberately, so the project has one answer to "which copy is the real
 * one" rather than one per layer.
 */
@Service
class DuplicateResolver(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun resolve(): ResolveResult {
        val demoted = demoteLosers()
        val flattened = flattenChains()
        if (demoted > 0 || flattened > 0) {
            log.info("Dedup sweep: {} article(s) marked duplicate, {} chain(s) flattened", demoted, flattened)
        }
        return ResolveResult(demoted, flattened)
    }

    /**
     * Every row sharing a `content_hash` with an earlier one becomes a duplicate of it.
     *
     * IDEMPOTENCE comes from the `status <> 'duplicate'` filter in the CTE: once a row
     * is demoted it leaves the candidate set, so the next sweep sees its group as a
     * single survivor and does nothing. Running this every minute is free.
     *
     * The filter is also what keeps this layer from fighting V5's. V5 resolved
     * URL-canonicalization collisions, and its losers keep `status='duplicate'` with
     * their original URL — which means they are never extracted, never hashed, and so
     * can never appear here. Verified against the live table: zero rows have both
     * `status='duplicate'` and a non-null `content_hash`.
     *
     * `article_not_self_dup CHECK (duplicate_of_id IS DISTINCT FROM id)` is satisfied by
     * `r.id <> r.winner_id`, and `article_dup_ck` (status='duplicate' implies a target)
     * by setting both columns in the same statement.
     */
    private fun demoteLosers(): Int = jdbc.update(
        """
        WITH ranked AS (
            SELECT id,
                   first_value(id) OVER (PARTITION BY content_hash ORDER BY published_at, id) AS winner_id,
                   count(*)        OVER (PARTITION BY content_hash) AS group_size
              FROM article
             WHERE content_hash IS NOT NULL
               AND status <> 'duplicate'
        )
        UPDATE article a
           SET status = 'duplicate',
               duplicate_of_id = r.winner_id
          FROM ranked r
         WHERE a.id = r.id
           AND r.group_size > 1
           AND r.id <> r.winner_id
        """.trimIndent(),
        MapSqlParameterSource(),
    )

    /**
     * Repoints a duplicate whose target has itself since become a duplicate.
     *
     * Reachable because winners are chosen by `published_at`, not by arrival order: an
     * article ingested today can carry an earlier `published_at` than one resolved
     * yesterday, which makes yesterday's winner today's loser. Without this, a reader
     * following `duplicate_of_id` lands on another hidden row instead of the surviving
     * article.
     *
     * ONE PASS, not a recursive CTE. A pass halves the depth of every chain, and a chain
     * deeper than two requires the "earlier article arrives later" event to happen twice
     * to the same group between sweeps — at a one-minute cadence that is not a real
     * scenario. The next sweep would finish the job anyway, so the invariant is
     * eventually true rather than never true, and the query stays readable.
     */
    private fun flattenChains(): Int = jdbc.update(
        """
        UPDATE article a
           SET duplicate_of_id = target.duplicate_of_id
          FROM article target
         WHERE a.duplicate_of_id = target.id
           AND target.status = 'duplicate'
           AND target.duplicate_of_id IS NOT NULL
           AND target.duplicate_of_id <> a.id
        """.trimIndent(),
        MapSqlParameterSource(),
    )
}
