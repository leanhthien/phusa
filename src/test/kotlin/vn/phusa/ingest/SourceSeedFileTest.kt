package vn.phusa.ingest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the real `seed/sources.json` against the constraints the `source` table
 * will enforce anyway.
 *
 * WHY DUPLICATE THE DB'S RULES HERE: without this, a typo in the seed file surfaces as
 * a constraint violation during ApplicationRunner on the next boot — in the deploy
 * logs, on the VPS, after the image is built. The DB is still the authority; this just
 * moves the discovery to `./gradlew test`. It also needs no container, so it runs on
 * the machine where Testcontainers is blocked.
 *
 * Each assertion below names the constraint it mirrors, so when V-something changes
 * the DDL it is obvious what has to change here too.
 */
class SourceSeedFileTest {

    private val seeds: List<SourceSeed> = ClassPathResource(SourceSeeder.SEED_PATH)
        .inputStream.use { jacksonObjectMapper().readValue(it) }

    @Test
    fun `seed file parses and carries the roadmap's 10+ sources`() {
        assertTrue(seeds.size >= 10, "Phase 1 calls for 10+ sources, found ${seeds.size}")
    }

    /** source_slug_uk */
    @Test
    fun `slugs are unique`() {
        val dupes = seeds.groupBy { it.slug }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate slugs: $dupes")
    }

    /** Not a DB constraint, but two rows pointing at one feed is always a mistake. */
    @Test
    fun `feed urls are unique`() {
        val dupes = seeds.mapNotNull { it.feedUrl }.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate feed urls: $dupes")
    }

    /** source_kind_ck */
    @Test
    fun `kind is one of the allowed tokens`() {
        val allowed = setOf("rss", "atom", "html", "api")
        val bad = seeds.filterNot { it.kind in allowed }.map { "${it.slug}=${it.kind}" }
        assertTrue(bad.isEmpty(), "bad kind: $bad")
    }

    /** source_feed_url_ck — a feed-type source must actually have a feed url. */
    @Test
    fun `feed kinds have a feed url`() {
        val bad = seeds.filter { it.kind in setOf("rss", "atom") && it.feedUrl.isNullOrBlank() }
        assertTrue(bad.isEmpty(), "feed kind without feedUrl: ${bad.map { it.slug }}")
    }

    /** source_interval_ck */
    @Test
    fun `crawl interval respects the 60s floor`() {
        val bad = seeds.filter { (it.crawlIntervalSec ?: 900) < 60 }
        assertTrue(bad.isEmpty(), "interval below floor: ${bad.map { it.slug }}")
    }

    /** source_rate_ck */
    @Test
    fun `rate limit is positive when set`() {
        val bad = seeds.filter { it.rateLimitPerMin != null && it.rateLimitPerMin!! <= 0 }
        assertTrue(bad.isEmpty(), "non-positive rate limit: ${bad.map { it.slug }}")
    }

    /** language is CHAR(2) in the DDL — anything longer is silently truncated or errors. */
    @Test
    fun `language is a two letter code when set`() {
        val bad = seeds.filter { it.language != null && it.language!!.length != 2 }
        assertTrue(bad.isEmpty(), "bad language code: ${bad.map { "${it.slug}=${it.language}" }}")
    }

    /** source_config_object_ck (V4) */
    @Test
    fun `config is a json object when set`() {
        val bad = seeds.filter { it.config != null && !it.config!!.isObject }
        assertTrue(bad.isEmpty(), "config not an object: ${bad.map { it.slug }}")
    }

    /** Whatever is in `config` must survive the codec the crawler reads it with. */
    @Test
    fun `every config is readable by the codec`() {
        val mapper = jacksonObjectMapper()
        val codec = SourceConfigCodec(mapper)
        for (seed in seeds) {
            val raw = seed.config?.let { mapper.writeValueAsString(it) } ?: "{}"
            val parsed = codec.read(
                vn.phusa.domain.Source(
                    slug = seed.slug,
                    name = seed.name,
                    homepageUrl = seed.homepageUrl,
                    kind = seed.kind,
                    feedUrl = seed.feedUrl,
                    config = raw,
                ),
            )
            // A config that silently fell back to defaults means a typo'd key or a
            // wrong value type — the codec is tolerant by design, so assert here.
            if (seed.config != null && seed.config!!.has("maxItems")) {
                assertNotNull(parsed.maxItems, "${seed.slug}: maxItems did not survive the codec")
            }
        }
    }

    @Test
    fun `the vietnamese sources that make this project vietnamese are present`() {
        val vi = seeds.filter { it.language == "vi" }
        assertTrue(vi.size >= 5, "expected several VN sources, found ${vi.size}")
        assertEquals(
            emptyList(),
            vi.filter { it.feedUrl.isNullOrBlank() }.map { it.slug },
            "VN sources missing a feed url",
        )
    }
}
