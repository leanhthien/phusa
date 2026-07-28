# Phù Sa — Roadmap

Task tracker. Survives across sessions. `CLAUDE.md` holds the *decisions* (stack, locked
choices, gotchas); this file holds the *work*.

## How to use this file

- **Tick boxes as you go, in the same commit as the work.** A box ticked without a commit
  is a lie to your future self.
- **Don't skip ahead.** Phases are ordered by dependency and by risk, not by interest.
  Phase 4 is the fun part; it is also worthless if Phase 0 never shipped.
- Each phase has an **exit criterion**. It's binary. Meet it before moving on.
- Append to the Session Log at the bottom when you stop.

**Status: Phase 0 — done except the deploy.** Exit criterion met locally: a scheduled
crawl feeds deduped rows through the keyset API into the Next feed, all in containers
behind Caddy. Prod config (compose overlay + HTTPS) has landed; what's missing is a
VPS with `ambert.io.vn` pointed at it — that box is owner-blocked, not code-blocked,
so Phase 1 has started in parallel: per-source JSONB config is done.

---

## Phase 0 — Vertical slice ⏱ ~2 weeks

One RSS feed, end to end, deployed. Nothing clever. The point is to prove the pipe exists
before widening it.

### Infra
- [x] `docker-compose.yml`: `pgvector/pgvector:pg16` + Redis 7
- [x] Confirm extensions available: `vector`, `unaccent`, `pg_trgm`, `pgcrypto`, `citext`
      — all 5 present and created on the pg16 image
- [x] Spring Boot 3.x skeleton, Gradle Kotlin DSL, Java 21
      — Boot 3.5.3 (pinned; Initializr only emits 4.x now), Kotlin 2.1.21, Gradle 8.14.3
- [x] Flyway wired, `spring.jpa.hibernate.ddl-auto: validate`
      — PROVEN: `bootRun` applies V1–V3 (flyway_schema_history at v3), Hibernate
      validate passes, actuator health UP. Boots in ~1.5s.
- [x] **Run V1–V3. They will fail — they were never tested against a real Postgres.**
      Fix, and note what broke in the Session Log
      — All three apply CLEAN against pg16. Nothing broke. Smoke-tested: generated
      `url_hash` (32 bytes), HNSW index, and the dual-form Vietnamese tsvector
      (accented insert, unaccented query → hit). DB reset to empty for Flyway to own.
- [~] Testcontainers spins up Postgres in tests, migrations apply green
      — Test WRITTEN (`TestcontainersConfiguration` pins pgvector/pgvector:pg16 so
      `CREATE EXTENSION vector` works). BLOCKED locally: docker-java's zerodep
      transport gets HTTP 400 from Docker Desktop 4.82's socket proxy (curl on the
      same socket returns 200) — a known macOS Docker Desktop quirk, not a code
      issue. DECISION (owner): defer to Linux CI (Phase 6), where Testcontainers
      works out of the box. Do not contort the build for one machine's Desktop proxy.
      Substance meanwhile proven via bootRun above.

### Backend
- [x] `Source`, `Article`, `ArticleContent` entities
      — mapped under `vn.phusa.domain`; `ddl-auto: validate` passes against the real
      schema. Gotcha found & fixed: CHAR(2) `language` needed `@JdbcTypeCode(CHAR)`
      (Hibernate defaults String→varchar). Repos: `SourceRepository`,`ArticleRepository`.
  - [x] `url_hash` → `@Generated(event = [EventType.INSERT])`, do not let Hibernate write it
        — VERIFIED: round-trips at 32 bytes, DB generates from canonical_url, read back
  - [x] `search_tsv` → left unmapped (validate only checks mapped columns exist)
  - [x] `ArticleContent` lazy, never fetched by the feed query
        — shared-PK 1:1 via `@MapsId`; `@OneToOne(fetch=LAZY)`; cascade persists content
- [x] Rome parses one RSS feed → `Article` rows
      — `FeedFetcher` (HTTP + User-Agent) → `RssIngestService`. Fetch is kept OUT of the
      DB tx and split across beans so `@Transactional` isn't self-invoked (CLAUDE gotcha).
- [x] Upsert is idempotent: `INSERT ... ON CONFLICT (url_hash) DO UPDATE`.
      **Test it by running the ingest twice and asserting the row count is unchanged.**
      — `ON CONFLICT ON CONSTRAINT article_url_hash_uk` (keys off the generated url_hash)
      with an `IS DISTINCT FROM` guard so unchanged rows write nothing. VERIFIED against
      compose pg16: pass1 3 written, pass2 0 written, count stays 3. IT covers it too.
- [x] `GET /api/articles` — keyset pagination from day one, opaque cursor, no page numbers
      — Row-value `(published_at,id) < (?,?)` via NamedParameterJdbcTemplate; opaque
      base64 cursor (`<instant>|<id>`); no `?page=`. VERIFIED on 5k seeded rows:
      first page + deep cursor page both `Index Scan using article_feed_idx`, NO Sort
      node, 5 buffer hits, ~0.05–0.25ms. Contrast OFFSET 2500: reads 2530 rows, 99
      buffers — keyset stays flat with depth. Endpoint paged end-to-end, no overlap,
      bad cursor → 400.
- [~] One integration test hitting real Postgres
      — `RssIngestServiceIT` (idempotency + update-in-place) and `ArticleFeedServiceIT`
      (keyset no-overlap ordering + 400 on bad cursor). Testcontainers → green on CI,
      blocked on this Mac (see Testcontainers note above).

### Frontend
- [x] Next.js App Router + Tailwind + shadcn/ui
      — Next 16.2.10 + React 19 + Tailwind 4 in `web/` (monorepo subdir). shadcn/ui
      DEFERRED to Phase 3 (three-pane/reader); a single list doesn't need it yet.
      `/api` proxied to the backend via next.config rewrite (no CORS, matches prod).
- [x] One page: article list, infinite scroll on the cursor API
      — `FeedList` client component: IntersectionObserver auto-load + "Load more"
      fallback. Verified end-to-end against the backend: 45 seeded articles paged
      3× via cursor, all unique, ordered, ends cleanly. Prod `next build` green.
      Backend: added `canonicalUrl` to the feed DTO so items link out.
- [x] Nothing else. No auth, no reader, no dark mode toggle (dark via prefers-color-scheme)

### Ship
- [x] Multi-stage Dockerfile per service
      — backend (Gradle→JRE21, non-root) + web (Next standalone, non-root); Caddy
      reverse proxy for a single origin (and HTTPS-ready for the VPS). Both images
      build; full stack runs via `docker compose up -d --build`.
