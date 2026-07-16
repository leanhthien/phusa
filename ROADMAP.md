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

**Status: Phase 0 — not started.** Schema written, never executed.

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
- [ ] `Source`, `Article`, `ArticleContent` entities
  - [ ] `url_hash` → `@Generated(event = [EventType.INSERT])`, do not let Hibernate write it
  - [ ] `search_tsv` → `insertable=false, updatable=false`, or leave unmapped
  - [ ] `ArticleContent` lazy, never fetched by the feed query
- [ ] Rome parses one RSS feed → `Article` rows
- [ ] Upsert is idempotent: `INSERT ... ON CONFLICT (url_hash) DO UPDATE`.
      **Test it by running the ingest twice and asserting the row count is unchanged.**
- [ ] `GET /api/articles` — keyset pagination from day one, opaque cursor, no page numbers
- [ ] One integration test hitting real Postgres

### Frontend
- [ ] Next.js App Router + Tailwind + shadcn/ui
- [ ] One page: article list, infinite scroll on the cursor API
- [ ] Nothing else. No auth, no reader, no dark mode toggle

### Ship
- [ ] Multi-stage Dockerfile per service
- [ ] Deployed to a VPS, real domain, HTTPS
- [ ] README stub: what it is, live link, how to run locally

> **Exit: a stranger can open a URL and see articles that a cron job put there.**
> Until that's true, nothing below matters.

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
```
