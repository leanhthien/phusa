package vn.phusa.dedup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.TestPropertySource
import vn.phusa.TestcontainersConfiguration
import java.security.MessageDigest
import java.time.Instant

/**
 * Runs against a real pgvector/pg16 Testcontainer, which is not optional here: the
 * resolver is one window-function statement enforced by three CHECK constraints
 * (`article_dup_ck`, `article_not_self_dup`, `article_status_ck`). Mocking the DAO would
 * test nothing — the behaviour under test IS the SQL.
 *
 * (On this macOS dev box the container handshake is blocked by a Docker Desktop quirk,
 * so this is unverified locally and runs on Linux CI. The same SQL was executed by hand
 * against the live database in a rolled-back transaction: 2 demoted, correct winners,
 * and a second run reporting UPDATE 0.)
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["phusa.crawl.enabled=false", "phusa.ingest.enabled=false"])
class DuplicateResolverIT @Autowired constructor(
    private val resolver: DuplicateResolver,
    private val jdbc: NamedParameterJdbcTemplate,
) {

    private fun hash(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())

    private fun sourceId(): Long = jdbc.queryForObject(
        """
        INSERT INTO source (slug, name, homepage_url, kind, feed_url)
        VALUES ('dedup-test-' || gen_random_uuid(), 'n', 'https://e.test', 'rss', 'https://e.test/f')
        RETURNING id
        """.trimIndent(),
        MapSqlParameterSource(), Long::class.java,
    )!!

    private fun article(sourceId: Long, url: String, publishedAt: Instant, contentHash: ByteArray?): Long =
        jdbc.queryForObject(
            """
            INSERT INTO article (source_id, canonical_url, title, published_at, status, content_hash)
            VALUES (:src, :url, :url, :pub, 'published', :hash)
            RETURNING id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("src", sourceId).addValue("url", url)
                .addValue("pub", java.sql.Timestamp.from(publishedAt))
                .addValue("hash", contentHash),
            Long::class.java,
        )!!

    private fun statusOf(id: Long) = jdbc.queryForObject(
        "SELECT status FROM article WHERE id = :id",
        MapSqlParameterSource().addValue("id", id), String::class.java,
    )

    private fun targetOf(id: Long) = jdbc.queryForObject(
        "SELECT duplicate_of_id FROM article WHERE id = :id",
        MapSqlParameterSource().addValue("id", id), Long::class.java,
    )

    @Test
    fun `earliest published wins and the later copy is hidden`() {
        val src = sourceId()
        val h = hash("same body ${System.nanoTime()}")
        val base = Instant.parse("2026-07-01T00:00:00Z")
        val older = article(src, "https://e.test/a-${System.nanoTime()}", base, h)
        val newer = article(src, "https://e.test/b-${System.nanoTime()}", base.plusSeconds(3600), h)

        resolver.resolve()

        assertThat(statusOf(older)).isEqualTo("published")
        assertThat(targetOf(older)).isNull()
        assertThat(statusOf(newer)).isEqualTo("duplicate")
        assertThat(targetOf(newer)).isEqualTo(older)
    }

    /** znews republished under a new slug with the SAME published_at — id breaks the tie. */
    @Test
    fun `identical published_at falls back to the lower id`() {
        val src = sourceId()
        val h = hash("tie ${System.nanoTime()}")
        val at = Instant.parse("2026-07-02T00:00:00Z")
        val first = article(src, "https://e.test/t1-${System.nanoTime()}", at, h)
        val second = article(src, "https://e.test/t2-${System.nanoTime()}", at, h)

        resolver.resolve()

        assertThat(statusOf(first)).isEqualTo("published")
        assertThat(statusOf(second)).isEqualTo("duplicate")
        assertThat(targetOf(second)).isEqualTo(first)
    }

    /** The property that makes a one-minute sweep free. */
    @Test
    fun `a second sweep changes nothing`() {
        val src = sourceId()
        val h = hash("idem ${System.nanoTime()}")
        val base = Instant.parse("2026-07-03T00:00:00Z")
        article(src, "https://e.test/i1-${System.nanoTime()}", base, h)
        article(src, "https://e.test/i2-${System.nanoTime()}", base.plusSeconds(60), h)

        resolver.resolve()
        val second = resolver.resolve()

        assertThat(second.demoted).isZero()
        assertThat(second.flattened).isZero()
    }

    /** Different bodies are different articles, however similar everything else is. */
    @Test
    fun `distinct hashes are left alone`() {
        val src = sourceId()
        val base = Instant.parse("2026-07-04T00:00:00Z")
        val a = article(src, "https://e.test/d1-${System.nanoTime()}", base, hash("one ${System.nanoTime()}"))
        val b = article(src, "https://e.test/d2-${System.nanoTime()}", base, hash("two ${System.nanoTime()}"))

        resolver.resolve()

        assertThat(statusOf(a)).isEqualTo("published")
        assertThat(statusOf(b)).isEqualTo("published")
    }

    /** A null hash means "not evidence of anything" — never a dedup key. */
    @Test
    fun `unhashed articles are never touched`() {
        val src = sourceId()
        val base = Instant.parse("2026-07-05T00:00:00Z")
        val a = article(src, "https://e.test/n1-${System.nanoTime()}", base, null)
        val b = article(src, "https://e.test/n2-${System.nanoTime()}", base.plusSeconds(1), null)

        resolver.resolve()

        assertThat(statusOf(a)).isEqualTo("published")
        assertThat(statusOf(b)).isEqualTo("published")
    }

    /** Syndication: the point of the layer is that it works across sources too. */
    @Test
    fun `duplicates are detected across different sources`() {
        val h = hash("syndicated ${System.nanoTime()}")
        val base = Instant.parse("2026-07-06T00:00:00Z")
        val original = article(sourceId(), "https://e.test/s1-${System.nanoTime()}", base, h)
        val copy = article(sourceId(), "https://e.test/s2-${System.nanoTime()}", base.plusSeconds(7200), h)

        resolver.resolve()

        assertThat(statusOf(original)).isEqualTo("published")
        assertThat(statusOf(copy)).isEqualTo("duplicate")
        assertThat(targetOf(copy)).isEqualTo(original)
    }

    /**
     * The chain case: an article ingested later can carry an EARLIER published_at, which
     * makes a previous winner into a loser. Without flattening, the first duplicate would
     * point at a row that is itself hidden.
     */
    @Test
    fun `a duplicate is repointed when its target later becomes a duplicate`() {
        val src = sourceId()
        val h = hash("chain ${System.nanoTime()}")
        val mid = Instant.parse("2026-07-07T12:00:00Z")

        val b = article(src, "https://e.test/c-b-${System.nanoTime()}", mid, h)
        val c = article(src, "https://e.test/c-c-${System.nanoTime()}", mid.plusSeconds(3600), h)
        resolver.resolve()
        assertThat(targetOf(c)).isEqualTo(b)

        // Now the true original shows up, published before both.
        val a = article(src, "https://e.test/c-a-${System.nanoTime()}", mid.minusSeconds(3600), h)
        resolver.resolve()

        assertThat(statusOf(a)).isEqualTo("published")
        assertThat(statusOf(b)).isEqualTo("duplicate")
        assertThat(targetOf(b)).isEqualTo(a)
        assertThat(targetOf(c)).describedAs("c must point at the survivor, not at a hidden row").isEqualTo(a)
    }
}
