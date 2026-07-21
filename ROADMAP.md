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
VPS with `ambert.io.vn` pointed at it. Next widening: Phase 1.

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
- [ ] Per-source config in DB (`source.config` JSONB), not in application.yml
- [ ] 10+ real sources. HackerNews, Dev.to, Reddit, VN tech blogs
- [ ] jsoup + readability-style extraction for feed-less sites
- [ ] Playwright-Java for JS-rendered sites — **only if a real source demands it**

### Job queue
- [ ] `@Scheduled` scans `source` for `next_crawl_at <= now()`, enqueues `crawl_job`
- [ ] Worker claims with `FOR UPDATE SKIP LOCKED`, batch of 10
- [ ] Retry with exponential backoff; `attempt >= max_attempts` → `state='dead'`
- [ ] Lease reaper: `state='running' AND locked_until < now()` → back to pending
- [ ] `crawl_log` row per HTTP attempt
- [ ] Backoff on `source.consecutive_failures` — a dead site shouldn't be hit every 15 min

### Politeness (this is a portfolio piece — being a bad citizen is a bad look)
- [ ] robots.txt respected and cached
- [ ] Per-domain rate limit
- [ ] Real User-Agent with a contact URL
- [ ] HTTP conditional GET — `etag` / `last_modified` columns already exist. A 304 should
      cost you nothing

### Dedup — the interesting problem, do it in layers
- [ ] URL canonicalization: strip `utm_*`, fragments, sort query params → `canonical_url`
- [ ] Exact: SHA-256 of normalized body → `content_hash`
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
```
