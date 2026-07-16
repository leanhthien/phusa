package vn.phusa.feed

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ArticleFeedService(
    private val dao: ArticleFeedDao,
) {
    fun feed(cursor: String?, limit: Int?): ArticleFeedResponse {
        val safeLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val decoded = cursor?.let {
            try {
                FeedCursor.decode(it)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor")
            }
        }

        val items = dao.page(decoded?.first, decoded?.second, safeLimit)

        // A full page means there may be more: hand back a cursor built from the last
        // row. A short page is the end of the feed → no next cursor.
        val nextCursor = if (items.size == safeLimit) {
            items.last().let { FeedCursor.encode(it.publishedAt, it.id) }
        } else {
            null
        }

        return ArticleFeedResponse(items, nextCursor)
    }

    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}