- [ ] Deployed to a VPS, real domain, HTTPS  ← **owner step**: only the box is missing.
      Domain is `ambert.io.vn`. Config is committed and verified —
      `docker-compose.prod.yml` (secrets from `.env`, restart policies, mem limits,
      `:443`) and a Caddyfile whose site address comes from `$SITE_ADDRESS`, so
      setting it to the domain is what switches on automatic HTTPS. Deploy is
      `cp .env.prod.example .env` + fill it + `up -d --build` with both compose files.
      Point DNS and confirm `dig +short ambert.io.vn` returns the VPS IP BEFORE the
      first `up` — a failed challenge burns Let's Encrypt's failed-validation limit
      (5 per hostname per hour; recovers over lunch).
      The limit that actually hurts is the other one: **5 duplicate certs per week**,
      spent by issuance *succeeding*. Caddy keeps its certs in the `phusa-caddydata`
      volume, so a plain restart is free — but `down -v` wipes that volume alongside
      `phusa-pgdata`, and Caddy silently re-issues on the next boot. Four or five
      `down -v` debug cycles in one afternoon = locked out of issuance for
      `ambert.io.vn` for the rest of a rolling week, with a working stack serving
      nothing but TLS errors. Nothing warns you; each issuance logs as success.
      So: on the VPS use `down`, never `down -v`. And rehearse the plumbing against
      staging first (`acme_ca https://acme-staging-v02.api.letsencrypt.org/directory`
      in a global block — untrusted certs, no meaningful limits), then remove it.
      Note also that Caddy backs off on its own after a failure; `restart` throws that
      state away and resets the backoff, so the "just restart and see" instinct is
      exactly the wrong one here.
- [x] README stub: what it is, live link, how to run locally
      — README updated (Compose one-liner + IDE workflow); live link pending deploy.

- [x] **Scheduled ingest** (needed for the exit criterion, not originally a box):
      `@Scheduled` crawler + seeder. VERIFIED in-container: crawls dev.to → 12 real
      articles → feed, unattended, on one origin. jsoup cleans HTML summaries to text.

