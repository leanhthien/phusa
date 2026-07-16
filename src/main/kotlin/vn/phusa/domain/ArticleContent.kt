package vn.phusa.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType
import java.time.Instant

/**
 * The wide side, 1:1 with [Article] and sharing its primary key (article_id is both
 * PK and FK). @MapsId means this row's id is *derived* from the associated Article's
 * id — no separate sequence, no way to have content without an article.
 *
 * Kept out of the feed path on purpose: a careless `SELECT *` here would drag tens of
 * KB of HTML into a feed response. The split makes that a structural impossibility.
 */
@Entity
@Table(name = "article_content")
class ArticleContent(
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id")
    var article: Article,

    @Column(name = "text_plain", nullable = false)
    var textPlain: String,

    var html: String? = null,

    @Column(nullable = false)
    var extractor: String = "jsoup-readability",
) {
    // Populated by @MapsId from article.id on persist; not set directly.
    @Id
    @Column(name = "article_id")
    var articleId: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "extracted_at", updatable = false)
    var extractedAt: Instant? = null
}
