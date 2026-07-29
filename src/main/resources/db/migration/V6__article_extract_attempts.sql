-- V6 — track per-article extraction attempts.
--
-- WHY A COLUMN IS NEEDED AT ALL
-- Extraction picks work with "articles that have no `article_content` row". That
-- predicate has no memory, so an article that CANNOT be extracted is selected again on
-- every cycle, forever. This is not hypothetical: arstechnica.com answers our bot with
-- HTTP 202 and a challenge page, www.infoq.com with 405, and paywalled or JS-only pages
-- will never yield a body. Left alone, ~1 request per unextractable article per cycle
-- goes out for the lifetime of the project — wasted work, and repeatedly knocking on the
-- door of a site that has already said no.
--
-- `crawl_log` cannot hold this: it is keyed on (job_id, source_id) with no article_id,
-- because it records HTTP attempts against a SOURCE. Extraction attempts are per
-- ARTICLE. Widening crawl_log would blur what that table means.
--
-- WHY A COUNTER AND NOT A BOOLEAN
-- A boolean `extract_failed` cannot distinguish "the network blipped once" from "this
-- page is never going to work". A counter lets one transient failure be retried and a
-- persistently broken page be abandoned, using the same mechanism the job queue already
-- uses for `attempt` / `max_attempts`.
--
-- SMALLINT, not INTEGER: the value never exceeds a single digit, and `article` is the
-- table the feed query scans constantly. Narrow rows mean more tuples per 8KB page,
-- which is the same reasoning that put the body text in `article_content`.

ALTER TABLE article
    ADD COLUMN extract_attempts smallint NOT NULL DEFAULT 0;

ALTER TABLE article
    ADD CONSTRAINT article_extract_attempts_ck CHECK (extract_attempts >= 0);

-- Supports the extraction worker's pick-next query:
--   WHERE source_id = ? AND status <> 'duplicate' AND extract_attempts < 3
--   ORDER BY published_at DESC
--
-- Partial on the attempt cap so the index holds only rows still worth trying — it
-- shrinks as the backlog is worked off, rather than growing with the table. The cap is
-- inlined as a literal because a partial index predicate must be immutable; the
-- application's max-attempts constant and this number have to be changed together, which
-- is called out in ArticleExtractionWorker.
CREATE INDEX article_extract_pending_idx
    ON article (source_id, published_at DESC)
    WHERE extract_attempts < 3 AND status <> 'duplicate';
