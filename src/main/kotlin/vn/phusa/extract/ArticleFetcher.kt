package vn.phusa.extract

import org.springframework.stereotype.Component
import vn.phusa.ingest.FeedFetcher
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Raw page bytes plus the status that produced them. Bytes, not String — see [fetch]. */
data class FetchedPage(val bytes: ByteArray, val status: Int, val finalUrl: String) {
    // ByteArray in a data class needs these; the generated ones compare identity.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Fetches an article page for extraction.
 *
 * RETURNS BYTES, NOT A STRING, and that is the important decision. Decoding here would
 * mean guessing a charset from the Content-Type header, and Vietnamese pages are exactly
 * where that guess goes wrong — a mislabelled or absent charset turns "mã nguồn mở" into
 * mojibake that then gets stored, indexed and hashed. jsoup can sniff the real encoding
 * from the document's own `<meta charset>`, which is more reliable than the header, but
 * only if it is handed the undecoded bytes.
 *
 * GZIP IS HANDLED EXPLICITLY because Java's HttpClient does not do it. This was found
 * the hard way: znews.vn returns `Content-Encoding: gzip` even when the client never
 * sent `Accept-Encoding` — a protocol violation, but a real one. Undecompressed it looks
 * like a page with 43,000 characters and zero `<p>` elements, i.e. a broken extractor
 * rather than a broken fetch. Every VN source is a candidate for this.
 */
@Component
class ArticleFetcher {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * @throws IllegalStateException on a non-2xx response, so the caller's attempt
     *         counter and logging see it as the failure it is.
     */
    fun fetch(url: String, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): FetchedPage {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", FeedFetcher.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Accept-Language", "vi,en;q=0.9")
            .timeout(Duration.ofSeconds(timeoutSec.toLong()))
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()

        // The body must be drained or closed on every path, or the connection leaks.
        val bytes = response.body().use { raw ->
            val encoding = response.headers().firstValue("Content-Encoding").orElse("").lowercase()
            val stream = when {
                encoding.contains("gzip") -> GZIPInputStream(raw)
                encoding.contains("deflate") -> InflaterInputStream(raw)
                else -> raw
            }
            stream.readNBytes(MAX_BYTES)
        }

        // Checked AFTER draining. Ars Technica's 202 and InfoQ's 405 both carry a body,
        // and leaving it unread would leak the connection on precisely the hosts we
        // contact most often once they start refusing us.
        check(status in 200..299) { "HTTP $status for $url" }
        return FetchedPage(bytes, status, response.uri().toString())
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 20

        /**
         * Article pages are text. Anything past this is a mislabelled download or a
         * generated monster, and reading it would trade a bounded fetch for an
         * unbounded one — the largest real page measured here was 1.4MB (tinhte).
         */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
