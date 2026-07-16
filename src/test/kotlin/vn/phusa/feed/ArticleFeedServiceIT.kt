package vn.phusa.feed

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.server.ResponseStatusException
import vn.phusa.TestcontainersConfiguration
import vn.phusa.domain.Source
import vn.phusa.repo.ArticleRepository
import vn.phusa.repo.SourceRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Keyset pagination against real Postgres: pages must not overlap, must stay in
 * published_at DESC order, and a malformed cursor must be a 400 — not a 500.
 * (Testcontainers: green on CI; local Docker Desktop quirk documented in ROADMAP.)
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ArticleFeedServiceIT @Autowired constructor(
    private val service: ArticleFeedService,
    private val sources: SourceRepository,
    private val articles: ArticleRepository,
) {

    @Test
    fun `pages walk the feed with no overlap in published_at DESC order`() {
        val source = sources.save(
            Source(slug = "feed-it", name = "Feed IT", homepageUrl = "https://x.example", kind = "rss"),
        )
        val base = Instant.now().truncatedTo(ChronoUnit.MICROS)
        // 7 published articles, strictly decreasing published_at.
        repeat(7) { i ->
            articles.upsert(
                sourceId = source.id!!,
                canonicalUrl = "https://x.example/$i",
                title = "Article $i",
                summary = null,
                publishedAt = base.minusSeconds(i.toLong()),
            )
        }

        val page1 = service.feed(cursor = null, limit = 3)
        val page2 = service.feed(cursor = page1.nextCursor, limit = 3)
        val page3 = service.feed(cursor = page2.nextCursor, limit = 3)

        val p1 = page1.items.map { it.id }
        val p2 = page2.items.map { it.id }
        val p3 = page3.items.map { it.id }

        assertThat(p1).hasSize(3)
        assertThat(p2).hasSize(3)
        assertThat(p3).hasSize(1) // 7 total = 3 + 3 + 1

        // No overlap across pages.
        assertThat((p1 + p2 + p3).toSet()).hasSize(7)

        // Globally ordered by published_at DESC.
        val all = page1.items + page2.items + page3.items
        assertThat(all.map { it.publishedAt }).isSortedAccordingTo(reverseOrder())

        // Last page → no further cursor.
        assertThat(page3.nextCursor).isNull()
    }

    @Test
    fun `a malformed cursor is a 400, not a 500`() {
        assertThatThrownBy { service.feed(cursor = "!!!not-base64!!!", limit = 3) }
            .isInstanceOf(ResponseStatusException::class.java)
    }
}
