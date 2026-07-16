package vn.phusa.feed

import java.time.Instant
import java.util.Base64

/**
 * Opaque pagination cursor: base64url of `<published_at>|<id>` — the exact
 * `(published_at, id)` of the last row on the page.
 *
 * "Opaque" is a deliberate API contract: clients pass it back verbatim and never
 * parse it, so we can change the keyset internals without breaking them. The full
 * ISO-8601 instant is encoded (not epoch millis) so it matches the timestamptz value
 * to the microsecond — the `<` boundary must be exact or keyset paging skips or
 * repeats rows. The delimiter is `|` because ISO-8601 already contains `:`.
 */
object FeedCursor {

    fun encode(publishedAt: Instant, id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$publishedAt|$id".toByteArray())

    /** @throws IllegalArgumentException if the token is malformed. */
    fun decode(raw: String): Pair<Instant, Long> {
        val decoded = String(Base64.getUrlDecoder().decode(raw))
        val sep = decoded.indexOf('|')
        require(sep > 0) { "malformed cursor" }
        return Instant.parse(decoded.substring(0, sep)) to decoded.substring(sep + 1).toLong()
    }
}
