# Phù Sa

> Vietnamese tech & startup news aggregator — crawls RSS/HTML sources, deduplicates,
> enriches with LLM summaries and tags, and serves a Discord/Linear-style reading UI.

**Phù sa** is Mekong alluvium: sources flow in, junk washes through, and what settles is
fertile. That is the pipeline.

> **Status:** 🚧 In development — **Phase 0 (vertical slice)**. The database schema is
> designed and documented; application code is being built out from the migrations up.
> A live demo link will land here the moment Phase 0 ships.

---

## What it is

A news aggregator focused on the Vietnamese tech ecosystem. It pulls from RSS/Atom feeds
and HTML pages, canonicalizes and deduplicates articles across several layers, and
(later) adds embeddings, semantic related-articles, and LLM-generated TL;DRs.

It is also, deliberately, a **portfolio project** — built to be defended in an interview.
Where a decision was made for its teaching value or its story rather than raw convenience,
that is called out in the docs.

## Tech stack

| Layer | Choice |
|---|---|
| **Backend** | Kotlin · Spring Boot 3.x · Gradle (Kotlin DSL) · Java 21 |
| **Database** | PostgreSQL 16 (`pgvector/pgvector:pg16`) — `vector`, `unaccent`, `pg_trgm`, `pgcrypto`, `citext` |
| **Migrations** | Flyway (`ddl-auto: validate` only — never `update`) |
| **Persistence** | Spring Data JPA / Hibernate 6 |
| **Crawling** | jsoup (HTML) · Rome (RSS/Atom) · Playwright-Java (JS-rendered sites) |
| **Queue** | `crawl_job` table + `@Scheduled` + `FOR UPDATE SKIP LOCKED` |
| **Search** | PostgreSQL full-text search (`tsvector` + GIN) |
| **Cache** | Redis (Spring Cache) |
| **Frontend** | Next.js (App Router) · TypeScript · Tailwind · shadcn/ui |
| **Mobile** | Flutter · Drift (offline cache) · FCM push |
| **Infra** | Docker Compose → GitHub Actions → single VPS |
| **Tests** | JUnit 5 · MockK · Testcontainers against **real Postgres** |

Tests run against a real Postgres via Testcontainers rather than H2 — H2 has no
`tsvector`, no `pgvector`, and no `SKIP LOCKED`, so it would validate nothing that matters.

## Architecture at a glance

```
 RSS / HTML sources
        │
        ▼
 ┌─────────────┐   @Scheduled enqueues    ┌────────────┐
 │  Crawlers   │ ───────────────────────▶ │ crawl_job  │  (FOR UPDATE SKIP LOCKED)
 │ jsoup/Rome  │                          └────────────┘
 └─────────────┘                                 │
        │  canonicalize · dedup (layered)        ▼
        ▼                                   ┌──────────┐
 ┌──────────────┐   narrow feed rows        │ workers  │  summarise · tag · embed
 │   article    │◀──────────────────────────└──────────┘
 │ article_content (body, one join away)    │
 └──────────────┘                           ▼
        │  keyset pagination            Postgres FTS + pgvector
        ▼
 Spring Boot REST API  ──▶  Next.js reader UI  ·  Flutter app
```

## Getting started (local)

> Prerequisites: Docker + Docker Compose, JDK 21.

```bash
# 1. Bring up Postgres (pgvector) + Redis
docker compose up -d

# 2. Apply migrations and run the backend (Flyway runs on boot)
./gradlew bootRun

# 3. Frontend (once it exists)
cd web && npm install && npm run dev
```

> These steps describe the Phase 0 target. As of now the schema migrations under
> `src/main/resources/db/migration/` are the concrete deliverable; the Gradle skeleton
> and Compose file are being wired up next. See **[ROADMAP.md](ROADMAP.md)** for exactly
> what is and isn't done.

## Repository layout

```
db/
  benchmark/seed_and_explain.sql       500k-row seed + before/after EXPLAIN ANALYZE
src/main/resources/db/migration/
  V1__baseline.sql                     source, author, article, article_content, tag,
                                       article_tag, crawl_job, crawl_log, app_user,
                                       refresh_token, bookmark
  V2__fulltext_search.sql              tsvector + GIN + triggers, Vietnamese diacritics
  V3__embeddings.sql                   pgvector, HNSW, related-articles
CLAUDE.md                              engineering decisions & gotchas (the real docs)
ROADMAP.md                             phased task list, exit criteria, session log
```

The SQL files carry extensive inline comments — they are the primary documentation for
*why* the schema looks the way it does.

## Design decisions worth reading

This project treats a handful of database problems as first-class, because the database
layer is where the interesting engineering lives.

- **Layered deduplication.** URL canonicalization → exact content hash → simhash
  (near-duplicate) → `pg_trgm` headline similarity → (later) embeddings for syndication.
  Four techniques, because each catches what the previous one misses.

- **Vietnamese diacritics, indexed both ways.** Vietnamese users routinely type without
  accents (`ma nguon mo` → `mã nguồn mở`), but stripping accents is *lossy* in Vietnamese
  in a way it isn't in French — `ma / mà / má / mã / mả` are five different words. The
  `tsvector` indexes both accented and unaccented lexemes: precise queries stay precise,
  lazy queries still hit. Costs ~2× index size; worth it.

- **Keyset pagination, not `OFFSET`.** Row-value form `(published_at, id) < (?, ?)` — one
  index range condition, and it stays *correct* under concurrent inserts, not merely
  faster. The API exposes an opaque cursor, never a page number.

- **A job queue that is a table.** `FOR UPDATE SKIP LOCKED` gives N workers zero
  contention, and a plain table means enqueue is transactional with the crawl that
  produced it — something a Redis queue can't offer for free.

- **`article` / `article_content` split.** The feed query scans `article` constantly;
  narrow rows mean more per 8 KB page. Body text is one join away, loaded only in the reader.

## Deliberate non-goals

Documented rejections are part of the point — they read as judgment, not omission.

- **No X/Twitter ingestion.** The API is paid and hostile. Sources are HackerNews, Dev.to,
  Reddit, and Vietnamese tech blog RSS instead.
- **No Elasticsearch or Kafka before Phase 4** — and only then with a benchmark that
  justifies them by a number. "I measured and didn't need it" is a stronger answer than
  having it.
- **No table partitioning on `article`.** Partitioning forces the partition key into every
  unique constraint, so `UNIQUE(url_hash)` would become `UNIQUE(url_hash, published_at)` —
  which no longer stops the same URL arriving twice. That's the dedup guarantee gone.
  Revisit past ~50M rows.

## Roadmap

Phase 0 vertical slice (deployed) → 1 ingestion + dedup → 2 API + auth → 3 frontend →
4 Kafka / Elasticsearch / embeddings / LLM → 5 Flutter → 6 CI + observability.

Full task list, per-phase exit criteria, and progress live in **[ROADMAP.md](ROADMAP.md)**.

## License

Not yet licensed. All rights reserved by the author until a license is added.
