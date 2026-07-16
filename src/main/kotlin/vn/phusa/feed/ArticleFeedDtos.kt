package vn.phusa.feed

import java.time.Instant

/** One row of the feed. Narrow on purpose — no body text (that's the reader). */
data class ArticleFeedItem(
    val id: Long,
    val title: String,
    val canonicalUrl: String,
    val summary: String?,
    val imageUrl: String?,
    val publishedAt: Instant,
    val sourceSlug: String,
    val sourceName: String,
)

/**
 * A page of the feed. [nextCursor] is an opaque token to pass back as `?cursor=`;
 * null means there is no next page. The client never sees or constructs page numbers.
 */
data class ArticleFeedResponse(
    val items: List<ArticleFeedItem>,
    val nextCursor: String?,
)
