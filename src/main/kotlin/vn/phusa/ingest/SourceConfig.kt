package vn.phusa.ingest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import vn.phusa.domain.Source

/**
 * The typed view of `source.config` (JSONB).
 *
 * WHAT BELONGS HERE, AND WHAT DOESN'T. Crawl policy that every source has --
 * `enabled`, `crawl_interval_sec`, `rate_limit_per_min`, `next_crawl_at` -- lives in
 * real columns on `source`, because it is uniform, queried (the scheduler selects on
 * it), and worth constraining. This object is only for settings whose *shape varies
 * by source kind*, which is the one thing a column cannot express well.
 *
 * The failure mode to avoid is blob-ification: config JSONB slowly absorbing fields
 * that should have been columns, until nothing is queryable or constrained and the
 * only validation is whatever the app remembers to do. If a setting applies to every
 * source and you would ever filter or sort on it, it is a column.
 *
 * FORWARD COMPATIBILITY: `ignoreUnknown = true` is load-bearing, not politeness. A
 * JSONB document written by a newer deploy (or by the Phase 3 admin UI) will contain
 * keys this version has never heard of, and the old instance still has to read that
 * row. Strict binding would turn a routine rollout into a crawl outage.
 *
 * Every field is nullable and means "unset -> use the caller's default". That keeps
 * the default in one place (the call site that actually knows it) rather than
 * duplicating it here and drifting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceConfig(
    /**
     * Per-source User-Agent override. Some publishers 403 an unfamiliar bot but allow
     * a specific string; that is a per-source negotiation, so it is per-source config.
     * Unset -> [FeedFetcher.USER_AGENT].
     */
    val userAgent: String? = null,

    /** Read timeout for the feed request, seconds. Unset -> [FeedFetcher.DEFAULT_TIMEOUT_SEC]. */
    val requestTimeoutSec: Int? = null,

    /**
     * Cap on entries processed per crawl. Feeds differ wildly in how much history they
     * expose -- some return 10 items, some 500 -- and a firehose source should not be
     * able to dominate a crawl cycle. Unset -> take everything the feed returned.
     */
    val maxItems: Int? = null,
) {
    companion object {
        val DEFAULTS = SourceConfig()
    }
}

/**
 * Reads [SourceConfig] out of a [Source]'s JSONB column.
 *
 * Deliberately total: a malformed or hostile config must not take down the crawl. The
 * scheduler already isolates failures per source, but a parse error is not a reason to
 * skip a source at all -- the sane fallback is "crawl it with defaults" plus a loud
 * log. V4's CHECK constraint means the envelope is at least an object; anything wrong
 * beyond that is a value problem, and values have defaults.
 */
@Component
class SourceConfigCodec(private val mapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun read(source: Source): SourceConfig {
        val raw = source.config.trim()
        if (raw.isEmpty() || raw == "{}") return SourceConfig.DEFAULTS
        return try {
            mapper.readValue<SourceConfig>(raw)
        } catch (e: Exception) {
            log.warn(
                "Source '{}' has unreadable config, falling back to defaults: {}",
                source.slug,
                e.message,
            )
            SourceConfig.DEFAULTS
        }
    }

    fun write(config: SourceConfig): String = mapper.writeValueAsString(config)
}
