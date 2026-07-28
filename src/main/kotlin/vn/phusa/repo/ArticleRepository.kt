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
            INSERT INTO article (source_id, canonical_url, title, summary, published_at, status,
                                 content_hash, word_count)
            VALUES (:sourceId, :canonicalUrl, :title, :summary, :publishedAt, 'published',
                    :contentHash, :wordCount)
            ON CONFLICT ON CONSTRAINT article_url_hash_uk DO UPDATE
               SET title = EXCLUDED.title,
                   summary = EXCLUDED.summary,
                   content_hash = COALESCE(EXCLUDED.content_hash, article.content_hash),
                   word_count   = COALESCE(EXCLUDED.word_count,   article.word_count)
             WHERE article.title        IS DISTINCT FROM EXCLUDED.title
                OR article.summary      IS DISTINCT FROM EXCLUDED.summary
                OR (EXCLUDED.content_hash IS NOT NULL
                    AND article.content_hash IS DISTINCT FROM EXCLUDED.content_hash)
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("sourceId") sourceId: Long,
        @Param("canonicalUrl") canonicalUrl: String,
        @Param("title") title: String,
        @Param("summary") summary: String?,
        @Param("publishedAt") publishedAt: Instant,
        @Param("contentHash") contentHash: ByteArray?,
        @Param("wordCount") wordCount: Int?,
    ): Int

    /**
     * Stores the article body, keyed by URL rather than by id.
     *
     * WHY NOT RETURN THE ID FROM [upsert] AND USE IT HERE: because `ON CONFLICT DO
     * UPDATE ... WHERE <changed>` returns NO ROW when the guard suppresses the update —
     * which is the common case, an unchanged article. A `RETURNING id` there hands back
     * nothing precisely when the row does exist, so every caller would need a fallback
     * SELECT. Looking the row up by `url_hash` instead keeps one statement with no
     * special case, and it uses `article_url_hash_uk` so the lookup is an index probe.
     *
     * `INSERT ... SELECT` also means a missing article yields zero rows rather than an
     * FK violation — content for an article that failed to insert is simply skipped.
     *
     * The `IS DISTINCT FROM` guard matters more here than on `article`: writing
     * `text_plain` fires `article_content_tsv_update`, which rebuilds the tsvector. Re-
     * writing an unchanged 6KB body on every crawl would mean re-indexing every article
     * on every crawl, for nothing.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO article_content (article_id, text_plain, html, extractor)
            SELECT a.id, :textPlain, :html, :extractor
              FROM article a
             WHERE a.url_hash = digest(:canonicalUrl, 'sha256')
            ON CONFLICT (article_id) DO UPDATE
               SET text_plain   = EXCLUDED.text_plain,
                   html         = EXCLUDED.html,
                   extractor    = EXCLUDED.extractor,
                   extracted_at = now()
             WHERE article_content.text_plain IS DISTINCT FROM EXCLUDED.text_plain
        """,
        nativeQuery = true,
    )
    fun upsertContent(
        @Param("canonicalUrl") canonicalUrl: String,
        @Param("textPlain") textPlain: String,
        @Param("html") html: String?,
        @Param("extractor") extractor: String,
    ): Int
}
