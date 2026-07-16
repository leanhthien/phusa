package vn.phusa.ingest

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import vn.phusa.domain.Source
import vn.phusa.repo.SourceRepository

/**
 * Ensures the Phase-0 default source exists on boot, so a fresh deploy has something
 * for the scheduler to crawl. Idempotent (keyed on slug). Managing many sources +
 * per-source config is Phase 1.
 */
@Component
@ConditionalOnProperty(prefix = "phusa.ingest", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SourceSeeder(
    private val props: IngestProperties,
    private val sources: SourceRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val d = props.defaultSource
        if (sources.findBySlug(d.slug) != null) return
        sources.save(
            Source(
                slug = d.slug,
                name = d.name,
                homepageUrl = d.homepageUrl,
                kind = "rss",
                feedUrl = d.feedUrl,
            ),
        )
        log.info("Seeded default source '{}' ({})", d.slug, d.feedUrl)
    }
}
