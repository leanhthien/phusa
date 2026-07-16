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

    fun fetch(feedUrl: String): SyndFeed {
        val request = HttpRequest.newBuilder(URI.create(feedUrl))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT)
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
    }
}
