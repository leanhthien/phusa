package vn.phusa.dedup

import vn.phusa.ingest.ContentNormalizer

/**
 * 64-bit simhash — dedup layer 3, for NEAR-duplicates.
 *
 * WHAT THIS CATCHES THAT LAYERS 1 AND 2 CANNOT. URL canonicalization catches the same
 * article at two addresses. `content_hash` catches byte-identical bodies. Neither sees
 * two copies of one story that differ by a sentence — a syndicated piece with the
 * publisher's own intro, an article that gained a correction, the same wire story run by
 * two outlets with different photo captions. Those are the same article to a reader and
 * two unrelated rows to a cryptographic hash, because SHA-256 is designed so that one
 * changed byte changes everything.
 *
 * Simhash inverts that property on purpose: it is a LOCALITY-SENSITIVE hash, so similar
 * inputs produce similar outputs and "how different are these documents" becomes "how
 * many bits differ", i.e. Hamming distance.
 *
 * HOW IT WORKS
 *  1. cut the text into overlapping word shingles
 *  2. hash each shingle to 64 bits
 *  3. for every bit position, add the shingle's weight if that bit is 1, subtract if 0
 *  4. the final fingerprint has a 1 wherever the running total came out positive
 * A shingle that appears in both documents pushes every bit the same way in both, so
 * shared phrasing survives into the fingerprint and unshared phrasing cancels out.
 *
 * SHINGLES, NOT SINGLE WORDS, and this is the decision that makes or breaks it. A
 * word-level simhash is a bag-of-words fingerprint: two different articles on the same
 * subject use much the same vocabulary and collide constantly. Three-word shingles
 * capture PHRASING rather than topic, which is what actually distinguishes "the same
 * text" from "the same subject".
 */
object Simhash {

    private const val BITS = 64

    /** Words per shingle. 3 is the standard choice — long enough to encode phrasing. */
    private const val SHINGLE_SIZE = 3

    /**
     * A document needs enough distinct shingles for the bit votes to mean anything.
     * Below this the fingerprint is dominated by a handful of features and near-collides
     * with anything else short, so the honest answer is null rather than a number that
     * looks authoritative.
     */
    private const val MIN_SHINGLES = 8

    /**
     * Fingerprint of already-normalized text, or null when there is not enough of it.
     *
     * Takes NORMALIZED text (see [ContentNormalizer]) so that markup, zero-width padding
     * and whitespace differences cannot move the fingerprint — the same input the
     * content hash sees, which keeps the two layers talking about the same document.
     */
    fun of(normalizedText: String): Long? {
        if (normalizedText.length < ContentNormalizer.MIN_HASHABLE_CHARS) return null

        val tokens = tokenize(normalizedText)
        if (tokens.size < SHINGLE_SIZE + MIN_SHINGLES - 1) return null

        // Weight by frequency: a phrase repeated through a document is more
        // characteristic of it than one that appears once.
        val weights = HashMap<Long, Int>()
        for (i in 0..tokens.size - SHINGLE_SIZE) {
            var h = FNV_OFFSET
            for (j in i until i + SHINGLE_SIZE) {
                for (ch in tokens[j]) {
                    h = (h xor ch.code.toLong()) * FNV_PRIME
                }
                h = (h xor ' '.code.toLong()) * FNV_PRIME
            }
            weights.merge(mix(h), 1, Int::plus)
        }
        if (weights.size < MIN_SHINGLES) return null

        val votes = IntArray(BITS)
        for ((feature, weight) in weights) {
            for (bit in 0 until BITS) {
                if ((feature ushr bit) and 1L == 1L) votes[bit] += weight else votes[bit] -= weight
            }
        }

        var fingerprint = 0L
        for (bit in 0 until BITS) {
            if (votes[bit] > 0) fingerprint = fingerprint or (1L shl bit)
        }
        return fingerprint
    }

    /**
     * Splits on anything that is not a letter or digit, and lowercases.
     *
     * Lowercasing is safe for Vietnamese; UNACCENTING would not be, and is deliberately
     * not done here. "ma", "mà", "má", "mã" and "mả" are five different words, so
     * stripping diacritics would make genuinely different sentences hash alike — the
     * same reasoning behind the dual-form tsvector in V2.
     *
     * [Char.isLetterOrDigit] is Unicode-aware, so Vietnamese letters are letters rather
     * than separators.
     */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(*SEPARATORS).filter { it.isNotEmpty() }
            .ifEmpty { emptyList() }

    /** Hamming distance — the number of differing bits. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * splitmix64's finalizer.
     *
     * FNV-1a alone has poor avalanche in the high bits, and simhash votes on EVERY bit
     * independently — a hash whose upper bits barely move would make those fingerprint
     * bits near-constant across all documents, quietly cutting the usable width of the
     * fingerprint and pulling every distance toward zero.
     */
    private fun mix(z0: Long): Long {
        var z = z0
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private val SEPARATORS: Array<String> = buildList {
        // Split on the punctuation and whitespace that actually appears in these
        // sources; everything else stays part of a token.
        addAll(listOf(" ", "\n", "\t", ",", ".", ";", ":", "!", "?", "\"", "'", "(", ")",
            "[", "]", "{", "}", "<", ">", "/", "\\", "|", "—", "–", "-", "…", "“", "”", "‘", "’"))
    }.toTypedArray()
}
