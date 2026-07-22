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
 * Fetches and parses a feed over HTTP. Network I/O only — deliberately holds no
 * transaction (see [RssIngestService]).
 *
 * Politeness here is the cheap baseline: a real User-Agent with a contact URL so
 * we're never an anonymous hammering bot. robots.txt, per-domain rate limiting and
 * conditional GET (etag/last-modified — columns already exist on `source`) are
 * Phase 1.
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
     * redeploy. Defaulted so callers with nothing to say (tests, ad-hoc fetches) stay
     * one argument short.
     */
    fun fetch(feedUrl: String, config: SourceConfig = SourceConfig.DEFAULTS): SyndFeed {
        val request = HttpRequest.newBuilder(URI.create(feedUrl))
            .timeout(Duration.ofSeconds((config.requestTimeoutSec ?: DEFAULT_TIMEOUT_SEC).toLong()))
            .header("User-Agent", config.userAgent ?: USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) {
            "Feed $feedUrl returned HTTP ${response.statusCode()}"
        }
        // XmlReader sniffs the charset from the HTTP headers / XML prolog / BOM.
        return response.body().use { SyndFeedInput().build(XmlReader(it)) }
    }

    companion object {
        const val USER_AGENT = "PhuSaBot/0.1 (+https://github.com/leanhthien/phusa)"
        const val DEFAULT_TIMEOUT_SEC = 20
    }
}
