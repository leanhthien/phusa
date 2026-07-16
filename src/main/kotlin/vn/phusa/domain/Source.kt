package vn.phusa.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * One row per feed / site we ingest from. Crawl policy lives in the DB (see V1)
 * so the admin UI can tune it without a redeploy.
 *
 * Columns that carry a DB default AND are meant to be app-controlled (enabled,
 * intervals, next_crawl_at, config) are mapped as ordinary insertable fields with
 * Kotlin defaults that mirror the DDL defaults — Hibernate writes them, the app
 * owns them. Only columns the *database* generates (id, created_at, updated_at)
 * are read-only + @Generated so Hibernate reads them back instead of writing them.
 */
@Entity
@Table(name = "source")
class Source(
    @Column(nullable = false)
    var slug: String,

    @Column(nullable = false)
    var name: String,

    @Column(name = "homepage_url", nullable = false)
    var homepageUrl: String,

    // TEXT + CHECK (rss|atom|html|api) in the DB. Kept as String, not @Enumerated:
    // the CHECK is the source of truth, and the DB tokens are lowercase while a
    // Kotlin enum's names are upper — bridging them cleanly needs an
    // AttributeConverter. Add that the day the service layer branches on `kind`.
    @Column(nullable = false)
    var kind: String,

    @Column(name = "feed_url")
    var feedUrl: String? = null,

    // CHAR(2) / bpchar in the DB (ISO 639-1 code). Must declare the CHAR jdbc type
    // or `validate` rejects it against Hibernate's default varchar mapping.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    var language: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "crawl_interval_sec", nullable = false)
    var crawlIntervalSec: Int = 900,

    @Column(name = "rate_limit_per_min", nullable = false)
    var rateLimitPerMin: Int = 20,

    @Column(name = "next_crawl_at", nullable = false)
    var nextCrawlAt: Instant = Instant.now(),

    @Column(name = "last_crawled_at")
    var lastCrawledAt: Instant? = null,

    @Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,

    @Column(name = "http_etag")
    var httpEtag: String? = null,

    @Column(name = "http_last_modified")
    var httpLastModified: String? = null,

    // JSONB. @JdbcTypeCode(JSON) binds the String straight to jsonb. Per-source
    // crawl config (selectors, api params) whose shape genuinely varies.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var config: String = "{}",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null

    // Trigger-maintained (set_updated_at BEFORE UPDATE). Regenerated on every write,
    // so Hibernate reads it back after INSERT and UPDATE rather than writing it.
    @Generated(event = [EventType.INSERT, EventType.UPDATE])
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}
