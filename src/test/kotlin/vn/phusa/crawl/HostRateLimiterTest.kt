package vn.phusa.crawl

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostRateLimiterTest {

    private val gap = Duration.ofMillis(120)

    @Test
    fun `first request to a host does not wait`() {
        val limiter = HostRateLimiter()
        assertEquals(Duration.ZERO, limiter.acquire("https://example.com/a", gap))
    }

    @Test
    fun `a second request to the same host waits for the gap`() {
        val limiter = HostRateLimiter()
        limiter.acquire("https://example.com/a", gap)
        val start = System.nanoTime()
        limiter.acquire("https://example.com/b", gap)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= gap.toMillis() - 20, "expected to wait ~${gap.toMillis()}ms, waited ${elapsedMs}ms")
    }

    /**
     * The reason this class is keyed on host at all: Lobsters' 76 articles point at 71
     * distinct hosts. Those must not queue behind one another.
     */
    @Test
    fun `different hosts do not block each other`() {
        val limiter = HostRateLimiter()
        val start = System.nanoTime()
        repeat(8) { limiter.acquire("https://host$it.example.com/a", gap) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < gap.toMillis(), "distinct hosts serialised: ${elapsedMs}ms")
    }

    /** Port is not part of the key — same machine, same load. */
    @Test
    fun `scheme and port do not create a second bucket`() {
        val limiter = HostRateLimiter()
        limiter.acquire("https://example.com/a", gap)
        val start = System.nanoTime()
        limiter.acquire("http://example.com:8080/b", gap)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= gap.toMillis() - 20)
    }

    @Test
    fun `host matching is case insensitive`() {
        val limiter = HostRateLimiter()
        limiter.acquire("https://Example.COM/a", gap)
        val start = System.nanoTime()
        limiter.acquire("https://example.com/b", gap)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= gap.toMillis() - 20)
    }

    /**
     * Concurrency is the point of the atomic compute(): N threads aiming at one host
     * must be spaced, not all released together.
     */
    @Test
    fun `concurrent callers on one host are serialised`() {
        val limiter = HostRateLimiter()
        val pool = Executors.newFixedThreadPool(4)
        val start = System.nanoTime()
        repeat(4) { pool.submit { limiter.acquire("https://one.example.com/x", gap) } }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 4 requests at one host => at least 3 gaps between them.
        assertTrue(elapsedMs >= 3 * gap.toMillis() - 40, "bursted: ${elapsedMs}ms for 4 requests")
    }

    @Test
    fun `unparseable urls are not rate limited and do not throw`() {
        val limiter = HostRateLimiter()
        assertEquals(Duration.ZERO, limiter.acquire("not a url", gap))
        assertEquals(Duration.ZERO, limiter.acquire("", gap))
        assertNull(HostRateLimiter.hostOf("mailto:a@b.com"))
    }

    @Test
    fun `extracts host correctly`() {
        assertEquals("example.com", HostRateLimiter.hostOf("https://Example.com/a?b=1"))
        assertEquals("sub.example.co.uk", HostRateLimiter.hostOf("http://sub.example.co.uk:8080/x"))
    }
}
