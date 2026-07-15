-- ============================================================================
-- V2__fulltext_search.sql
-- Postgres-native full-text search. No Elasticsearch yet -- and possibly ever.
--
-- Why FTS before ES: Postgres FTS comfortably handles single-digit millions of
-- documents on one box. Adding ES on day one means running a second stateful
-- system, owning a sync/reindex story, and answering "why?" in an interview
-- with "it's on job posts". Build FTS, measure it, and add ES in Phase 4 with
-- a real number to justify it. That sequence is the answer they want.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ---------------------------------------------------------------------------
-- unaccent() is STABLE, not IMMUTABLE -- it resolves its dictionary through
-- search_path, so Postgres refuses to let you build an index on it. Pinning
-- the dictionary with the regdictionary overload makes it genuinely
-- deterministic, so wrapping it as IMMUTABLE is honest rather than a lie to
-- the planner.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION f_unaccent(TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE PARALLEL SAFE STRICT
AS $$
    SELECT unaccent('unaccent'::regdictionary, $1)
$$;


-- ---------------------------------------------------------------------------
-- Vietnamese and diacritics -- the interesting problem in this file.
--
-- Postgres ships no 'vietnamese' text search config, so we use 'simple':
-- lowercase + tokenise, no stemming. That's actually correct for Vietnamese,
-- which is analytic and doesn't inflect. (It also means no stopword removal;
-- a custom config with a vi stopword file is a later refinement.)
--
-- The real issue: Vietnamese users type without diacritics constantly. Someone
-- searching "ma nguon mo" expects to find "mã nguồn mở". But stripping
-- diacritics is lossy in a way it isn't for French or Spanish -- ma/mà/má/mã/mả
-- are five different words, so unaccenting everything tanks precision.
--
-- The compromise here: index BOTH forms into the same tsvector. An accented
-- query matches only the accented lexemes (precise). An unaccented query falls
-- through to the unaccented lexemes (recall). Users who type carefully get
-- exact results; users who don't get fuzzy ones. Costs roughly 2x index size,
-- which is a fine trade for a text column.
--
-- Weights: A = title, B = summary, C = body. ts_rank_cd reads them.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION build_article_tsv(
    p_title   TEXT,
    p_summary TEXT,
    p_body    TEXT
) RETURNS tsvector
LANGUAGE sql
IMMUTABLE PARALLEL SAFE
AS $$
    SELECT
        setweight(to_tsvector('simple', coalesce(p_title,   '')), 'A') ||
        setweight(to_tsvector('simple', f_unaccent(coalesce(p_title,   ''))), 'A') ||
        setweight(to_tsvector('simple', coalesce(p_summary, '')), 'B') ||
        setweight(to_tsvector('simple', f_unaccent(coalesce(p_summary, ''))), 'B') ||
        -- body is truncated: ts_vector has a 1MB limit and the first ~200KB of
        -- an article carries essentially all of its retrieval signal anyway.
        setweight(to_tsvector('simple', left(coalesce(p_body, ''), 200000)), 'C') ||
        setweight(to_tsvector('simple', f_unaccent(left(coalesce(p_body, ''), 200000))), 'C')
$$;


ALTER TABLE article ADD COLUMN search_tsv tsvector;


-- ---------------------------------------------------------------------------
-- Why triggers and not a GENERATED column: the vector spans two tables
-- (title/summary live on article, body lives on article_content). A generated
-- column can only see its own row. So: two triggers, one per write path.
-- ---------------------------------------------------------------------------

