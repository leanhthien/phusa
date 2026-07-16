package vn.phusa.repo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import vn.phusa.domain.Article
import java.time.Instant

interface ArticleRepository : JpaRepository<Article, Long> {

    /**
     * Idempotent insert-or-update keyed on the URL.
     *
     * We never send `url_hash` — it's a STORED generated column (`digest(canonical_url)`)
     * that the DB computes, and it carries the unique constraint `article_url_hash_uk`.
     * ON CONFLICT targets that constraint by name, so re-ingesting the same link updates
     * in place instead of inserting a duplicate.
     *
     * The `WHERE ... IS DISTINCT FROM` guard means an unchanged row is left untouched:
     * no write, no `updated_at` trigger churn, and the method returns 0. So a second
     * ingest of an identical feed writes nothing — that's the idempotency proof.
     *
     * Returns rows affected: 1 on insert or a real update, 0 when the row already
     * matched.
     *
     * Phase 0 note: rows land as `status='published'` so the feed (and the partial
     * `article_feed_idx WHERE status='published'`) has something to show. The real
     * lifecycle — discovered → fetching → extracted → published, driven by the
     * crawler and enrichment — arrives in Phase 1/4.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO article (source_id, canonical_url, title, summary, published_at, status)
            VALUES (:sourceId, :canonicalUrl, :title, :summary, :publishedAt, 'published')
            ON CONFLICT ON CONSTRAINT article_url_hash_uk DO UPDATE
               SET title = EXCLUDED.title,
                   summary = EXCLUDED.summary
             WHERE article.title   IS DISTINCT FROM EXCLUDED.title
                OR article.summary IS DISTINCT FROM EXCLUDED.summary
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("sourceId") sourceId: Long,
        @Param("canonicalUrl") canonicalUrl: String,
        @Param("title") title: String,
        @Param("summary") summary: String?,
        @Param("publishedAt") publishedAt: Instant,
    ): Int
}
