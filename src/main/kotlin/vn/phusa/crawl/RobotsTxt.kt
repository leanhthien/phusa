package vn.phusa.crawl

/**
 * A parsed robots.txt, reduced to the one group that applies to us.
 *
 * [crawlDelaySec] is null when the host expressed no preference. It is NOT defaulted
 * here — "the host asked for 30s" and "the host said nothing" are different facts, and
 * only the caller knows what its own floor is.
 */
data class RobotsRules(
    val allows: List<String>,
    val disallows: List<String>,
    val crawlDelaySec: Double?,
    /** Which `User-agent:` group matched, for logging. `*` or the literal token. */
    val matchedAgent: String,
) {
    /**
     * RFC 9309 §2.2.2: the most specific rule wins, where specificity is the length of
     * the matched pattern; Allow wins an exact tie.
     *
     * This ordering is not decoration. Sites routinely write
     *     Disallow: /
     *     Allow: /blog/
     * and evaluating in file order, or letting Disallow win by default, would block the
     * entire site — which is precisely the failure mode that makes people give up and
     * ignore robots.txt altogether.
     */
    fun isAllowed(path: String): Boolean {
        val p = path.ifEmpty { "/" }
        val allow = allows.filter { pathMatches(it, p) }.maxOfOrNull { it.length } ?: -1
        val disallow = disallows.filter { pathMatches(it, p) }.maxOfOrNull { it.length } ?: -1
        return allow >= disallow
    }

    companion object {
        /** No rules at all — used for 4xx, which RFC 9309 §2.3.1.3 defines as "allow". */
        val ALLOW_ALL = RobotsRules(emptyList(), emptyList(), null, "*")

        /**
         * Used when robots.txt could not be retrieved because of a SERVER error.
         * RFC 9309 §2.3.1.4 is explicit that this means "disallow", not "allow" — the
         * host's wishes are unknown, and guessing in our own favour is the whole
         * problem. Also the state a host is in when it is struggling, which is exactly
         * when hammering it is worst.
         */
        val DISALLOW_ALL = RobotsRules(emptyList(), listOf("/"), null, "*")

        /**
         * Wildcard path matching. `*` is any run of characters, `$` anchors the end.
         * Both are used widely enough that ignoring them is not an option — dev.to has
         * 16 such lines, and viblo.asia blocks the patterns slash-star-dot-json and
         * slash-star-dot-xml.
         *
         * (Those two patterns are spelled out rather than written literally because
         * Kotlin block comments NEST, unlike Java's: an embedded slash-star opens a
         * comment that never closes, and the compiler then reports a cascade of
         * unresolved references pointing anywhere but here.)
         */
        fun pathMatches(pattern: String, path: String): Boolean {
            if (pattern.isEmpty()) return false
            val anchored = pattern.endsWith("$")
            val body = if (anchored) pattern.dropLast(1) else pattern
            val regex = buildString {
                append('^')
                for (part in body.split('*').map { Regex.escape(it) }) {
                    if (length > 1) append(".*")
                    append(part)
                }
                if (anchored) append('$')
            }
            return Regex(regex).containsMatchIn(path)
        }
    }
}

/**
 * RFC 9309 parser.
 *
 * Deliberately hand-written and small. crawler-commons pulls a transitive tree for one
 * file format, and the format is 30 lines of parsing — but 30 lines with real traps in
 * them, all of which are exercised by this project's own sources:
 *  - CONSECUTIVE `User-agent:` lines share one group. lobste.rs lists seven allowed
 *    bots this way before a single `Allow: /`. Treating each as its own group loses the
 *    rules entirely.
 *  - GROUP SELECTION is by most specific match, not first match. theverge.com has 106
 *    groups and vnexpress.net 54; picking the first one that matches `*` would ignore a
 *    more specific group later in the file.
 *  - `Disallow:` with an EMPTY value means allow everything, the opposite of
 *    `Disallow: /`. hnrss.org relies on exactly this.
 */
object RobotsTxtParser {

    /** RFC 9309 §2.5 caps the parsed size at 500 KiB. */
    const val MAX_BYTES = 500 * 1024

    fun parse(body: String, ourToken: String): RobotsRules {
        // agent token -> rules, built as we walk the file
        val groups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        var currentAgents = mutableListOf<String>()
        var lastLineWasAgent = false

        for (rawLine in body.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val field = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (field.isEmpty()) continue

            when (field) {
                "user-agent" -> {
                    // A new group starts only when the previous line was NOT an agent.
                    if (!lastLineWasAgent) currentAgents = mutableListOf()
                    currentAgents += value.lowercase()
                    groups.getOrPut(value.lowercase()) { mutableListOf() }
                    lastLineWasAgent = true
                }

                "allow", "disallow", "crawl-delay" -> {
                    lastLineWasAgent = false
                    for (agent in currentAgents) {
                        groups.getOrPut(agent) { mutableListOf() } += field to value
                    }
                }

                else -> lastLineWasAgent = false // sitemap, content-signal, unknown
            }
        }

        val token = ourToken.lowercase()
        // Most specific wins: an exact token match beats the `*` fallback. Exact rather
        // than prefix matching, or `User-agent: bot` would capture PhuSaBot.
        val matched = groups.keys.firstOrNull { it == token }
            ?: groups.keys.firstOrNull { it == "*" }
            ?: return RobotsRules.ALLOW_ALL

        val rules = groups[matched].orEmpty()
        return RobotsRules(
            // An empty `Disallow:` means "nothing is disallowed" — it must not become
            // the pattern "" and it must not become "/".
            allows = rules.filter { it.first == "allow" && it.second.isNotEmpty() }.map { it.second },
            disallows = rules.filter { it.first == "disallow" && it.second.isNotEmpty() }.map { it.second },
            crawlDelaySec = rules.lastOrNull { it.first == "crawl-delay" }?.second?.toDoubleOrNull(),
            matchedAgent = matched,
        )
    }
}