> **Exit: a stranger can open a URL and see articles that a cron job put there.**
> Until that's true, nothing below matters.
>
> **MET locally** (http://localhost via Compose): scheduled crawl → deduped rows →
> keyset API → Next feed, all in containers. Only the public VPS URL remains.

---

## Phase 1 — Ingestion ⏱ ~2 weeks

The part that actually differentiates this from a CRUD app.

### Sources
- [x] Per-source config in DB (`source.config` JSONB), not in application.yml
      — `phusa.ingest.default-source` is gone from application.yml (what's left there
      is deployment-only: enabled / interval). `IngestProperties` deleted with it.
      V4 adds `CHECK (jsonb_typeof(config) = 'object')` — schemaless isn't
      constraint-free; without it `'[]'`, `'"rss"'` and `'null'` are all valid JSONB
      and each one breaks the parse at crawl time in a log nobody reads. Typed
      `SourceConfig` + `SourceConfigCodec` on the app side, `ignoreUnknown=true` so a
      row written by a newer deploy doesn't take out an older instance. Live knobs:
      `userAgent`, `requestTimeoutSec`, `maxItems`. DIVISION OF LABOUR: uniform policy
      stays in real columns (enabled, crawl_interval_sec, rate_limit_per_min) because
      it's queried and constrained; JSONB is only for what varies by `kind`. Seeding
      is insert-if-absent from `seed/sources.json` and never updates — the scheduler
      and the Phase 3 admin UI write to these rows, so re-applying a file on boot
      would silently revert them.
      VERIFIED against compose pg16: V4 applies, validate passes; constraint rejects
      array/scalar/null and accepts an object; `{"maxItems":3}` in the DB → "3 entries
      considered (12 in feed)"; `{"maxItems":"lots"}` → WARN + defaults + crawl still
      succeeds. `SourceConfigCodecTest` 7/7 green — and it's a plain unit test, so it
      runs on the Mac where Testcontainers is blocked.
- [~] 10+ real sources. HackerNews, Dev.to, Reddit, VN tech blogs
      — 20 in `seed/sources.json`: 13 international (HN via hnrss, Dev.to, Lobsters,
      r/programming, GitHub, Stack Overflow, InfoQ, Martin Fowler, Spring, Kotlin,
      Android Developers, Ars Technica, The Verge) + 7 Vietnamese (Viblo, VnExpress
      Số hóa, GenK, Tinh tế, VietnamNet Công nghệ, TopDev, ZNews Công nghệ). The VN
      half is the part that makes this a *Vietnamese* aggregator rather than another
      HN mirror.
      EVERY URL PROBED before it was written down, with the project's real User-Agent
      and Accept headers: HTTP 200, root element checked, item count counted. Four
      candidates were rejected on evidence — Baeldung (403 to every UA, Cloudflare),
      Kipalog (404, site appears dead), and two wrong-path guesses. Corrections the
      probe caught: `viblo.asia/feed` is 404, the real path is `/rss`;
      `topdev.vn/blog/feed/` with a trailing slash hangs, without it is fine;
      `spring.io/blog.atom` serves RSS despite the extension.
      FINDING, against my own prediction: NOTHING needed a `userAgent` override. The
      one apparent 403-on-bot-UA (hnrss) was a transient timeout that a retry cleared.
      The knob stays because it costs nothing, but it has not yet earned its keep —
      worth remembering before adding knobs on a hunch.
      `SourceSeedFileTest` (11 tests) validates the file against the table's own
      constraints — unique slugs/feed urls, kind token, feed url present for rss/atom,
      interval floor, CHAR(2) language, config is an object, config survives the
      codec. Container-free, so it runs on this Mac. 18/18 green with the codec tests.
      NOW VERIFIED (2026-07-23, once Docker came back): 19 rows seeded (devto already
      existed), all 20 sources crawled green through the job queue on the first run —
      407 items found, 401 new, zero failures. Slowest was martinfowler (13.0s),
      fastest znews (0.23s). Article table 144 → 543.
- [ ] jsoup + readability-style extraction for feed-less sites
- [ ] Playwright-Java for JS-rendered sites — **only if a real source demands it**

### Job queue
- [x] `@Scheduled` scans `source` for `next_crawl_at <= now()`, enqueues `crawl_job`
      — `CrawlScheduler.enqueue()`, one `INSERT..SELECT`. Replaces the Phase-0
      `IngestScheduler`, which crawled every source every tick and ignored
      `crawl_interval_sec` entirely — at 20 sources that would have fetched
      martinfowler.com ~2,900 times a month for a blog that posts a few times.
      `ON CONFLICT (source_id, job_type) WHERE state IN ('pending','running')` — the
      WHERE is mandatory, not decoration: Postgres only infers a PARTIAL unique index
      if the clause repeats the index predicate verbatim, otherwise you get "no unique
      or exclusion constraint matching" at runtime. VERIFIED: 5 enqueue passes in a
      row insert 7, then 0, 0, 0, 0.
- [x] Worker claims with `FOR UPDATE SKIP LOCKED`, batch of 10
      — `CrawlJobDao.claimBatch`. PROVEN with two concurrent psql sessions: A claims 3
      and holds its transaction 6s; B runs the identical query and returns in **83ms**
      with disjoint ids. Same test with plain `FOR UPDATE`: B blocks **4106ms**. ~50x,
      and the numbers are the README artifact.
      NUANCE WORTH KEEPING: without SKIP LOCKED B still eventually got *different*
      rows — plain `FOR UPDATE` is CORRECT, just serialised. SKIP LOCKED buys
      throughput, not correctness. Claiming otherwise in an interview is a trap.
      TRANSACTION SHAPE is the load-bearing part: claim commits IMMEDIATELY, then the
      HTTP fetch runs with no transaction, then a short finish transaction. Wrapping
      the loop in `@Transactional` would pin a connection across a 20s fetch and hold
      the claim's row locks the whole time — at which point SKIP LOCKED protects
      nothing. What makes early commit safe is the LEASE (`locked_until`), which
      outlives the row lock and is visible to other processes.
- [x] Retry with exponential backoff; `attempt >= max_attempts` → `state='dead'`
      — 60s base, doubling, capped 1h, with **jitter**: without it a batch that failed
      together retries together and thunders the herd on whatever just recovered.
      The dead/retry decision is made in SQL against the row's own attempt counter, not
      the worker's in-memory copy, so a concurrent reaper can't grant an extra attempt.
      TRAP FOUND AND CLOSED (confirmed against the live schema before writing code):
      `crawl_job_att_ck CHECK (attempt <= max_attempts)` + claim-time `attempt+1` means
      a job reaped at attempt=max_attempts violates the CHECK on its next claim and
      takes the whole batch down with it. Guarded in both the claim predicate
      (`attempt < max_attempts`) and the reaper's CASE.
      VERIFIED live: broken source → attempt 1/5 pending, retry in 53s (jittered from
      60s); fast-forwarded to 5/5 → `dead`, finished_at set, lease cleared.
- [x] Lease reaper: `state='running' AND locked_until < now()` → back to pending
      — `CrawlScheduler.reap()`. Without it an OOM-killed worker leaves jobs 'running'
      forever, and because 'running' is INSIDE the partial unique index that source can
      never be enqueued again — one crash silently retires a source. VERIFIED: expired
      lease → pending; exhausted job → dead, never pending.
- [x] `crawl_log` row per HTTP attempt
      — success and failure both. VERIFIED: 20 rows for the 20-source run
      (407 found / 401 new), 2 rows for the broken source's 2 real attempts.
- [x] Backoff on `source.consecutive_failures` — a dead site shouldn't be hit every 15 min
      — distinct from the per-job retry above: this is a property of the SOURCE across
      jobs. Capped at 1h so a recovered source is picked up within the hour.
      VERIFIED: failure → consecutive_failures=1, next_crawl_at pushed 55s; success →
      reset to 0 and next_crawl_at = now + crawl_interval_sec (900s sources came due in
      ~8 min, 1800s in ~23 min — the per-source interval the old scheduler ignored).

### Politeness (this is a portfolio piece — being a bad citizen is a bad look)
- [ ] robots.txt respected and cached
- [ ] Per-domain rate limit
- [x] Real User-Agent with a contact URL
      — `PhuSaBot/0.1 (+https://github.com/leanhthien/phusa)`, since Phase 0. Landed
      early; ticking it now that the politeness section is being worked properly.
      Per-source override available via `source.config.userAgent` (still unused —
      all 20 sources accept the bot UA).
- [x] HTTP conditional GET — `etag` / `last_modified` columns already exist. A 304 should
      cost you nothing
      — `FeedFetcher` sends `If-None-Match` / `If-Modified-Since` from the validators
      stored on `source`; returns a sealed `FetchResult` (`Fetched` | `NotModified`).
      THE TRAP, and the reason this is worth a test: **304 is not in the 2xx range.**
      The pre-existing `check(status in 200..299)` turned the single best outcome of a
      conditional request into an exception — which would then feed retry/backoff and
      eventually mark a perfectly healthy source dead. Checked for 304 *before* the
      success check.
      Sealed type, not `SyndFeed?`: "nothing changed" and "nothing came back" are
      opposite events (best case vs failure), and collapsing them invites exactly the
      bug above.
      `storeValidators` uses COALESCE and is NOT called on 304 — a server may send only
      one validator, and RFC 9110 lets a 304 omit the ETag entirely, so treating either
      as authoritative would erase working state on every successful hit.
      MEASURED across all 20 sources (fetch, then re-fetch with validators):
      **10/20 return 304; 1.05 MB of 2.05 MB saved on a no-change poll (~51%).**
      Verified end-to-end through the app on two passes: pass 1 all 200 + validators
      stored for 16 sources; pass 2 → 10× 304, 30× 200 across both passes, all 40 jobs
      succeeded, zero consecutive_failures, validators retained, 304 rows logged
      items 0/0 (nothing changed ≠ unknown). Pass 2's 200s found 660 items, only 53
      new — the upsert absorbing the rest.
      FINDINGS worth keeping: 4 sources advertise validators and then ignore them
      (return 200 anyway), so the 200-despite-conditional path has to be ordinary, not
      an error. And the VN sources have the weakest HTTP hygiene — Viblo, VnExpress
      and GenK offer no validators at all; only ZNews and TopDev 304. The bandwidth win
      is concentrated in the international half.
      `FeedFetcherConditionalTest` (9 tests) covers it against a JDK stub server — no
      container, no network. 27/27 green with the other unit tests.

### Dedup — the interesting problem, do it in layers
- [x] URL canonicalization: strip `utm_*`, fragments, sort query params → `canonical_url`
      — `UrlCanonicalizer` (pure, total, no Spring) + `V5__canonicalize_article_urls.sql`
      to retrofit the 582 existing rows. EVERY RULE CHOSEN AGAINST THE REAL TABLE, and
      the REJECTED rules are the more interesting half:
      • lowercase the path — REJECTED, decisive counter-evidence: all 47 uppercase rows
        have it in the *path* (`bliki/VibeCoding.html`, `podcast-S5E18`). Only scheme and
        host are case-insensitive (RFC 3986 §6.2.2.1); lowercasing 404s six real articles.
      • http→https upgrade — REJECTED. 2 rows are plain http. Rewriting the scheme
        asserts reachability we never verified.
      • strip `www.` — REJECTED. 57 rows have it, *zero* collide with a bare-host twin;
        different DNS name, may serve different content. No measurable gain.
      • trailing slash — APPLIED, and it's the one rule with no collision evidence
        (158 have one, none collide). Narrower argument: unlike www/https it can't change
        which server answers. Stated plainly as the weakest link rather than hidden.
      WHY THE MIGRATION IS MANDATORY, not tidy-up: `url_hash` is GENERATED from
      `canonical_url` and carries the UNIQUE constraint, so canonical_url IS the dedup
      identity. Ship the canonicalizer alone and the next crawl sees a *different* string
      for every InfoQ article (16 rows, all with `utm_*`) → different hash → no conflict
      → INSERT. Every known article from a param-emitting source silently doubles.
      Canonicalizer without backfill is worse than neither.
      THE HAZARD, real and not theoretical: canonicalization is lossy on purpose, so rows
      that were unique can stop being unique. martinfowler.com publishes incrementally and
      the feed points at a new `#section` each time — THREE rows (5520/5523/5524) collapse
      onto one document once the fragment is dropped. RFC 3986: the fragment is never sent
      to the server, so they are one resource and merging is correct.
      RESOLUTION: earliest `published_at` wins (id tie-break); losers get
      `status='duplicate'` + `duplicate_of_id`. Losers KEEP their original URL — an
      untouched URL can't collide, which is what makes the winners' hash recompute safe.
      Losers first, winners second; the order is load-bearing. Nothing is deleted:
      `article_content`, `article_tag`, `article_embedding` and `bookmark` all FK here and
      a vanishing bookmark is a worse bug than a duplicate row.
      DRIFT RISK NAMED AND MEASURED: the SQL is a second implementation of the same rules.
      Verified by differential test — dumped all 582 live URLs through the SQL function and
      through the Kotlin: **582 compared, 0 mismatches.** The SQL is then DROPped; it is a
      frozen historical fix, not a shared home for the rules (a Flyway migration must mean
      the same thing forever, so it must not call live app code).
      VERIFIED end-to-end: V5 applied in 33ms → 0 utm remaining, 171 URLs rewritten, 2
      rows marked duplicate. Re-crawled all 20 sources: the original 16 InfoQ rows kept
      their ids and `discovered_at`, so the incoming canonicalized URL matched the
      backfilled one — no re-insert. Today's 15 InfoQ rows are genuinely new (0 path
      collisions). Zero repeated title-within-source across all 832 rows.
      ALSO: `duplicate_of_id` is a self-FK with `ON DELETE SET NULL` and had no index, so
      every article delete sequentially scanned `article` for referrers. Added partial
      (`WHERE duplicate_of_id IS NOT NULL`) now that the column is finally populated.
      `UrlCanonicalizerTest` 15 tests, half of them asserting the rejected rules DON'T
      apply — an over-eager canonicalizer merges two real articles and the loser is
      unrecoverable. 42/42 unit tests green.
