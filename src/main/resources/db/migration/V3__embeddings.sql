-- ============================================================================
-- V3__embeddings.sql
-- pgvector: powers "related articles" and semantic near-dup detection.
--
-- Two features, one index. That's the argument for doing this in Postgres
-- rather than bolting on a vector DB: you get transactional consistency with
-- the article rows and you can filter by tag/date/source in the SAME query,
-- which is exactly where standalone vector stores get awkward.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;


-- ---------------------------------------------------------------------------
-- Separate table, not a column on article. Three reasons:
--   1. Embeddings are optional and arrive late (async enrichment). A nullable
--      1.5KB column on the hot feed table is dead weight in every page read.
--   2. Model changes. When you swap embedding models the dimension changes and
--      every vector is invalid. Truncating a side table is a Tuesday;
--      rewriting a column on the main table is an outage.
--   3. `model` is part of the key -- you can run two models side by side during
--      a migration and cut over per-source.
-- ---------------------------------------------------------------------------
CREATE TABLE article_embedding (
    article_id  BIGINT      NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    model       TEXT        NOT NULL,
    -- 768 = multilingual-e5-base / gte-multilingual-base. Both handle
    -- Vietnamese properly, which OpenAI's older ada-002 does not do well.
    -- Dimension is fixed at DDL time -- changing it is a new migration.
    embedding   VECTOR(768) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (article_id, model)
);


-- ---------------------------------------------------------------------------
-- HNSW over IVFFlat:
--   * IVFFlat must be built AFTER the table has representative data (it k-means
--     clusters at build time) and degrades as the distribution drifts -- awful
--     for a table that grows continuously from empty.
--   * HNSW builds incrementally, has better recall/latency, and needs no
--     retraining. Costs more memory and a slower build. For a continuously
--     ingesting corpus it's the obvious pick.
--
-- vector_cosine_ops: normalise embeddings at write time and cosine is the right
-- metric for semantic similarity. (If your model already L2-normalises, inner
-- product is equivalent and marginally faster -- vector_ip_ops.)
--
-- m=16, ef_construction=64 are the defaults; raise ef_construction to ~200 for
-- better recall if build time is acceptable. Query-time recall is tuned with
-- SET hnsw.ef_search = 100 (default 40).
-- ---------------------------------------------------------------------------
CREATE INDEX article_embedding_hnsw_idx
    ON article_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);


-- ============================================================================
-- Reference queries
-- ============================================================================
--
-- Related articles:
--
--   SELECT a.id, a.title, a.published_at,
--          1 - (e.embedding <=> :query_vec) AS similarity
--     FROM article_embedding e
--     JOIN article a ON a.id = e.article_id
--    WHERE e.model = 'multilingual-e5-base'
--      AND e.article_id <> :source_article_id
--      AND a.status = 'published'
--    ORDER BY e.embedding <=> :query_vec       -- <=> = cosine distance
--    LIMIT 8;
--
-- The ORDER BY <=> ... LIMIT shape is mandatory. HNSW is only used for an
-- ordered nearest-neighbour scan with a limit; a WHERE on distance
-- (`WHERE embedding <=> :v < 0.3`) falls back to a full scan. Filter after,
-- in the application, or wrap it.
--
-- ---------------------------------------------------------------------------
-- The filtering trap, worth knowing before an interviewer asks:
--
-- Adding `AND a.source_id = 5` to the above is "post-filtering" -- HNSW walks
-- the graph for the global nearest 8, THEN the join throws most away, and you
-- can get back 2 rows instead of 8. This is the classic ANN gotcha.
--
-- Fixes, in order of preference:
--   1. Over-fetch (LIMIT 100) then filter and re-limit in the app. Crude, works.
--   2. Denormalise the filter column onto article_embedding and use a partial
--      HNSW index per hot filter value.
--   3. SET hnsw.iterative_scan = relaxed_order (pgvector 0.8+) -- lets the scan
--      keep walking until the filter is satisfied.
--
-- ---------------------------------------------------------------------------
-- Semantic dedup -- the syndication case simhash misses. VnExpress and a
-- re-blogger publish the same story with different wording; byte hashes differ,
-- simhash may or may not fire, embeddings catch it:
--
--   SELECT e2.article_id, 1 - (e1.embedding <=> e2.embedding) AS sim
--     FROM article_embedding e1
--     JOIN article_embedding e2
--       ON e2.model = e1.model AND e2.article_id <> e1.article_id
--     JOIN article a2 ON a2.id = e2.article_id
--    WHERE e1.article_id = :new_id
--      AND a2.published_at BETWEEN :pub - interval '3 days' AND :pub + interval '3 days'
--    ORDER BY e1.embedding <=> e2.embedding
--    LIMIT 5;
--
-- Threshold around 0.92-0.95 cosine for "same story"; set article.status =
-- 'duplicate' and article.duplicate_of_id = the earliest published one.
-- Calibrate on real data, not on a number from a blog post.
