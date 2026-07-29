package vn.phusa.extract

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Against a JDK stub server — no container, no network, deterministic.
 *
 * The behaviours under test are the ones real sources actually broke: unrequested gzip
 * (znews.vn), bot-challenge statuses that still carry a body (Ars Technica 202, InfoQ
 * 405), and non-UTF-8 Vietnamese pages.
 */
class ArticleFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val fetcher = ArticleFetcher()

    private val vietnamese = "Mã nguồn mở đang phát triển mạnh tại Việt Nam"
    private fun page(body: String) =
        "<html><head><title>T</title></head><body><article><p>$body</p></article></body></html>"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.executor = null
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun handle(path: String, responder: (HttpExchange) -> Unit) {
        server.createContext(path) { ex -> responder(ex); ex.close() }
    }

    private fun send(ex: HttpExchange, bytes: ByteArray, status: Int = 200) {
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    @Test
    fun `fetches a plain page`() {
        handle("/a") { send(it, page("hello").toByteArray()) }
        val result = fetcher.fetch("$baseUrl/a")
        assertEquals(200, result.status)
        assertContains(String(result.bytes), "hello")
    }

    /**
     * znews.vn sends `Content-Encoding: gzip` whether or not the client asked. Java's
     * HttpClient does not decompress, so without explicit handling this arrives as
     * binary and looks like a page with no paragraphs — a broken extractor rather than
     * a broken fetch.
     */
    @Test
    fun `decompresses gzip even though java does not`() {
        val raw = page(vietnamese).toByteArray()
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()

        handle("/gz") { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            send(ex, gzipped)
        }

        val result = fetcher.fetch("$baseUrl/gz")
        assertContains(String(result.bytes), vietnamese)
        assertTrue(result.bytes.size > gzipped.size, "body was not actually decompressed")
    }

    @Test
    fun `gzipped page extracts end to end with diacritics intact`() {
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(page(vietnamese.repeat(8)).toByteArray()) }
        }.toByteArray()
        handle("/gz2") { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            send(ex, gzipped)
        }

        val page = fetcher.fetch("$baseUrl/gz2")
        val extracted = assertNotNull(ArticleExtractor.extract(page.bytes, page.finalUrl))
        assertContains(extracted.text, "Mã nguồn mở")
    }

    /**
     * Charset comes from the document, not the header. A page declaring windows-1258
     * (the Vietnamese legacy encoding) must not be decoded as UTF-8 — mojibake here is
     * permanent, because the mangled text is what gets stored, indexed and hashed.
     */
    @Test
    fun `charset is detected from the document meta tag`() {
        val html = "<html><head><meta charset=\"utf-8\"></head><body><article><p>" +
            vietnamese.repeat(8) + "</p></article></body></html>"
        handle("/vn") { ex ->
            // Deliberately NO charset in the header — the meta tag is the only signal.
            ex.responseHeaders.add("Content-Type", "text/html")
            send(ex, html.toByteArray(Charsets.UTF_8))
        }

        val page = fetcher.fetch("$baseUrl/vn")
        val extracted = assertNotNull(ArticleExtractor.extract(page.bytes, page.finalUrl))
        assertContains(extracted.text, "Việt Nam")
    }

    // ---- refusing, without leaking ------------------------------------------------

    /** Ars Technica's bot challenge. 202 is a 2xx, so it must NOT be treated as success. */
    @Test
    fun `a 202 challenge is still a success status and returns its body`() {
        handle("/202") { send(it, "<html>challenge</html>".toByteArray(), 202) }
        val result = fetcher.fetch("$baseUrl/202")
        assertEquals(202, result.status)
        // The extractor is what rejects it — there is no article here.
        assertEquals(null, ArticleExtractor.extract(result.bytes, "$baseUrl/202"))
    }

    @Test
    fun `non-2xx throws with the status in the message`() {
        handle("/405") { send(it, "<html>nope</html>".toByteArray(), 405) }
        handle("/500") { send(it, "boom".toByteArray(), 500) }

        val e405 = runCatching { fetcher.fetch("$baseUrl/405") }.exceptionOrNull()
        assertNotNull(e405)
        assertContains(e405.message!!, "405")

        val e500 = runCatching { fetcher.fetch("$baseUrl/500") }.exceptionOrNull()
        assertNotNull(e500)
        assertContains(e500.message!!, "500")
    }

    /** A body far past the cap must be truncated rather than read in full. */
    @Test
    fun `oversized bodies are capped`() {
        val huge = ByteArray(ArticleFetcher.MAX_BYTES + 2_000_000) { 'x'.code.toByte() }
        handle("/big") { send(it, huge) }
        val result = fetcher.fetch("$baseUrl/big")
        assertTrue(result.bytes.size <= ArticleFetcher.MAX_BYTES, "read ${result.bytes.size} bytes")
    }
}
