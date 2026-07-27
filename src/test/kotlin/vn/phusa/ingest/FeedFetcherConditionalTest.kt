package vn.phusa.ingest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conditional-GET behaviour against a stub HTTP server from the JDK — no Testcontainers,
 * no network, so it runs everywhere and deterministically.
 *
 * The bug this exists to prevent: 304 is NOT in the 2xx range. A `check(status in
 * 200..299)` treats the single best outcome of a conditional request as an exception,
 * which then feeds the retry/backoff machinery and eventually marks a perfectly
 * healthy source dead. It is the kind of regression that looks like a network problem
 * for a week.
 */
class FeedFetcherConditionalTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val fetcher = FeedFetcher()

    /** Requests the stub saw, so tests can assert on what was actually sent. */
    private val received = mutableListOf<Map<String, String>>()

    private val feedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>Stub Feed</title><link>https://example.com</link><description>d</description>
          <item><title>Bài viết tiếng Việt</title><link>https://example.com/1</link></item>
        </channel></rss>
    """.trimIndent()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.executor = null
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        received.clear()
    }

    private fun handle(path: String, responder: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            received += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            responder(ex)
            ex.close()
        }
    }

    private fun respondFeed(ex: HttpExchange, etag: String?, lastModified: String?) {
        etag?.let { ex.responseHeaders.add("ETag", it) }
        lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
        val bytes = feedXml.toByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    @Test
    fun `200 returns the feed and surfaces both validators`() {
        handle("/feed") { respondFeed(it, "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT") }

        val result = fetcher.fetch("$baseUrl/feed")

        val fetched = assertIs<FetchResult.Fetched>(result)
        assertEquals(1, fetched.feed.entries.size)
        assertEquals("Bài viết tiếng Việt", fetched.feed.entries[0].title)
        assertEquals("\"v1\"", fetched.etag)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", fetched.lastModified)
    }

    /** The whole point: a 304 must be a success, not an exception. */
    @Test
    fun `304 returns NotModified rather than throwing`() {
        handle("/feed") { it.sendResponseHeaders(304, -1) }

        val result = fetcher.fetch("$baseUrl/feed", etag = "\"v1\"")

        assertIs<FetchResult.NotModified>(result)
    }

    @Test
    fun `validators are sent as conditional request headers`() {
        handle("/feed") { it.sendResponseHeaders(304, -1) }

        fetcher.fetch("$baseUrl/feed", etag = "\"v1\"", lastModified = "Wed, 21 Oct 2026 07:28:00 GMT")

        val headers = received.single()
        assertEquals("\"v1\"", headers["if-none-match"])
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", headers["if-modified-since"])
    }

    /** No stored validators -> an ordinary unconditional GET. */
    @Test
    fun `no validators means no conditional headers`() {
        handle("/feed") { respondFeed(it, null, null) }

        fetcher.fetch("$baseUrl/feed")

        val headers = received.single()
        assertNull(headers["if-none-match"])
        assertNull(headers["if-modified-since"])
    }

    /**
     * A weak ETag must go back byte-for-byte. Stripping the `W/` prefix or the quotes
     * — a tempting "normalisation" — makes the comparison fail at the origin and
     * silently costs you every 304 from that source.
     */
    @Test
    fun `weak etags are echoed verbatim`() {
        handle("/feed") { it.sendResponseHeaders(304, -1) }

        fetcher.fetch("$baseUrl/feed", etag = "W/\"weak-123\"")

        assertEquals("W/\"weak-123\"", received.single()["if-none-match"])
    }

    /**
     * Plenty of servers advertise validators and then ignore them (4 of this project's
     * 20 sources do). That must be handled as a normal 200, not as a protocol error.
     */
    @Test
    fun `server ignoring conditional headers and returning 200 is handled normally`() {
        handle("/feed") { respondFeed(it, "\"v2\"", null) }

        val result = fetcher.fetch("$baseUrl/feed", etag = "\"v1\"")

        val fetched = assertIs<FetchResult.Fetched>(result)
        assertEquals("\"v2\"", fetched.etag)
    }

    /** A 200 that carries only one validator must not fabricate the other. */
    @Test
    fun `partial validators come back partial`() {
        handle("/feed") { respondFeed(it, "\"only-etag\"", null) }

        val fetched = assertIs<FetchResult.Fetched>(fetcher.fetch("$baseUrl/feed"))
        assertEquals("\"only-etag\"", fetched.etag)
        assertNull(fetched.lastModified)
    }

    /** Real failures must still fail — 304 handling must not swallow a 500. */
    @Test
    fun `server error still throws`() {
        handle("/feed") { it.sendResponseHeaders(500, -1) }

        val error = runCatching { fetcher.fetch("$baseUrl/feed") }.exceptionOrNull()
        assertTrue(error != null, "a 500 must not be treated as success")
        assertTrue(error!!.message!!.contains("500"), "message should name the status: ${error.message}")
    }

    /** Header names are case-insensitive; HTTP/2 lowercases them. */
    @Test
    fun `lowercase etag header is still picked up`() {
        handle("/feed") { ex ->
            ex.responseHeaders.add("etag", "\"lower\"")
            val bytes = feedXml.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
        }

        val fetched = assertIs<FetchResult.Fetched>(fetcher.fetch("$baseUrl/feed"))
        assertEquals("\"lower\"", fetched.etag)
    }
}
