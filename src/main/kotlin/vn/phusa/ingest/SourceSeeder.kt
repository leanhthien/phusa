package vn.phusa.ingest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import vn.phusa.domain.Source
import vn.phusa.repo.SourceRepository

/** One entry in `seed/sources.json`. Unknown keys ignored so the file can lead the code. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceSeed(
    val slug: String,
    val name: String,
    val homepageUrl: String,
    val kind: String,
    val feedUrl: String? = null,
    val language: String? = null,
    val crawlIntervalSec: Int? = null,
    val rateLimitPerMin: Int? = null,
    /** Free-form per-kind settings; stored verbatim into `source.config` (JSONB). */
    val config: JsonNode? = null,
)

/**
 * Bootstraps the source list from `seed/sources.json` so a fresh deploy has something
 * to crawl.
 *
 * INSERT-IF-ABSENT, NEVER UPDATE. This is the important property, and it is why the
 * seed file is not "the config". Once a row exists the *database* owns it: the Phase 1
 * scheduler writes `next_crawl_at`, `consecutive_failures` and the conditional-GET
 * etag/last-modified back to it, and the Phase 3 admin UI will let a human edit crawl
 * policy directly. A seeder that re-applied the file on every boot would silently
 * revert all of that on the next restart — the classic "why did my change disappear?"
 * bug. The file is a starting point, not a source of truth.
 *
 * That is also the answer to "why not just keep this in application.yml?": yml is
 * read-only to the running app and needs a redeploy to change. Crawl policy has to be
 * mutable at runtime — by the scheduler and by an admin — so it lives in the DB.
 */
@Component
@ConditionalOnProperty(prefix = "phusa.ingest", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SourceSeeder(
    private val sources: SourceRepository,
    private val mapper: ObjectMapper,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val seeds = load()
        var created = 0

        for (seed in seeds) {
            if (sources.findBySlug(seed.slug) != null) continue
            sources.save(
                Source(
                    slug = seed.slug,
                    name = seed.name,
                    homepageUrl = seed.homepageUrl,
                    kind = seed.kind,
                    feedUrl = seed.feedUrl,
                    language = seed.language,
                ).apply {
                    seed.crawlIntervalSec?.let { crawlIntervalSec = it }
                    seed.rateLimitPerMin?.let { rateLimitPerMin = it }
                    // V4 constrains this column to a JSON object, so reject anything
                    // else here rather than letting the DB refuse the insert with a
                    // constraint name nobody can trace back to this file.
                    seed.config?.let {
                        require(it.isObject) { "Source '${seed.slug}': config must be a JSON object" }
                        config = mapper.writeValueAsString(it)
                    }
                },
            )
            created++
            log.info("Seeded source '{}' ({})", seed.slug, seed.feedUrl ?: seed.homepageUrl)
        }

        if (created == 0) log.debug("Source seed: all {} source(s) already present", seeds.size)
    }

    private fun load(): List<SourceSeed> {
        val resource = ClassPathResource(SEED_PATH)
        if (!resource.exists()) {
            log.warn("No {} on the classpath — starting with no sources", SEED_PATH)
            return emptyList()
        }
        return resource.inputStream.use { mapper.readValue<List<SourceSeed>>(it) }
    }

    companion object {
        const val SEED_PATH = "seed/sources.json"
    }
}
