package vn.phusa.ingest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import vn.phusa.domain.Source
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Plain unit test — no Spring context, no database, so it runs everywhere including
 * the machine where Testcontainers is blocked (see ROADMAP). The contract under test
 * is the one the crawler depends on: reading `source.config` must never throw, because
 * a bad config row is not a reason to stop crawling that source.
 */
class SourceConfigCodecTest {

    private val codec = SourceConfigCodec(jacksonObjectMapper())

    private fun sourceWith(config: String) = Source(
        slug = "test",
        name = "Test",
        homepageUrl = "https://example.com",
        kind = "rss",
        feedUrl = "https://example.com/feed",
        config = config,
    )

    @Test
    fun `empty object yields defaults`() {
        val cfg = codec.read(sourceWith("{}"))
        assertEquals(SourceConfig.DEFAULTS, cfg)
        assertNull(cfg.maxItems)
        assertNull(cfg.userAgent)
        assertNull(cfg.requestTimeoutSec)
    }

    @Test
    fun `populated object is parsed`() {
        val cfg = codec.read(
            sourceWith("""{"userAgent":"Custom/1.0","requestTimeoutSec":5,"maxItems":7}"""),
        )
        assertEquals("Custom/1.0", cfg.userAgent)
        assertEquals(5, cfg.requestTimeoutSec)
        assertEquals(7, cfg.maxItems)
    }

    @Test
    fun `partial object leaves the rest unset`() {
        val cfg = codec.read(sourceWith("""{"maxItems":3}"""))
        assertEquals(3, cfg.maxItems)
        assertNull(cfg.userAgent)
    }

    /**
     * Forward compatibility. A row written by a newer deploy (or the Phase 3 admin UI)
     * will carry keys this build has never heard of; the old instance still has to read
     * it. Strict binding would turn a rollout into a crawl outage.
     */
    @Test
    fun `unknown keys are ignored`() {
        val cfg = codec.read(
            sourceWith("""{"maxItems":4,"selectors":{"title":"h1"},"addedInV9":true}"""),
        )
        assertEquals(4, cfg.maxItems)
    }

    /**
     * V4's CHECK guarantees the envelope is an object, but says nothing about the
     * values inside it. A wrong-typed value must degrade to defaults, not propagate.
     */
    @Test
    fun `wrongly typed value falls back to defaults instead of throwing`() {
        val cfg = codec.read(sourceWith("""{"maxItems":"lots"}"""))
        assertEquals(SourceConfig.DEFAULTS, cfg)
    }

    @Test
    fun `malformed json falls back to defaults instead of throwing`() {
        assertEquals(SourceConfig.DEFAULTS, codec.read(sourceWith("{not json")))
    }

    @Test
    fun `write then read round-trips`() {
        val original = SourceConfig(userAgent = "RT/2.0", requestTimeoutSec = 11, maxItems = 42)
        assertEquals(original, codec.read(sourceWith(codec.write(original))))
    }
}
