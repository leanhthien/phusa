-- ============================================================================
-- seed_and_explain.sql
-- NOT a Flyway migration. Run manually against a scratch database.
--
-- This produces the single most persuasive artifact in your README: a
-- before/after EXPLAIN ANALYZE showing you understand why an index works,
-- not just that one should exist. Screenshot both plans.
--
--   docker compose exec -T postgres psql -U app -d newsfeed < seed_and_explain.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Seed. 500k articles across 50 sources, published over the last 2 years.
--    generate_series + random() is enough; you're benchmarking the planner,
--    not writing a data generator.
-- ---------------------------------------------------------------------------
INSERT INTO source (slug, name, homepage_url, feed_url, kind)
SELECT 'src-' || i,
       'Source ' || i,
       'https://example-' || i || '.com',
       'https://example-' || i || '.com/feed.xml',
       'rss'
  FROM generate_series(1, 50) i
    ON CONFLICT (slug) DO NOTHING;

INSERT INTO article (source_id, canonical_url, title, summary, status, published_at, word_count)
SELECT
    (SELECT id FROM source ORDER BY id LIMIT 1 OFFSET (i % 50)),
    'https://example.com/a/' || i || '-' || md5(i::text),
    'Article ' || i || ' about ' || (ARRAY['Kotlin','Spring Boot','Postgres','Kafka','Flutter'])[1 + (i % 5)],
    'Summary text for article ' || i,
    -- realistic status mix: most published, a tail of failures/dupes. This
    -- matters -- a partial index's value depends on the skew.
    CASE WHEN i % 100 < 92 THEN 'published'
         WHEN i % 100 < 96 THEN 'duplicate'
         ELSE 'failed' END,
    now() - (random() * interval '730 days'),
    200 + (i % 1800)
  FROM generate_series(1, 500000) i;

-- Without this the planner is working from stale/absent statistics and every
-- number below is noise. ANALYZE first, always.
ANALYZE article;


-- ---------------------------------------------------------------------------
-- 2. BEFORE: drop the feed index, run the real query.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS article_feed_idx;
DROP INDEX IF EXISTS article_source_feed_idx;
ANALYZE article;

\echo '=============== BEFORE: no supporting index ==============='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, title, published_at
  FROM article
 WHERE status = 'published'
   AND (published_at, id) < (now() - interval '200 days', 999999999)
 ORDER BY published_at DESC, id DESC
 LIMIT 30;

-- Expect: Seq Scan on article -> Sort (external merge, spilling to disk) ->
-- Limit. Reads the entire heap and sorts ~460k rows to return 30. Tens/hundreds
-- of ms, and it scales linearly with the table forever.


-- ---------------------------------------------------------------------------
-- 3. AFTER: the index from V1.
-- ---------------------------------------------------------------------------
CREATE INDEX article_feed_idx
    ON article (published_at DESC, id DESC)
    WHERE status = 'published';
ANALYZE article;

\echo '=============== AFTER: partial composite index ==============='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, title, published_at
  FROM article
 WHERE status = 'published'
   AND (published_at, id) < (now() - interval '200 days', 999999999)
 ORDER BY published_at DESC, id DESC
 LIMIT 30;

-- Expect: Index Scan Backward using article_feed_idx -> Limit. No Sort node at
-- all -- that's the tell. Sub-millisecond, and it stays sub-millisecond at 5M
-- rows because the work is bounded by LIMIT, not by table size.
--
-- Three things to say about this in an interview:
--   * Index column order matches ORDER BY exactly, so the index IS the sort.
--   * The row-value comparison (published_at, id) < (?, ?) is a single index
--     range condition. Writing it as
--       published_at < ? OR (published_at = ? AND id < ?)
--     is logically identical and the planner handles it far worse.
--   * WHERE status = 'published' is in the index predicate, so the 8% of dead
--     rows aren't in the index at all -- smaller index, better cache hit rate.


-- ---------------------------------------------------------------------------
-- 4. The OFFSET comparison. Run this; put the numbers in the README.
-- ---------------------------------------------------------------------------
\echo '=============== OFFSET pagination at page 1 ==============='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id, title FROM article WHERE status = 'published'
 ORDER BY published_at DESC, id DESC LIMIT 30 OFFSET 0;

\echo '=============== OFFSET pagination at page 10,000 ==============='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id, title FROM article WHERE status = 'published'
 ORDER BY published_at DESC, id DESC LIMIT 30 OFFSET 300000;

-- OFFSET 300000 still *fetches and discards* 300,000 rows. Page 1 is instant,
-- page 10,000 is not, and the degradation is linear. Keyset pagination is O(1)
-- per page regardless of depth. This is why the API exposes a cursor and not a
-- page number -- and it's a nice bonus that it's also correct under concurrent
-- inserts, which OFFSET is not (new articles shift rows across page boundaries,
-- so users skip or re-see items).


-- ---------------------------------------------------------------------------
-- 5. N+1 demonstration. Do this from the Kotlin side, but here's the shape.
-- ---------------------------------------------------------------------------
\echo '=============== tag fetch: the N+1 fix ==============='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT a.id, a.title, t.slug
  FROM article a
  LEFT JOIN article_tag at2 ON at2.article_id = a.id
  LEFT JOIN tag t ON t.id = at2.tag_id
 WHERE a.id IN (SELECT id FROM article WHERE status = 'published'
                 ORDER BY published_at DESC LIMIT 30);

-- Note the shape: page the ROOT entity first (subquery with LIMIT), then fetch
-- its collection. This is what Hibernate does with @BatchSize or
-- @Fetch(SUBSELECT) and it's why `JOIN FETCH` + Pageable is a trap -- the join
-- multiplies rows, Hibernate can't apply LIMIT in SQL, so it pulls the whole
-- result set and paginates in application memory. It logs
-- HHH000104 and everyone ignores it until production.
