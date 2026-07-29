package vn.phusa.crawl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces a minimum gap between requests to the same HOST.
 *
 * WHY HOST AND NOT SOURCE, which is the whole point of this class:
 * `source.rate_limit_per_min` exists and is the obvious key, and it is wrong. Measured
 * against the real table, Hacker News's articles point at **51 distinct hosts across 59
 * articles** and Lobsters' at **71 across 76** — aggregators are almost entirely
 * off-site links. A per-source limit would let one Lobsters extraction job issue 76
 * requests "politely" while sending several of them at one small blog in the same
 * second. The bucket has to be the thing that feels the load, and that is the host.
 *
 * A host's own `Crawl-delay` always wins when it is slower than our floor: news.
 * ycombinator.com asks for 30 seconds, which is ten times our default. Honouring a
 * request to go slower costs nothing; ignoring it is the difference between a crawler
 * and a nuisance.
 *
 * NOT a token bucket. A bucket permits bursts by design — 20 requests in the first
 * second of a minute satisfies "20 per minute" and is exactly what a small site
 * experiences as an attack. A minimum inter-request gap cannot burst, which is the
 * property actually wanted here.
 *
 * Single-process, in-memory: this app runs as one instance, and the state is a few
 * hundred entries. When it becomes multi-instance the map has to move to Redis, or each
 * instance will independently believe it is being polite. Noted rather than pre-built.
 */
@Component
class HostRateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)

    /** host -> epoch millis at which the next request to it may proceed. */
    private val nextFreeAt = ConcurrentHashMap<String, Long>()

    /**
     * Blocks until this host may be contacted, then reserves the following slot.
     *
     * Returns how long it waited, which the caller logs — a crawl that is mostly
     * sleeping should be visible rather than merely slow.
     */
    fun acquire(url: String, minGap: Duration): Duration {
        val host = hostOf(url) ?: return Duration.ZERO
        val gapMs = minGap.toMillis().coerceAtLeast(0)

        val now = System.currentTimeMillis()
        // compute() is atomic per key, so two threads targeting one host cannot both
        // read the same "free at" and decide they may go now.
        val waitUntil = nextFreeAt.compute(host) { _, prev ->
            val start = maxOf(prev ?: 0L, now)
            start + gapMs
        }!! - gapMs

        val waitMs = waitUntil - now
        if (waitMs <= 0) return Duration.ZERO

        log.debug("Rate limit: waiting {}ms before hitting {}", waitMs, host)
        try {
            Thread.sleep(waitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        return Duration.ofMillis(waitMs)
    }

    /** Visible for the worker's "how long until I could fetch this" decisions. */
    fun waitMillisFor(url: String): Long {
        val host = hostOf(url) ?: return 0
        return (nextFreeAt[host] ?: 0L) - System.currentTimeMillis()
    }

    fun forget(host: String) = nextFreeAt.remove(host)

    companion object {
        /**
         * Host, lowercased, port ignored.
         *
         * Port is deliberately not part of the key: :80 and :443 on one machine are one
         * server and one set of resources. Including it would let us double the load on
         * a host by alternating schemes.
         */
        fun hostOf(url: String): String? =
            runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
