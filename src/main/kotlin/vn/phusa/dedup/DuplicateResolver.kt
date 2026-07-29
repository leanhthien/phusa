package vn.phusa.dedup

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import vn.phusa.ingest.ContentNormalizer

/**
 * What one sweep did. [flattened] is chain repair, which should almost always be 0;
 * [near] counts demotions attributable to simhash rather than an exact body match.
 */
data class ResolveResult(val demoted: Int, val near: Int, val flattened: Int, val fingerprinted: Int)

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
        val fingerprinted = backfillSimhashes()
        val demoted = demoteLosers()
        val near = demoteNearDuplicates()
        val flattened = flattenChains()
        if (demoted > 0 || near > 0 || flattened > 0 || fingerprinted > 0) {
            log.info(
                "Dedup sweep: {} fingerprinted, {} exact duplicate(s), {} near-duplicate(s), {} chain(s) flattened",
                fingerprinted, demoted, near, flattened,
            )
        }
        return ResolveResult(demoted, near, flattened, fingerprinted)
    }

    /**
     * Compute simhashes for bodies that do not have one yet.
     *
     * FINGERPRINTING LIVES HERE, NOT IN THE WRITERS, so there is one path rather than
     * two. Bodies arrive from the feed path and the extraction path, and threading a
     * simhash argument through both would mean two places that must both remember to
     * compute it — and a third when the next writer appears. This way the resolver owns
     * layer 3 end to end: fingerprint, then compare. The cost is that a new body is
     * unfingerprinted for at most one sweep interval, which for a dedup layer that
     * compares against a 48-hour window is not a meaningful delay.
     *
     * Lives here rather than in a Flyway migration for the reason V5's backfill did the
     * opposite: that one COULD be expressed in SQL and so was frozen there, while a
     * simhash cannot be — it needs the tokenizer, the shingling and the mix function,
     * which are application code. A migration calling app code would silently change
     * meaning whenever that code changed, which is exactly what a migration must never
     * do. So it is a self-healing sweep step instead: new bodies are fingerprinted by
     * the writers, and anything that slipped through gets picked up here.
     *
     * Batched so one sweep cannot hold a long transaction over the whole table.
     *
     * The `length(text_plain) >= :minChars` filter is not an optimisation — it is what
     * lets the backfill FINISH. `WHERE simhash IS NULL` has no memory, the same flaw V6
     * fixed for extraction attempts: a body below the fingerprint floor returns null
     * forever, so it would be re-read and re-rejected on every sweep for the life of the
     * project. Measured here: 17 of 655 bodies sit under the floor (212-475 chars), all
     * of them permanently unfingerprintable. Excluding them in SQL is correct rather
     * than a workaround, because the floor is a property of the text, not of the attempt.
     */
    private fun backfillSimhashes(): Int {
        val rows = jdbc.query(
            """
            SELECT c.article_id, c.text_plain
              FROM article_content c
              JOIN article a ON a.id = c.article_id
             WHERE a.simhash IS NULL
               AND length(c.text_plain) >= :minChars
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("limit", BACKFILL_BATCH)
                .addValue("minChars", ContentNormalizer.MIN_HASHABLE_CHARS),
        ) { rs, _ -> rs.getLong("article_id") to rs.getString("text_plain") }

        var written = 0
        for ((id, text) in rows) {
            val fingerprint = Simhash.of(text) ?: continue
            written += jdbc.update(
                "UPDATE article SET simhash = :h WHERE id = :id AND simhash IS NULL",
                MapSqlParameterSource().addValue("id", id).addValue("h", fingerprint),
            )
        }
        return written
    }

    /**
     * Dedup layer 3 — near-duplicates, via simhash Hamming distance.
     *
     * `bit_count((a # b)::bit(64))` is the distance: XOR, then count set bits. Postgres
     * 16 has `bit_count` natively and the `::bit(64)` cast is sign-safe, so a negative
     * bigint (fingerprints use the full 64-bit range) compares correctly — checked, not
     * assumed.
     *
     * THE 48-HOUR WINDOW IS DOING TWO JOBS. It reflects reality — a syndicated story and
     * its copies appear within a day or two, not months apart — and it bounds what would
     * otherwise be a self-join over the whole table. There is no index that can answer
     * "Hamming distance <= 3", because btree orders by magnitude and two fingerprints one
     * bit apart can sit at opposite ends of that order; the distance test can only ever
     * be a filter, so the window is what decides how many rows it runs against.
     *
     * That only holds with `article_simhash_window_idx` (V7). Without it the planner
     * materialises the whole fingerprinted set and applies the window as a JOIN FILTER —
     * measured at 401,956 rows removed from a 634x634 product. With it the window becomes
     * an Index Cond and each article meets 121 neighbours instead of 634. Same wall time
     * at this size, different complexity class: O(n^2) becomes O(n x window).
     *
     * At 500k rows even that is not enough and the next step is Manku-style banding:
     * split the 64 bits into 4 bands of 16, index each, and require an exact match on
     * one — which pigeonhole guarantees for any pair within distance 3, since 4 bits
     * cannot spread across 4 bands without leaving one untouched. Not built, because at
     * this corpus size it would be structure with no measurement behind it.
     *
     * `(w.published_at, w.id) < (l.published_at, l.id)` is the row-value form, the same
     * comparison the feed's keyset pagination uses. It makes "earlier" a single total
     * order and guarantees the pair is only ever considered in one direction, so the two
     * halves of a duplicate can never demote each other.
     */
    private fun demoteNearDuplicates(): Int = jdbc.update(
        """
        WITH pairs AS (
            SELECT l.id AS loser_id,
                   w.id AS winner_id,
                   row_number() OVER (PARTITION BY l.id ORDER BY w.published_at, w.id) AS rn
              FROM article l
              JOIN article w
                ON w.id <> l.id
               AND w.simhash IS NOT NULL
               AND w.status <> 'duplicate'
               AND (w.published_at, w.id) < (l.published_at, l.id)
               AND w.published_at > l.published_at - make_interval(hours => :windowHours)
               AND bit_count((l.simhash # w.simhash)::bit(64)) <= :maxDistance
             WHERE l.simhash IS NOT NULL
               AND l.status <> 'duplicate'
        )
        UPDATE article a
           SET status = 'duplicate',
               duplicate_of_id = p.winner_id
          FROM pairs p
         WHERE a.id = p.loser_id
           AND p.rn = 1
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("windowHours", WINDOW_HOURS)
            .addValue("maxDistance", MAX_DISTANCE),
    )

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

    companion object {
        /**
         * Hamming distance at or below which two bodies are the same article.
         *
         * 3 is the value from Manku et al. (WWW 2007) for 64-bit fingerprints, and it
         * was CHECKED against this corpus rather than adopted on authority: over 528
         * fingerprinted documents and 139,128 pairs, the distance histogram is
         *
         *     distance  0 : 4 pairs   (all four already known exact duplicates)
         *     distance  1-14 : ZERO pairs
         *     distance 15 : 5, 16 : 7, 17 : 8, 18 : 39, 19 : 68, 20 : 164 …
         *
         * so there is an empty band twelve bits wide between "duplicate" and "nearest
         * unrelated pair". Anything from 3 to 14 behaves identically on this data; 3 is
         * chosen because it is the conservative end of that range and the published
         * default, which means the margin only has to hold as the corpus grows, not
         * right now.
         *
         * THE HONEST CONSEQUENCE: at this threshold the layer currently demotes NOTHING
         * that `content_hash` had not already caught. It is not dead code — it is the
         * layer that catches syndication, and this corpus has none yet, because every
         * source publishes its own writing. Recorded as a zero rather than dressed up,
         * exactly as content_hash was recorded as zero before extraction gave it bodies.
         */
        const val MAX_DISTANCE = 3

        /**
         * Only compare articles published within this many hours of each other.
         *
         * Near-duplicates in news are temporally clustered; two articles a month apart
         * that happen to sit 3 bits apart are far likelier to be a coincidence than a
         * syndication. It also bounds the self-join, which nothing else can — Hamming
         * distance is not indexable by btree.
         */
        const val WINDOW_HOURS = 48

        /** Cap per sweep so backfill cannot hold a long transaction over the table. */
        const val BACKFILL_BATCH = 200
    }
}
