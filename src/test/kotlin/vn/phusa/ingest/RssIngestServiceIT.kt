package vn.phusa.ingest

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import vn.phusa.TestcontainersConfiguration
import vn.phusa.domain.Source
import vn.phusa.repo.ArticleRepository
import vn.phusa.repo.SourceRepository

/**
 * The idempotency proof the ROADMAP asks for: ingest the same feed twice and assert
 * the row count does not move. Runs against a real pgvector/pg16 Testcontainer — the
 * generated `url_hash` and the ON CONFLICT arbiter constraint only exist in real
 * Postgres, so H2 could never validate this.
 *
 * (On this macOS dev box the container handshake is blocked by a Docker Desktop
 * quirk; this passes on Linux CI. See ROADMAP Phase 0 / Testcontainers.)
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["phusa.ingest.enabled=false"])
class RssIngestServiceIT @Autowired constructor(
    private val ingest: RssIngestService,
    private val sources: SourceRepository,
    private val articles: ArticleRepository,
) {

    private fun sampleFeed() =
        SyndFeedInput().build(XmlReader(javaClass.getResourceAsStream("/fixtures/hn-sample.xml")!!))

    @Test
    fun `re-ingesting the same feed creates no duplicate rows`() {
        val source = sources.save(
            Source(
                slug = "hn-it",
                name = "HN (IT)",
                homepageUrl = "https://news.ycombinator.com",
                kind = "rss",
                feedUrl = "https://hnrss.org/frontpage",
            ),
        )
        val feed = sampleFeed()

        val first = ingest.ingest(source, feed)
        val afterFirst = articles.count()

        val second = ingest.ingest(source, feed)
        val afterSecond = articles.count()

        assertThat(first.written).isEqualTo(feed.entries.size)
        assertThat(afterFirst).isEqualTo(feed.entries.size.toLong())
        // Idempotent: second pass writes nothing and the count is unchanged.
        assertThat(second.written).isEqualTo(0)
        assertThat(afterSecond).isEqualTo(afterFirst)
    }

    @Test
    fun `changed title updates in place, still no new row`() {
        val source = sources.save(
            Source(
                slug = "hn-it2",
                name = "HN (IT2)",
                homepageUrl = "https://news.ycombinator.com",
                kind = "rss",
                feedUrl = "https://hnrss.org/frontpage",
            ),
        )

        ingest.ingest(source, sampleFeed())
        val afterFirst = articles.count()

        // Same links, mutated titles → updates, not inserts.
        val edited = sampleFeed().apply { entries.forEach { it.title = it.title + " (edited)" } }
        val second = ingest.ingest(source, edited)

        assertThat(second.written).isEqualTo(edited.entries.size)
        assertThat(articles.count()).isEqualTo(afterFirst)
    }
}