- [~] Exact: SHA-256 of normalized body → `content_hash`
      — `ContentNormalizer` (strip tags via jsoup → strip zero-width → collapse
      whitespace → trim → SHA-256) + `article_content` written straight from the feed
      where the feed carries the real article. PARTIAL ON PURPOSE: 5 of 20 sources ship
      full text, so the other 15 stay NULL until the extractor lands. `content_hash` is
      populated for 69 articles; the layer is correct and idempotent but has caught
      **0 duplicates so far**, which is the honest result — exact-body matches need
      syndication, and the 5 full-text sources are topically disjoint. It earns its keep
      when the extractor gives all 20 sources bodies.
      MEASURED ALL 20 FEEDS FIRST. Full text: github-blog, android-developers, topdev,
      devto, kotlin-blog (median ~5000-6600 normalized chars). Everything else is a
      teaser (8-1094).
      WHY IT IS DECLARED PER-SOURCE (`SourceConfig.feedHasFullContent`) AND NOT DETECTED
      — both obvious heuristics were tested and both fail:
      • ELEMENT NAME fails. Dev.to ships the whole post in plain `<description>`; Tinh tế
        ships a 516-char teaser in `<content:encoded>`, the element that supposedly means
        full content. So `richestBody` takes the LONGEST element, not the best-named one.
      • LENGTH ALONE is unsafe. After normalization there is a clean 4.6x gap
        (~5000 vs ~1094) — but that gap only exists BECAUSE of normalization, and the raw
        numbers lie: the Stack Overflow blog's "1445-char body" is 190 chars of text plus
        **1160 zero-width padding characters** (86% of the payload). A rule that stays
        correct only while normalization stays perfect is a rule that breaks silently.
      THE CASE THE FLOOR EXISTS FOR: the Lobsters feed ships the byte-identical body
      "Comments" for all 25 items. A naive body hash marks 24 real articles as duplicates
      of the 25th, and nothing about that is visible from the outside.
      `MIN_HASHABLE_CHARS = 500` is a BACKSTOP, not the classifier — it sits in a wide
      empty band (longest pathological body 378, shortest real full-text ~5000). Below it
      `hash()` returns NULL, and `article_content_hash_idx` is partial
      (`WHERE content_hash IS NOT NULL`) so unhashed rows cost nothing.
      Zero-width stripping is measured, not defensive: checked twice, the padding is
      STABLE across fetches (identical SHA-256), so it does not destabilise the hash —
      but it wrecks every length judgement and would break a hash comparison against any
      copy that had been through a system which strips it.
      A teaser is never written to `article_content.text_plain`: storing one under that
      name poisons every later layer that trusts it to be the article, and the FTS
      trigger would index the teaser as the body. `extractor='feed'` records provenance
      so a later pass can tell "already have the real article" from "have what the feed
      gave us".
      VERIFIED end-to-end: 69 bodies stored across the 5 declared sources, 0 below the
      floor, 0 hashes on the other 15, FTS trigger fired on all 69 (`search_tsv` set —
      body search had nothing to index before this). Second crawl: 0 written, 0 bodies,
      `extracted_at` unchanged — the `IS DISTINCT FROM` guard matters here because
      writing `text_plain` rebuilds the tsvector, so an unguarded write would re-index
      every article every crawl.
      UNEXPECTED FINDING, and it is layer 1 and layer 2 working together: a github.blog
      post was first discovered via **Hacker News** on 2026-07-23; today github-blog's own
      feed carried the same canonical URL, matched on `url_hash`, and enriched the
      existing row instead of duplicating it. Consequence worth designing for later:
      `article.source_id` means FIRST DISCOVERER, not publisher — that article is
      attributed to HN. A real fix is an `article_source` join table (many-to-many);
      noted, not done, because it is a schema change well beyond this layer.
      `ContentNormalizerTest` 12 tests. 54/54 unit tests green.
