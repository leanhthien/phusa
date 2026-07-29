package vn.phusa.crawl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import vn.phusa.ingest.FeedFetcher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches, caches and applies robots.txt.
 *
 * CACHING IS NOT AN OPTIMISATION HERE. Without it, obeying robots.txt would DOUBLE the
 * request count — one robots.txt per article fetch — which would make the polite
 * implementation ruder than the impolite one. A day's TTL is the usual convention and
 * matches how rarely these files change.
 *
 * The cache holds failures too, at a shorter TTL. Re-fetching a 5xx robots.txt on every
 * article is the same stampede problem pointed at a host that is already unwell.
 */
@Service
class RobotsService(private val limiter: HostRateLimiter) {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Entry(val rules: RobotsRules, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * May we fetch this URL? Any failure to answer confidently resolves to "no".
     */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = buildString {
            append(uri.rawPath.ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
        }
        return rulesFor(uri).isAllowed(path)
    }

    /** The host's requested delay, or [default] when it asked for nothing slower. */
    fun crawlDelay(url: String, default: Duration): Duration {
        val uri = runCatching { URI(url) }.getOrNull() ?: return default
        val asked = rulesFor(uri).crawlDelaySec ?: return default
        val requested = Duration.ofMillis((asked * 1000).toLong())
        return if (requested > default) requested else default
    }

    private fun rulesFor(uri: URI): RobotsRules {
        val host = uri.host?.lowercase() ?: return RobotsRules.DISALLOW_ALL
        val key = "${uri.scheme}://$host"

        cache[key]?.let { if (Instant.now() < it.expiresAt) return it.rules }

        val (rules, ttl) = fetch(key)
        cache[key] = Entry(rules, Instant.now().plus(ttl))
        return rules
    }

    private fun fetch(origin: String): Pair<RobotsRules, Duration> {
        val url = "$origin/robots.txt"
        return try {
            // robots.txt is itself a request to the host, so it goes through the same
            // limiter. Fetching it in a tight loop while checking whether we may crawl
            // politely would be self-defeating.
            limiter.acquire(url, DEFAULT_GAP)

            val request = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", FeedFetcher.USER_AGENT)
                .header("Accept", "text/plain, */*")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                in 200..299 -> {
                    val body = response.body().take(RobotsTxtParser.MAX_BYTES)
                    val rules = RobotsTxtParser.parse(body, PRODUCT_TOKEN)
                    log.info(
                        "robots.txt {}: matched group '{}', {} allow / {} disallow, crawl-delay={}",
                        origin, rules.matchedAgent, rules.allows.size, rules.disallows.size,
                        rules.crawlDelaySec ?: "-",
                    )
                    rules to SUCCESS_TTL
                }

                // RFC 9309 2.3.1.3 — "unavailable" means no restrictions.
                in 400..499 -> {
                    log.info("robots.txt {} returned {} — treating as allow-all", origin, response.statusCode())
                    RobotsRules.ALLOW_ALL to SUCCESS_TTL
                }

                // RFC 9309 2.3.1.4 — "unreachable" means treat as fully disallowed.
                else -> {
                    log.warn("robots.txt {} returned {} — treating as DISALLOW until it recovers", origin, response.statusCode())
                    RobotsRules.DISALLOW_ALL to FAILURE_TTL
                }
            }
        } catch (e: Exception) {
            // Same reasoning as 5xx: we do not know what the host permits, and assuming
            // permission in our own favour is precisely the thing being avoided.
            log.warn("robots.txt {} unreachable ({}) — treating as DISALLOW", origin, e.message)
            RobotsRules.DISALLOW_ALL to FAILURE_TTL
        }
    }

    /**
     * Seconds until the cached answer for this URL's origin expires — i.e. the earliest
     * moment a re-check could say something different.
     *
     * THE BUG THIS FIXES, found live on the first end-to-end run: hnrss.org failed one
     * TLS handshake, the unreachable->disallow rule (correctly) blocked it, and the
     * worker then deferred the source by the SUCCESS TTL — 24 hours — even though the
     * failure entry expires in 30 minutes. A single network blip silenced Hacker News
     * for a day. Deferring past the cache expiry is always wrong, in both directions:
     * a genuine disallow needs the full TTL, a failure needs the short one, and only
     * the cache knows which one it is holding.
     */
    fun secondsUntilRecheck(url: String): Long {
        val uri = runCatching { URI(url) }.getOrNull() ?: return FAILURE_TTL.seconds
        val host = uri.host?.lowercase() ?: return FAILURE_TTL.seconds
        val entry = cache["${uri.scheme}://$host"] ?: return FAILURE_TTL.seconds
        return Duration.between(Instant.now(), entry.expiresAt).seconds.coerceAtLeast(60)
    }

    /** Test seam — lets a test install rules without a network round trip. */
    fun preload(origin: String, rules: RobotsRules, ttl: Duration = SUCCESS_TTL) {
        cache[origin] = Entry(rules, Instant.now().plus(ttl))
    }

    fun clearCache() = cache.clear()

    companion object {
        /**
         * The product token, NOT the full User-Agent header. RFC 9309 matches on the
         * bare token, so `PhuSaBot/0.1 (+url)` must be compared as `phusabot`.
         */
        const val PRODUCT_TOKEN = "PhuSaBot"

        val DEFAULT_GAP: Duration = Duration.ofSeconds(3)   // 20 requests/min
        val SUCCESS_TTL: Duration = Duration.ofHours(24)
        val FAILURE_TTL: Duration = Duration.ofMinutes(30)
    }
}
