package vn.phusa.feed

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The feed read. Hand-written SQL (not JPA) on purpose: the row-value keyset
 * predicate `(published_at, id) < (?, ?)` is exactly the shape that maps to one
 * range scan on `article_feed_idx (published_at DESC, id DESC) WHERE status='published'`
 * — column order matches the ORDER BY, so Postgres walks the index backwards and
 * stops after LIMIT rows with **no Sort node**. JPQL can't express row-value
 * comparison cleanly, and the OR-expanded form
 * (`published_at < ? OR (published_at = ? AND id < ?)`) plans far worse.
 */
@Repository
class ArticleFeedDao(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun page(cursorPublishedAt: Instant?, cursorId: Long?, limit: Int): List<ArticleFeedItem> {
        val hasCursor = cursorPublishedAt != null && cursorId != null

        val sql = buildString {
            append(
                """
                SELECT a.id, a.title, a.canonical_url, a.summary, a.image_url, a.published_at,
                       s.slug AS source_slug, s.name AS source_name
                  FROM article a
                  JOIN source s ON s.id = a.source_id
                 WHERE a.status = 'published'
                """.trimIndent(),
            )
            // First page has no cursor and simply reads from the top of the index.
            if (hasCursor) append("\n   AND (a.published_at, a.id) < (:cursorTs, :cursorId)")
            append("\n ORDER BY a.published_at DESC, a.id DESC")
            append("\n LIMIT :limit")
        }

        val params = MapSqlParameterSource().addValue("limit", limit)
        if (hasCursor) {
            // pgjdbc binds OffsetDateTime cleanly to timestamptz.
            params.addValue("cursorTs", OffsetDateTime.ofInstant(cursorPublishedAt, ZoneOffset.UTC))
            params.addValue("cursorId", cursorId)
        }

        return jdbc.query(sql, params) { rs, _ ->
            ArticleFeedItem(
                id = rs.getLong("id"),
                title = rs.getString("title"),
                canonicalUrl = rs.getString("canonical_url"),
                summary = rs.getString("summary"),
                imageUrl = rs.getString("image_url"),
                publishedAt = rs.getObject("published_at", OffsetDateTime::class.java).toInstant(),
                sourceSlug = rs.getString("source_slug"),
                sourceName = rs.getString("source_name"),
            )
        }
    }
}
