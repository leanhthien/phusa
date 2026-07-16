package vn.phusa.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * The narrow "feed row" (see V1). Deliberately does NOT contain body text — that
 * lives in [ArticleContent], one join away, loaded only in the reader.
 *
 * Two mappings here are load-bearing and easy to get wrong:
 *
 *  - `url_hash` is `GENERATED ALWAYS AS (digest(canonical_url,'sha256')) STORED`.
 *    If Hibernate tries to write it, the INSERT fails ("cannot insert into column").
 *    @Generated(INSERT) tells Hibernate the DB produces it: excluded from INSERT,
 *    then read back (via RETURNING) so the in-memory entity has the hash afterwards.
 *
 *  - `search_tsv` (a trigger-maintained tsvector, added in V2) is intentionally
 *    NOT mapped. Hibernate `validate` only checks that *mapped* columns exist, so
 *    an unmapped column is fine — and it keeps a raw tsvector type out of the model.
 *    Query it via native SQL when search lands (Phase 3).
 */
@Entity
@Table(name = "article")
class Article(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    var source: Source,

    @Column(name = "canonical_url", nullable = false)
    var canonicalUrl: String,

    @Column(nullable = false)
    var title: String,

    @Column(name = "published_at", nullable = false)
    var publishedAt: Instant,

    // Raw FK columns, not associations: Author isn't modelled yet, and a self-FK to
    // the "original" of a duplicate is only set by the dedup pass (Phase 1). Promote
    // to @ManyToOne when something actually needs to navigate them.
    @Column(name = "author_id")
    var authorId: Long? = null,

    @Column(name = "duplicate_of_id")
    var duplicateOfId: Long? = null,

    var summary: String? = null,

    @Column(name = "image_url")
    var imageUrl: String? = null,

    // CHAR(2) / bpchar in the DB — declare the CHAR jdbc type (see Source.language).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    var language: String? = null,

    @Column(name = "word_count")
    var wordCount: Int? = null,

    @Column(name = "content_hash")
    var contentHash: ByteArray? = null,

    var simhash: Long? = null,

    // TEXT + CHECK (discovered|fetching|extracted|published|failed|duplicate).
    // See the note on Source.kind for why this is a String, not an @Enumerated.
    @Column(nullable = false)
    var status: String = "discovered",

    // Lazy, shared-PK 1:1. The feed query never touches this; the reader does.
    @OneToOne(
        mappedBy = "article",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    var content: ArticleContent? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "url_hash", updatable = false)
    var urlHash: ByteArray? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "discovered_at", updatable = false)
    var discoveredAt: Instant? = null

    // Optimistic locking. Enrichment consumers (summariser, tagger) race on the same
    // row; @Version makes the loser retry instead of silently clobbering (Phase 4).
    @Version
    var version: Int = 0

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null

    @Generated(event = [EventType.INSERT, EventType.UPDATE])
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}
