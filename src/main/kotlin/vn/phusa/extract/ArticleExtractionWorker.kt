package vn.phusa.extract

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import vn.phusa.crawl.ClaimedJob
import vn.phusa.crawl.CrawlJobDao
import vn.phusa.crawl.CrawlProperties
import vn.phusa.crawl.HostRateLimiter
import vn.phusa.crawl.PendingArticle
import vn.phusa.crawl.RobotsService
import vn.phusa.ingest.ContentNormalizer
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

/**
 * Works `fetch_article` jobs: one job per source, a batch of that source's articles.
 *
 * WHY A BATCH PER SOURCE RATHER THAN A JOB PER ARTICLE — the schema decides this.
 * `crawl_job_live_uk` is `UNIQUE(source_id, job_type) WHERE state IN
 * ('pending','running')`, so only one live `fetch_article` job can exist per source and
 * a job-per-article simply cannot be enqueued. That turned out to be the right shape
 * anyway: one job per source serialises a source's fetches by construction.
 *
 * POLITENESS IS PER ARTICLE URL, NOT PER SOURCE, and that distinction is the whole
 * reason this class is careful. Aggregator articles live on third-party hosts —
 * measured, Hacker News's articles span 51 distinct hosts across 59 articles. So
 * robots.txt is consulted for the ARTICLE's origin (which has never heard of this
 * crawler and never opted in), and the rate limiter buckets on the ARTICLE's host.
 * Checking the source's host instead would be checking permission from the wrong party.
 *
 * TRANSACTION SHAPE, same discipline as CrawlWorker: no transaction spans the HTTP
 * fetch. Each article's DB write is its own short transaction via [storeOne], so a slow
 * host cannot pin a connection, and a batch that dies halfway keeps the bodies it
 * already earned.
 */
@Component
class ArticleExtractionWorker(
    private val jobs: CrawlJobDao,
    private val fetcher: ArticleFetcher,
    private val robots: RobotsService,
    private val limiter: HostRateLimiter,
    private val props: CrawlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val workerId: String = buildString {
        append(runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown"))
        append('/')
        append(ManagementFactory.getRuntimeMXBean().name.substringBefore('@'))
    }

    fun runBatch(): Int {
        val now = Instant.now()
        val claimed = jobs.claimBatch(workerId, JOB_TYPE, props.batchSize, LEASE_SECONDS, now)
        if (claimed.isEmpty()) return 0

        log.info("Claimed {} extraction job(s)", claimed.size)
        for (job in claimed) {
            runCatching { work(job) }
                .onFailure { log.error("Extraction job {} ({}) escaped", job.jobId, job.sourceSlug, it) }
        }
        return claimed.size
    }

    private fun work(job: ClaimedJob) {
        val started = System.nanoTime()
        val pending = jobs.articlesNeedingExtraction(job.sourceId, ARTICLES_PER_JOB, MAX_EXTRACT_ATTEMPTS)
        if (pending.isEmpty()) {
            jobs.markSucceeded(job.jobId, Instant.now())
            return
        }

        var extracted = 0
        var blocked = 0
        var failed = 0

        for (article in pending) {
            // Counted BEFORE the attempt, not after. If this process dies mid-fetch the
            // article must not come back forever — crediting the attempt only on a
            // clean finish is how a crawler ends up in an infinite retry loop against
            // the one page that reliably kills it.
            jobs.bumpExtractAttempt(article.id)

            when (val outcome = fetchAndExtract(article)) {
                is Outcome.Extracted -> {
                    storeOne(article, outcome.result)
                    extracted++
                }
                is Outcome.Blocked -> blocked++
                is Outcome.Failed -> failed++
            }
        }

        val elapsed = elapsedMs(started)
        jobs.markSucceeded(job.jobId, Instant.now())
        jobs.logAttempt(job.jobId, job.sourceId, null, elapsed, pending.size, extracted, null)
        log.info(
            "  {} extraction: {}/{} bodies ({} robots-blocked, {} failed) in {}ms",
            job.sourceSlug, extracted, pending.size, blocked, failed, elapsed,
        )
    }

    private sealed interface Outcome {
        data class Extracted(val result: Extraction, val bytes: Int) : Outcome
        data object Blocked : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private fun fetchAndExtract(article: PendingArticle): Outcome {
        val url = article.canonicalUrl
        return try {
            // Permission comes from the ARTICLE's host, which for an aggregator is a
            // third party that never opted in to anything.
            if (!robots.isAllowed(url)) {
                log.debug("  robots.txt forbids {}", url)
                return Outcome.Blocked
            }
            limiter.acquire(url, robots.crawlDelay(url, RobotsService.DEFAULT_GAP))

            val page = fetcher.fetch(url)
            val extraction = ArticleExtractor.extract(page.bytes, page.finalUrl)
                ?: return Outcome.Failed("no article content found")
            Outcome.Extracted(extraction, page.bytes.size)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            log.debug("  extraction failed for {}: {}", url, e.message)
            Outcome.Failed(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    /**
     * One article's writes, in one short transaction.
     *
     * `@Transactional` works here because [runBatch] is invoked from the scheduler —
     * a different bean — so the call arrives through the proxy. A private call from
     * inside this class would bypass the interceptor entirely and silently run
     * unmanaged, which is the self-invocation trap.
     */
    @Transactional
    fun storeOne(article: PendingArticle, extraction: Extraction) {
        val text = extraction.text
        val hash = ContentNormalizer.hash(text)
        jobs.storeExtraction(
            articleId = article.id,
            textPlain = text,
            html = extraction.html,
            extractor = EXTRACTOR,
            contentHash = hash,
            // Only counted when the body was substantial enough to hash — an unhashable
            // body is not evidence of a real article, so its word count would be a
            // number that means nothing.
            wordCount = hash?.let { ContentNormalizer.wordCount(text) },
        )
    }

    private fun elapsedMs(startNanos: Long): Int =
        Duration.ofNanos(System.nanoTime() - startNanos).toMillis().toInt()

    companion object {
        const val JOB_TYPE = "fetch_article"
        const val EXTRACTOR = "jsoup-readability"

        /**
         * MUST match the literal in `article_extract_pending_idx`'s predicate
         * (V6__article_extract_attempts.sql). A partial index predicate has to be
         * immutable, so the cap cannot be parameterised — changing one without the
         * other silently drops the index from the plan.
         */
        const val MAX_EXTRACT_ATTEMPTS = 3

        /**
         * Articles per job. Deliberately modest: at a 3s floor per host this is a couple
         * of minutes of work, and the backlog drains over several cycles rather than one
         * long job holding a lease while a slow host thinks about it.
         */
        const val ARTICLES_PER_JOB = 20

        /** Longer than a feed job's: 20 articles x a rate-limited fetch each. */
        const val LEASE_SECONDS = 900L
    }
}