- [ ] Near: simhash of body → `simhash`, Hamming distance threshold
- [ ] Headline: pg_trgm similarity within a 48h window
- [ ] Semantic: embeddings (Phase 4 — catches syndication the others miss)
- [ ] Loser gets `status='duplicate'` + `duplicate_of_id` → earliest published wins
- [ ] **Write up the layering in the README.** "I used four techniques because each
      catches what the previous one misses" is a better story than any single one

> **Exit: 10 sources crawling on a schedule, unattended, for 48 hours, with no duplicates
> in the feed and no source hammered.**

---

## Phase 2 — API ⏱ ~2 weeks

- [ ] Spring Security + JWT: short access token, rotating refresh
  - [ ] Store `token_hash`, never the token — a DB dump must not be a session hijack kit
  - [ ] Reuse of a rotated token → revoke the whole family (`replaced_by_id` chain)
- [ ] Google OAuth
- [ ] Keyset pagination everywhere — feed, bookmarks, search
- [ ] springdoc-openapi → Swagger UI, linked from the README
- [ ] Bean validation on every DTO
- [ ] `@ControllerAdvice` → RFC 7807 problem+json
- [ ] Structured JSON logging + correlation ID via MDC
- [ ] Redis cache on the hot feed query — **and a written invalidation story.** "How do you
      invalidate?" is the follow-up question, always
- [ ] Bucket4j rate limiting
- [ ] Tests: JUnit 5 + MockK + Testcontainers. Meaningful coverage on ingest + dedup,
      not 90% on getters

> **Exit: Swagger UI is public, auth works end to end, and `./gradlew test` spins up
> Postgres and passes from cold.**

---

## Phase 3 — Frontend ⏱ ~2 weeks

- [ ] Three-pane: sources rail / feed list / reader pane
- [ ] Dark mode
- [ ] Reader view, tag filters, read/unread state
- [ ] Bookmarks, optimistic UI
- [ ] Search box on Postgres FTS with `ts_headline` highlighting
- [ ] Admin: source CRUD, crawl job health table, manual re-trigger, `crawl_log` graph
- [ ] Lighthouse ≥ 90, OG images, SEO metadata

> **Exit: it looks intentional.** Not impressive — intentional. Don't gold-plate; you're
> not selling frontend depth.

---

## Phase 4 — The keywords ⏱ ~2 weeks

Every item here needs a defensible "why". Adding a technology you can't justify is worse
than not having it.

### Benchmark first
- [ ] Run `db/benchmark/seed_and_explain.sql` — 500k rows, before/after `EXPLAIN ANALYZE`
- [ ] **Capture both plans as screenshots.** Seq Scan + external merge sort → Index Scan
      Backward with no Sort node. This is the single most persuasive artifact you will produce
- [ ] OFFSET at page 1 vs page 10,000 vs keyset — record the numbers
- [ ] Benchmark FTS at 500k docs. **If it's fast enough, that IS your Elasticsearch
      decision** — and "I measured, and didn't need it" is a stronger answer than having it

### Then
- [ ] Kafka: crawler publishes `article.discovered`, consumers do summarise / tag / embed
  - [ ] Now "event-driven" is honest, and the `@Version` optimistic locking earns its keep
        because consumers genuinely race
- [ ] Elasticsearch — only if the benchmark justified it. Own the sync + reindex story
- [ ] pgvector embeddings → related articles + semantic dedup
  - [ ] Mind the post-filtering trap (see CLAUDE.md)
- [ ] LLM 3-bullet TL;DR + auto-tagging, **cached in the DB so you don't re-bill**
- [ ] SSE or WebSocket push when new articles land

> **Exit: you can answer "why Kafka?" with a sentence about fan-out, and "why (not)
> Elasticsearch?" with a number.**

---

## Phase 5 — Flutter ⏱ ~2 weeks

- [ ] Feed, reader, bookmarks
- [ ] Offline cache with Drift
- [ ] FCM push on new articles in followed sources
- [ ] Same API, zero mobile-specific endpoints — that's the proof the API design is clean

> **Exit: it works on a plane.**

---

## Phase 6 — Polish ⏱ ~1 week

- [ ] GitHub Actions: lint → test → build → deploy
- [ ] Actuator + Prometheus + Grafana dashboard (crawl success rate, queue depth, p99)
- [ ] Sentry
- [ ] **README, properly** — see below

---

## Interview artifacts — the actual deliverable

Tick these off as they become true. They matter more than any feature.

- [ ] **Live demo link at the top of the README.** An undeployed portfolio project is
      worth ~30% of a deployed one
- [ ] Architecture diagram (C4-ish)
- [ ] `EXPLAIN ANALYZE` before/after screenshots
- [ ] OFFSET vs keyset timings at depth
- [ ] **"Hard problems I solved"** section:
  - [ ] Layered dedup, and why one technique isn't enough
  - [ ] Vietnamese diacritics — indexing both accented and unaccented forms, and why
        unaccenting everything is lossy in Vietnamese but not in French. *Nobody else's
        portfolio has thought about this. Lead with it.*
  - [ ] Keyset over OFFSET — including that it's *correct* under concurrent inserts,
        not just faster
  - [ ] `SKIP LOCKED` job queue, and why the queue is a table (transactional enqueue)
        rather than Redis
- [ ] **"Decisions I reversed"** section — X/Twitter dropped (paid, hostile API);
      partitioning rejected (breaks the `url_hash` unique constraint, kills dedup).
      Documented rejections read as judgment. Most portfolios have none

---

## Session Log

Append when you stop. One line per session: what landed, what broke, what's next.

