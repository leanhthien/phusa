package vn.phusa.ingest

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The outcome of a conditional fetch.
 *
 * Modelled as a sealed type rather than a nullable feed because "nothing changed" and
 * "nothing came back" are completely different events: one is the best possible
 * outcome, the other is a failure. Collapsing them into `SyndFeed?` invites a caller
 * to treat a successful 304 as an error — which is exactly the bug this whole feature
 * is prone to.
 */
sealed interface FetchResult {
    /** 200 with a body. [etag]/[lastModified] are the validators to store for next time. */
    data class Fetched(
        val feed: SyndFeed,
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult

    /** 304. The server confirmed our copy is current; there is no body and nothing to do. */
    data object NotModified : FetchResult
}

/**
 * Fetches and parses a feed over HTTP. Network I/O only — deliberately holds no
 * transaction (see [RssIngestService]).
 *
 * CONDITIONAL GET is the single most polite thing a crawler does, and it is nearly
 * free. We send back whatever validator the server gave us last time; if nothing has
 * changed the server answers 304 with an empty body. No XML is generated, no bandwidth
 * is spent, and on most feeds most of the time that is the outcome — this project
 * polls 20 sources on intervals as short as 15 minutes, and the great majority of
 * those polls have nothing new in them.
 *
 * Remaining politeness work (robots.txt, per-domain rate limiting) is still Phase 1.
 */
@Component
class FeedFetcher {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * [config] comes from the source's JSONB column, not from application.yml — the
     * User-Agent a publisher tolerates and how long its server takes to answer are
     * facts about *that source*, so they are stored next to it and editable without a
     * redeploy.
     *
     * [etag] and [lastModified] are the validators stored on the source from the
     * previous fetch. Pass them and the request becomes conditional. Both are opaque
     * strings echoed back VERBATIM — an ETag may be weak (`W/"abc"`), quoted, or
     * contain almost anything, and a Last-Modified is an HTTP-date whose exact
     * formatting the origin server chose. Reformatting either one breaks the match and
     * silently costs you the whole optimisation.
     */
    fun fetch(
        feedUrl: String,
        config: SourceConfig = SourceConfig.DEFAULTS,
        etag: String? = null,
        lastModified: String? = null,
    ): FetchResult {
        val builder = HttpRequest.newBuilder(URI.create(feedUrl))
            .timeout(Duration.ofSeconds((config.requestTimeoutSec ?: DEFAULT_TIMEOUT_SEC).toLong()))
            .header("User-Agent", config.userAgent ?: USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
            .GET()

        // RFC 9110: when both validators are available, send both. If-None-Match takes
        // precedence at the server — ETags are exact, dates have one-second resolution
        // and can't express "changed twice in the same second".
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) builder.header("If-Modified-Since", lastModified)

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

        // 304 FIRST, before any success check. A conditional request that succeeds
        // perfectly returns 304, which is NOT in the 2xx range — a naive
        // `check(status in 200..299)` turns the best outcome into an exception, and
        // then the retry/backoff machinery punishes the source for being efficient.
        if (response.statusCode() == HTTP_NOT_MODIFIED) {
            response.body().close()
            return FetchResult.NotModified
        }

        check(response.statusCode() in 200..299) {
            "Feed $feedUrl returned HTTP ${response.statusCode()}"
        }

        // XmlReader sniffs the charset from the HTTP headers / XML prolog / BOM.
        val feed = response.body().use { SyndFeedInput().build(XmlReader(it)) }
        return FetchResult.Fetched(
            feed = feed,
            // HttpHeaders lookups are case-insensitive, which matters: HTTP/2 lowercases
            // header names, so a server that sent `ETag` over HTTP/1.1 sends `etag` here.
            etag = response.headers().firstValue("ETag").orElse(null),
            lastModified = response.headers().firstValue("Last-Modified").orElse(null),
        )
    }

    companion object {
        const val USER_AGENT = "PhuSaBot/0.1 (+https://github.com/leanhthien/phusa)"
        const val DEFAULT_TIMEOUT_SEC = 20
        const val HTTP_NOT_MODIFIED = 304
    }
}