-- Path 1: article row written. Body may not exist yet (it usually doesn't --
-- discovery precedes extraction), so the lookup just returns NULL.
CREATE OR REPLACE FUNCTION article_tsv_trigger()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_tsv := build_article_tsv(
        NEW.title,
        NEW.summary,
        (SELECT text_plain FROM article_content WHERE article_id = NEW.id)
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER article_tsv_update
    BEFORE INSERT OR UPDATE OF title, summary ON article
    FOR EACH ROW EXECUTE FUNCTION article_tsv_trigger();


-- Path 2: body extracted/re-extracted. Reach back and refresh the parent.
-- AFTER, not BEFORE: the row must be committed-visible to the subquery.
CREATE OR REPLACE FUNCTION article_content_tsv_trigger()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE article a
       SET search_tsv = build_article_tsv(a.title, a.summary, NEW.text_plain)
     WHERE a.id = NEW.article_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER article_content_tsv_update
    AFTER INSERT OR UPDATE OF text_plain ON article_content
    FOR EACH ROW EXECUTE FUNCTION article_content_tsv_trigger();


-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

-- GIN, not GiST: GIN is slower to build and update but much faster to query,
-- and a news archive is written once and read constantly. fastupdate defers
-- index maintenance into a pending list, which suits bursty crawl inserts.
CREATE INDEX article_search_idx ON article USING GIN (search_tsv)
    WITH (fastupdate = on);

-- Trigram index on the unaccented title: powers admin typo-tolerant lookup and
-- the near-duplicate check ("did this headline already arrive from another
-- source 40 seconds ago?"). Different tool from FTS -- FTS matches words,
-- trigrams match string similarity.
CREATE INDEX article_title_trgm_idx ON article USING GIN (f_unaccent(title) gin_trgm_ops);


-- ---------------------------------------------------------------------------
-- Backfill existing rows (no-op on a fresh DB, matters on an established one).
-- On a large table, do this in batches outside the migration -- a single UPDATE
-- over 5M rows takes an ACCESS EXCLUSIVE-adjacent amount of pain and bloats the
-- table. Flyway migrations should be fast; long backfills belong in a job.
-- ---------------------------------------------------------------------------
UPDATE article a
   SET search_tsv = build_article_tsv(a.title, a.summary, ac.text_plain)
  FROM article_content ac
 WHERE ac.article_id = a.id;

UPDATE article a
   SET search_tsv = build_article_tsv(a.title, a.summary, NULL)
 WHERE a.search_tsv IS NULL;


-- ============================================================================
-- Reference queries (keep these in the repo -- they're what you demo)
-- ============================================================================
--
-- Search, ranked, with highlighting:
--
--   WITH q AS (SELECT websearch_to_tsquery('simple', f_unaccent(:term)) AS tsq)
--   SELECT a.id,
--          a.title,
--          ts_rank_cd(a.search_tsv, q.tsq, 32) AS rank,
--          ts_headline('simple', a.summary, q.tsq,
--                      'StartSel=<mark>, StopSel=</mark>, MaxFragments=2') AS snippet
--     FROM article a, q
--    WHERE a.search_tsv @@ q.tsq
--      AND a.status = 'published'
--    ORDER BY rank DESC, a.published_at DESC
--    LIMIT 30;
--
-- websearch_to_tsquery (not plainto_) gives users quoted phrases, OR, and -term
-- for free, and never throws on malformed input -- which plainto_tsquery
-- happily does the first time someone types a stray ':'.
--
-- ts_rank_cd's 4th arg 32 = rank/(rank+1), normalising into 0..1 so scores are
-- comparable across queries.
--
-- Note this query CANNOT use article_feed_idx for the ORDER BY -- ranking is
-- computed per-row, so it's GIN scan -> sort. That's fine at 100k docs and
-- starts hurting somewhere past a few million. When it does, THAT is your
-- documented reason to introduce Elasticsearch.
--
-- ---------------------------------------------------------------------------
-- Near-dup headline check (trigram):
--
--   SELECT id, title, similarity(f_unaccent(title), f_unaccent(:incoming))
--     FROM article
--    WHERE f_unaccent(title) % f_unaccent(:incoming)     -- % uses the GIN index
--      AND published_at > now() - interval '48 hours'
--    ORDER BY 3 DESC
--    LIMIT 5;
--
-- Tune the threshold with set_limit() / pg_trgm.similarity_threshold.