```
YYYY-MM-DD  Phase 0  —
2026-07-15  Phase 0  Wrote docker-compose.yml (pgvector/pgvector:pg16 + Redis 7).
                     No Docker on this machine, so validated migrations against
                     local Homebrew pg15 instead. RESULT: V1 + V2 apply clean, zero
                     errors — the predicted breakage didn't happen. V3 blocked only
                     by missing `vector` extension (no SQL error reached). Next:
                     stand up Docker/pg16, run V3, then Gradle + Spring Boot skeleton.
2026-07-15  Phase 0  Docker Desktop installed. Brought up the pg16 + redis stack,
                     ran V1–V3 against the LOCKED environment: all clean, all 5
                     extensions present. Smoke test passed — generated url_hash,
                     HNSW index, and dual-form Vietnamese search all work. The
                     schema is proven. Volume wiped back to empty so the app's
                     Flyway owns the migrations. Next: Gradle + Spring Boot skeleton,
                     Flyway wired with ddl-auto=validate.
2026-07-15  Phase 0  Scaffolded Spring Boot skeleton. Initializr only emits Boot 4.x
                     now, so hand-wrote build.gradle.kts pinned to Boot 3.5.3 /
                     Kotlin 2.1.21 / Gradle 8.14.3 (honoring the locked stack).
                     application.yml: ddl-auto=validate, Flyway on, clean-disabled.
                     VERIFIED via bootRun against pg16: Flyway applied V1–V3, validate
                     passed, health UP, ~1.5s startup. `./gradlew build -x test` green,
                     fat jar builds. TWO environment gotchas hit & documented:
                     (1) local Homebrew pg15 also on :5432 binds loopback, so the app
                     hit the wrong DB — reached the Docker pg16 via host IP instead;
                     (2) Testcontainers blocked by docker-java↔Docker Desktop 29 socket
                     400 (curl OK) — macOS quirk, will pass on Linux CI. Next: entities
                     (Source/Article/ArticleContent) + Rome RSS ingest.
2026-07-16  Phase 0  Step 0 (env cleanup) complete. Stopped Homebrew pg15 (was binding
                     loopback :5432 and shadowing the container) — compose pg16 now
                     owns :5432; verified bootRun connects via plain localhost, Flyway
                     validates, health UP. Testcontainers 400 persists after a Docker
                     Desktop restart; owner decision = defer to Linux CI, don't hack
                     the build around it. Env is clean for backend work. Next: JPA
                     entities (url_hash @Generated is the first gotcha) + Rome ingest.
2026-07-16  Phase 0  Step 1 (entities) done. Source/Article/ArticleContent under
                     vn.phusa.domain + two repos. Proven against compose pg16 with a
                     throwaway @Profile("verify") runner (since removed): ddl-auto
                     validate passes, url_hash @Generated round-trips at 32 bytes,
                     @MapsId shared-PK 1:1 works, cascade persists content. One gotcha
                     beyond CLAUDE.md's list: CHAR(2) `language` fails validate as
                     varchar → fixed with @JdbcTypeCode(CHAR). search_tsv left unmapped.
                     Next: Rome RSS ingest + idempotent ON CONFLICT(url_hash) upsert.
2026-07-16  Phase 0  Step 2 (ingest) done. Added Rome 2.1.0. FeedFetcher (HTTP+UA) →
                     RssIngestService (@Transactional) → FeedIngestOrchestrator (keeps
                     fetch out of the tx and avoids @Transactional self-invocation).
                     Idempotent upsert: ON CONFLICT ON CONSTRAINT article_url_hash_uk
                     DO UPDATE ... WHERE IS DISTINCT FROM. Proven against compose pg16
                     via throwaway verify runner (embedded feed, since removed): pass1
                     wrote 3, pass2 wrote 0, count steady at 3. RssIngestServiceIT
                     (Testcontainers) covers idempotency + update-in-place. Enum
                     decision saved to memory: String now, typed ArticleStatus when the
                     state machine appears. Next: GET /api/articles keyset pagination.
2026-07-16  Phase 0  Step 3 (feed API) done. GET /api/articles, keyset pagination via
                     NamedParameterJdbcTemplate: row-value (published_at,id)<(?,?),
                     opaque base64 cursor, no page numbers. Phase-0 tweak: ingest now
                     writes status='published' (no enrichment pipeline yet) so the
                     partial article_feed_idx has rows. Proved the plan on 5k seeded
                     rows: first page AND deep cursor page both Index Scan using
                     article_feed_idx, NO Sort node, 5 buffers, ~0.05–0.25ms; OFFSET
                     2500 reads 2530 rows / 99 buffers (captured for the README's
                     keyset-vs-OFFSET story). Endpoint paged live, no overlap, bad
                     cursor→400. Added ArticleFeedServiceIT. Next: Step 4 Next.js feed.
2026-07-16  Phase 0  Step 4 (frontend) done. Scaffolded Next 16.2.10 / React 19 /
                     Tailwind 4 in web/ via create-next-app (shadcn deferred to Ph3).
                     One page: FeedList client component, cursor infinite scroll
                     (IntersectionObserver + Load-more fallback), /api proxied via
                     next.config rewrite. Added canonicalUrl to the feed DTO. Verified
                     in-browser: 45 seeded articles paged 3× by cursor, all unique,
                     ordered, ends cleanly; diacritics + dark mode render; prod build
                     green. NOTE: the in-app preview browser can't fire IO or scroll,
                     so auto-scroll was verified via the fallback button; IO path is
                     standard and works in real browsers. Next: Step 5 Ship (Dockerfiles
                     + deploy). Phase 0 exit still needs a scheduled ingest.
2026-07-16  Phase 0  Step 5 (ship) done — Phase 0 exit MET locally. Multi-stage
                     Dockerfiles (backend Gradle→JRE21, web Next standalone), Caddy
                     reverse proxy = single origin (:80) + HTTPS-ready. @Scheduled
                     crawler + SourceSeeder: unattended crawl of dev.to → 12 real
                     articles. Full stack verified in containers via Caddy: page +
                     /api on one origin, real feed renders. Gotcha: Next `output:
                     standalone` bakes rewrites at build time, so runtime BACKEND_ORIGIN
                     was ignored (127.0.0.1 ECONNREFUSED) → solved with the Caddy proxy
                     instead of the app-level rewrite. Added jsoup to strip HTML from
                     RSS summaries (dev.to descriptions were raw HTML). README updated.
                     REMAINING for a public demo: deploy to a VPS w/ domain (owner).
                     Next widening: Phase 1 (real ingestion + layered dedup).
2026-07-21  Phase 0  Deploy prep. Domain secured: ambert.io.vn. VPS not yet bought —
                     recommendation is Singapore (~30-50ms to VN vs ~250ms from EU;
                     the audience clicking the link is in Vietnam), 2 vCPU / 4GB. The
                     4GB is for the BUILD, not the runtime: the stack idles ~1GB but
                     Kotlin compilation + `next build` on the box will OOM at 2GB.
                     SECURITY BUG FOUND & FIXED: compose published postgres, redis and
                     backend on 0.0.0.0 with phusa/phusa creds. Docker writes its
                     forwarding rules into the nat table AHEAD of ufw's INPUT chain, so
                     `ufw deny 5432` would NOT have closed it — a public box would have
                     had Postgres exposed. Now bound to 127.0.0.1 (IDE still reaches
                     it; verified against PG 16.14). Had to fix in the BASE file, not
                     the overlay: compose APPENDS `ports` when merging, so an overlay
                     can add a port but never remove one — which is also why "443:443"
                     in the overlay correctly joins the base "80:80". Caddyfile site
                     address is now {$SITE_ADDRESS::80}: one file, unset→:80 plain HTTP,
                     set→automatic HTTPS. Validated both modes. New prod overlay:
                     secrets from .env, restart policies, mem ceilings, pg tuning
                     (random_page_cost=1.1 — the 4.0 default assumes a spinning disk
                     and can talk the planner out of article_feed_idx at depth) and
                     JVM caps (uncapped, the JVM sizes heap off HOST RAM, not its
                     container share, and grows until the OOM-killer takes Postgres).
                     Landed 712b41b, pushed. Also fixed this file's status header,
                     which still read "Phase 0 — not started" with every box ticked.
                     Next: buy the box, point DNS, deploy — then Phase 1.
2026-07-22  Phase 1  First Phase-1 box: per-source config in JSONB. The column and its
                     Hibernate mapping already existed from V1 — the actual gap was
                     that nothing READ it and the source definition still lived in
                     application.yml. Removed phusa.ingest.default-source (and
                     IngestProperties, which had no other consumer); what remains in
                     yml is deployment-only. V4 adds a jsonb_typeof CHECK: schemaless
                     is not constraint-free, and '[]' / '"rss"' / 'null' are all legal
                     JSONB that break the parse at crawl time. Typed SourceConfig +
                     codec on the app side with ignoreUnknown=true (a row written by a
                     newer deploy must not break an older instance — that's a rollout
                     outage otherwise). Knobs wired through the pipeline: userAgent +
                     requestTimeoutSec → FeedFetcher, maxItems → RssIngestService.
                     Seeding moved to seed/sources.json, insert-if-absent and never
                     update: the scheduler writes next_crawl_at / etag back to these
                     rows and Phase 3 gets an admin UI, so re-applying a file on every
                     boot would silently revert both. Held the line on scope: uniform
                     policy stays in columns, JSONB only for what varies by kind —
                     the failure mode here is config blob-ification.
                     VERIFIED on compose pg16: V4 applies + validate passes; the CHECK
                     rejects array/scalar/null; {"maxItems":3} → "3 considered (12 in
                     feed)"; {"maxItems":"lots"} → WARN + defaults + crawl still
                     succeeds; unknown keys ignored. SourceConfigCodecTest 7/7, plain
                     unit test so it actually runs on this Mac. Also confirmed the
                     seeder skipped the pre-existing devto row.
                     Next: 10+ real sources (extends seed/sources.json), then the
                     crawl_job queue with FOR UPDATE SKIP LOCKED.
2026-07-22  Phase 1  Source list: 1 → 20 (13 international + 7 Vietnamese). Probed
                     every candidate with the project's real UA/Accept before writing
                     it down rather than trusting memory for feed URLs — which was the
                     right call, because four candidates failed and three "obvious"
                     URLs were wrong: viblo.asia/feed is 404 (it's /rss),
                     topdev.vn/blog/feed/ hangs with the trailing slash, and
                     spring.io/blog.atom serves RSS despite the extension. Rejected on
                     evidence: Baeldung (403 to every UA — Cloudflare) and Kipalog
                     (404, looks dead). Two probe bugs found and fixed mid-flight:
                     `grep -c` counts LINES not matches, so minified feeds all reported
                     items=1; and a curl 000 I nearly recorded as UA-gating was just a
                     transient timeout. HONEST FINDING: nothing needed a userAgent
                     override, contradicting my own prediction last session that it
                     would "earn its keep almost immediately". Knob stays (free), but
                     unproven — a reminder not to add config on a hunch.
                     Added SourceSeedFileTest (11 tests) mirroring the table's
                     constraints so a seed typo fails `gradlew test` instead of
                     ApplicationRunner on the VPS. 18/18 green, container-free.
                     BLOCKED: Docker Desktop stopped launching after a sleep/wake
                     ("Launchd job spawn failed", error 163) — retried once, same
                     failure, no lingering processes and no socket. So the live
                     20-source crawl is NOT verified; box left [~] not [x]. Needs a
                     Docker restart (possibly a reboot) then one bootRun.
                     Next: verify that crawl, then the crawl_job queue.
2026-07-23  Phase 1  Job queue done — all 6 boxes. Docker recovered on its own, so the
                     20-source crawl owed from last session also got verified: 19 rows
                     seeded, ALL 20 sources green first try, 407 found / 401 new,
                     articles 144 → 543. That box is now [x].
                     SKIP LOCKED proven with two concurrent psql sessions rather than
                     asserted: A holds 3 locked rows for 6s, B runs the identical claim
                     and returns in 83ms with disjoint ids; the same query with plain
                     FOR UPDATE blocks 4106ms. ~50x — and the honest nuance is that
                     plain FOR UPDATE is still CORRECT (B got different rows once A
                     committed), just serialised. SKIP LOCKED buys throughput, not
                     correctness; worth not overclaiming in an interview.
                     Found a real trap BEFORE writing code by testing the DDL:
                     crawl_job_att_ck is `attempt <= max_attempts` and the claim does
                     attempt+1, so a job reaped at max_attempts violates the CHECK on
                     its next claim and kills the whole batch. Guarded in the claim
                     predicate and the reaper CASE; both verified.
                     Transaction shape is the thing I'd defend hardest: claim commits
                     immediately, fetch runs OUTSIDE any transaction, finish is its own
                     short tx. The lease (locked_until) — not the row lock — is what
                     protects an in-flight job, because it outlives the transaction and
                     other processes can see it.
                     Deleted IngestScheduler (crawled everything every tick, ignored
                     crawl_interval_sec) and FeedIngestOrchestrator (CrawlWorker now
                     plays its role). Per-source intervals now actually honored:
                     900s sources came due in ~8 min, 1800s in ~23 min.
                     ENVIRONMENT NOTE: found the whole Compose stack still running from
                     an earlier session, with phusa-backend on STALE code publishing
                     0.0.0.0:8080 (pre-dating the loopback fix — the fix is in the
                     compose file, but a running container keeps its original config).
                     It had been crawling into the same DB. Stopped backend/web/caddy;
                     rebuild before trusting a container run.
                     Next: politeness (conditional GET is nearly free — etag/
                     last_modified columns already exist), then layered dedup.
2026-07-23  Phase 1  Conditional GET done. FeedFetcher now sends If-None-Match /
                     If-Modified-Since and returns a sealed FetchResult.
                     THE BUG THIS FEATURE IS PRONE TO, found in our own existing code:
                     `check(status in 200..299)` was already there from Phase 0, and
                     304 is NOT 2xx — so the moment conditional requests started
                     working, the best possible outcome would have raised an exception,
                     fed the retry/backoff machinery, and eventually marked healthy
                     sources dead. Would have looked like a flaky network for a week.
                     Check 304 before the success check; sealed type so "nothing
                     changed" can't be confused with "nothing came back".
                     MEASURED rather than assumed: probed all 20 sources with a
                     fetch-then-refetch. 10/20 honour it; 1.05MB of 2.05MB saved on a
                     no-change poll (~51%). Verified end-to-end in the app over two
                     passes: pass 1 all 200 + validators stored for 16 sources, pass 2
                     → 10x 304. All 40 jobs succeeded, zero failures, validators
                     retained, 304s logged items 0/0.
                     Two findings I did not expect. (1) Four sources ADVERTISE
                     validators then ignore them and return 200 anyway — so that path
                     must be ordinary, not an error. (2) The VN sources have the
                     weakest HTTP hygiene of the set: Viblo, VnExpress, GenK offer no
                     validators at all. The saving is concentrated in the
                     international half, which is worth saying plainly in the README
                     rather than implying a uniform 51%.
                     Also ticked "Real User-Agent with contact URL" — true since Phase
                     0, just never ticked.
                     FeedFetcherConditionalTest (9 tests) against a JDK stub HttpServer
                     — no container, no network, deterministic. 27/27 unit tests green.
                     Next: per-domain rate limit + robots.txt, then layered dedup.

2026-07-27 (2)      Dedup layer 1: URL canonicalization + the backfill it forces.
                     Done now rather than later on purpose — url_hash is GENERATED from
                     canonical_url, so this rewrites the dedup identity of every existing
                     row. At 582 rows the fix is one UPDATE; after 48h of unattended
                     crawling it is a collision-handling backfill over a much larger
                     table. Cost grows with delay.
                     The rules came from profiling the real 582 rows, not from a list of
                     things canonicalizers usually do — and four candidate rules were
                     REJECTED on evidence. Lowercasing the path would have 404'd six
                     martinfowler bliki URLs (all 47 uppercase rows have it in the path,
                     not the host). See the Dedup section for the full set.
                     The hazard was real, not hypothetical: martinfowler publishes an
                     article incrementally with a new #section per feed entry, so three
                     rows collapse onto one document. Earliest published wins, losers
                     keep their original URL so the winners' hash recompute can't
                     collide, nothing deleted (four tables FK to article).
                     THE THING I DID NOT WANT TO ASSERT WITHOUT CHECKING: the migration
                     SQL is a second implementation of the Kotlin rules. Dumped all 582
                     live URLs through both — 582 compared, 0 mismatches — then deleted
                     the throwaway test. The SQL function is DROPped at the end of the
                     migration so it can never become an API; a Flyway migration has to
                     keep meaning what it meant when it ran, which is also why it does
                     not call app code.
                     Self-inflicted lesson worth keeping: an earlier manual test ran the
                     migration OUTSIDE a transaction, where `CREATE TEMP TABLE ... ON
                     COMMIT DROP` disappears between statements — the UPDATEs errored but
                     `CREATE INDEX` committed, and the real Flyway run then failed on
                     "already exists". Flyway rolled back cleanly and the data was
                     untouched, so the failure was loud and harmless. Switched to an
                     explicit DROP TABLE so the migration behaves the same in or out of a
                     transaction, and did NOT add `IF NOT EXISTS` — that would have
                     masked a genuine conflict to paper over my own test pollution.
                     Also indexed duplicate_of_id: self-FK, ON DELETE SET NULL, no index,
                     so every delete scanned article for referrers.
                     42/42 unit tests green. The 5 Testcontainers ITs still can't start
                     on this Mac — pre-existing, unrelated.
                     Next: robots.txt + per-domain rate limit, then dedup layers 2-4
                     (content_hash, simhash, pg_trgm headline).

2026-07-28          Dedup layer 2: content_hash. Marked [~], not [x], and the reason is
                     the whole story — `article_content` was EMPTY (0 rows), so "SHA-256
                     of normalized body" had no body to hash. content_hash is not
                     paired with the jsoup extractor, it is BLOCKED on it.
                     What was deliverable without the extractor: the 5 of 20 sources
                     whose feeds already carry the full article. Measured all 20 before
                     writing anything.
                     Two heuristics tested and REJECTED on evidence — element name (Dev.to
                     puts full posts in <description>, Tinh tế puts teasers in
                     <content:encoded>) and length alone (Stack Overflow's "1445-char
                     body" is 190 chars of text plus 1160 zero-width padding characters,
                     86% invisible). So the flag is declared per source and the length
                     floor is only a backstop.
                     The floor exists because Lobsters ships the identical body
                     "Comments" for all 25 items — a naive hash marks 24 real articles as
                     duplicates of the 25th, invisibly.
                     TWO OF MY OWN CLAIMS CORRECTED MID-TASK, both by checking rather than
                     assuming. (1) I suspected the zero-width padding was per-fetch
                     tracking that would destabilise the hash; fetched twice, identical
                     SHA-256 — it is stable, and I said so instead of leaving the scarier
                     story standing. (2) My first probe reported three VN feeds as
                     0-item/broken; they were 301/302 redirects and curl was not following
                     them. FeedFetcher sets Redirect.NORMAL, so the app was fine and the
                     PROBE was broken — same class of mistake as the earlier `grep -c`
                     bug. Probes need verifying before their output becomes a finding.
                     HONEST RESULT: 69 bodies hashed, **0 duplicates caught**. The layer
                     is correct and proven idempotent, but exact-body matching needs
                     syndication and the 5 full-text sources are topically disjoint. It
                     pays off when the extractor gives all 20 sources bodies. Better to
                     record a zero than to imply the layer is working.
                     BONUS FINDING: a github.blog post first discovered via Hacker News
                     got enriched, not duplicated, when github-blog's own feed found the
                     same canonical URL — layers 1 and 2 co-operating. It also exposes
                     that `article.source_id` means first-discoverer, not publisher.
                     `article_source` join table is the real fix; noted, not done.
                     54/54 unit tests green.
                     Next: the jsoup extractor is now on the critical path — it unblocks
                     content_hash for the other 15 sources AND is the prerequisite for
                     simhash (layer 3), which needs the same bodies.
```
