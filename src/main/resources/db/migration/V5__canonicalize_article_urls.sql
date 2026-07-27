-- V5 — retrofit URL canonicalization onto rows written before it existed.
--
-- WHY THIS MIGRATION IS MANDATORY, NOT TIDY-UP
-- `article.url_hash` is GENERATED ALWAYS AS (digest(canonical_url,'sha256')) STORED and
-- carries the UNIQUE constraint, so canonical_url IS the dedup identity. The moment the
-- application starts canonicalizing, an InfoQ link arrives as
--   https://www.infoq.com/news/2026/07/rspack-2-release/
-- while the stored row says
--   https://www.infoq.com/news/2026/07/rspack-2-release/?utm_campaign=...&utm_term=global
-- Different string, different hash, no conflict, INSERT. Every already-known article
-- from a source that emits tracking params silently doubles on the next crawl. Shipping
-- the canonicalizer WITHOUT this migration is worse than shipping neither.
--
-- THE HAZARD
-- Canonicalization is lossy on purpose, so rows that were unique can stop being unique.
-- Rewriting canonical_url in place recomputes url_hash and trips article_url_hash_uk
-- mid-statement. This is real in the current data, not theoretical: martinfowler.com
-- publishes an article incrementally and the feed points at the new section each time,
-- so THREE rows (ids 5520, 5523, 5524) collapse onto one page once the fragment is
-- dropped. RFC 3986 is unambiguous that they are one resource — the fragment is never
-- sent to the server — so merging is correct; it just has to be done deliberately.
--
-- THE RESOLUTION
-- Earliest published_at wins (id as tie-break), losers become status='duplicate' with
-- duplicate_of_id pointing at the winner. Losers KEEP their original canonical_url —
-- that is what makes this safe, because an untouched URL cannot collide with anything.
-- Nothing is deleted: article_content, article_tag, article_embedding and bookmark all
-- FK to article, and a bookmark quietly vanishing is a worse bug than a duplicate row.
--
-- This function is a FROZEN HISTORICAL FIX, not a second home for the rules. The living
-- implementation is UrlCanonicalizer.kt; if those rules change later, they change for
-- new rows and this migration keeps meaning exactly what it meant when it ran. That is
-- also why it is dropped at the end — it must not become an API anyone calls.

CREATE FUNCTION v5_canonicalize(u text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    m        text[];
    scheme   text;
    auth     text;
    path     text;
    query    text;
BEGIN
    IF u IS NULL THEN RETURN NULL; END IF;

    -- Fragment first: everything after '#' is client-side and identifies no resource.
    m := regexp_match(split_part(btrim(u), '#', 1),
                      '^([A-Za-z][A-Za-z0-9+.-]*)://([^/?]*)([^?]*)\??(.*)$');
    IF m IS NULL THEN
        RETURN btrim(u);          -- unparseable: leave it exactly as found
    END IF;

    scheme := lower(m[1]);
    auth   := lower(m[2]);
    path   := m[3];
    query  := m[4];

    -- RFC 3986 6.2.3: an explicitly written default port is equivalent to none.
    IF scheme = 'https' THEN auth := regexp_replace(auth, ':443$', '');
    ELSIF scheme = 'http' THEN auth := regexp_replace(auth, ':80$', '');
    END IF;

    -- Trailing slash, except on a bare-root path where '/' is all there is.
    IF length(path) > 1 THEN path := regexp_replace(path, '/+$', ''); END IF;

    -- Drop referrer-identifying params, then sort what survives so that ?a=1&b=2 and
    -- ?b=2&a=1 hash identically. WITH ORDINALITY as the tie-break keeps repeated keys
    -- (?tag=a&tag=b) in their original relative order — the one case where query order
    -- carries meaning. Kotlin's sortedBy is stable and this mirrors it.
    SELECT string_agg(p, '&' ORDER BY split_part(p, '=', 1), ord)
      INTO query
      FROM unnest(string_to_array(query, '&')) WITH ORDINALITY AS t(p, ord)
     WHERE p <> ''
       AND lower(split_part(p, '=', 1)) NOT LIKE 'utm\_%'
       AND lower(split_part(p, '=', 1)) NOT IN (
             'fbclid','gclid','dclid','msclkid','yclid',
             'mc_cid','mc_eid','igshid','ref_src','ref_url',
             '_hsenc','_hsmi','spm');

    RETURN scheme || '://' || auth || path
           || CASE WHEN query IS NULL OR query = '' THEN '' ELSE '?' || query END;
END;
$$;

-- Materialised once. Calling v5_canonicalize inline in both the loser and winner
-- statements would evaluate it twice per row and, worse, let the two statements
-- disagree if the function were ever non-deterministic.
-- Explicitly dropped at the end rather than ON COMMIT DROP: that variant is correct
-- only if the whole migration runs inside one transaction, which is Flyway's default
-- but is a default, not a guarantee. This form behaves the same either way.
CREATE TEMP TABLE v5_plan AS
SELECT id,
       canonical_url                                   AS old_url,
       v5_canonicalize(canonical_url)                  AS new_url,
       first_value(id) OVER w                          AS winner_id,
       count(*)        OVER (PARTITION BY v5_canonicalize(canonical_url)) AS group_size
  FROM article
 WHERE status <> 'duplicate'   -- already-resolved duplicates are not re-litigated
WINDOW w AS (PARTITION BY v5_canonicalize(canonical_url)
             ORDER BY published_at, id);

-- Losers FIRST. Their canonical_url is deliberately left alone, so after this step the
-- winners' new hashes are guaranteed free.
UPDATE article a
   SET status          = 'duplicate',
       duplicate_of_id = p.winner_id
  FROM v5_plan p
 WHERE a.id = p.id
   AND p.group_size > 1
   AND p.id <> p.winner_id;

-- Winners and uncontested rows. The url_hash recompute happens here, on rows now proven
-- to be alone in their group.
UPDATE article a
   SET canonical_url = p.new_url
  FROM v5_plan p
 WHERE a.id = p.id
   AND p.id = p.winner_id
   AND a.canonical_url <> p.new_url;

DROP TABLE v5_plan;
DROP FUNCTION v5_canonicalize(text);

-- Unrelated to the backfill, and overdue: duplicate_of_id is a self-FK with
-- ON DELETE SET NULL and had no index, so every article delete forced a sequential scan
-- of article looking for referrers. Harmless at 582 rows, not at 500k. Partial because
-- the column is NULL for everything that is not a duplicate.
CREATE INDEX article_duplicate_of_idx ON article (duplicate_of_id)
    WHERE duplicate_of_id IS NOT NULL;
