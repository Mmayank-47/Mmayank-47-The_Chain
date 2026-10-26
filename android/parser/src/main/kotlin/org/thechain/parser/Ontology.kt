package org.thechain.parser

/**
 * Open capability ontology: English / Hinglish / Hindi / Marathi surface forms to a capability tag.
 * Exact phrase match first, then a string-similarity fallback for typos.
 */
data class Mention(val tag: String, val start: Int, val end: Int)

class Ontology(
    extra: Map<String, List<String>>? = null,
    val fuzzy: Double = 0.82,
    similarity: ((String, String) -> Double)? = null,
) {
    private val similarityFn: (String, String) -> Double =
        similarity ?: { a, b -> SequenceMatcher.ratio(a, b) }
    private val phrases = HashMap<String, String>()
    private val singles = ArrayList<Pair<String, String>>()
    private var maxLen = 1
    val tags: Set<String>

    init {
        val merged = LinkedHashMap<String, List<String>>()
        merged.putAll(CAPS)
        if (extra != null) {
            for ((tag, forms) in extra) merged[tag] = forms
        }
        for ((tag, forms) in merged) {
            for (form in forms) {
                val toks = tokenize(form)
                if (toks.isEmpty()) continue
                phrases[phraseKey(toks)] = tag
                if (toks.size > maxLen) maxLen = toks.size
                if (toks.size == 1) singles.add(toks[0] to tag)
            }
        }
        tags = phrases.values.toSet()
    }

    /** Longest exact phrase starting at token i, as (tag, length). */
    fun matchAt(toks: List<String>, i: Int): Pair<String, Int>? {
        val limit = minOf(maxLen, toks.size - i)
        for (n in limit downTo 1) {
            val tag = phrases[phraseKey(toks, i, n)]
            if (tag != null) return tag to n
        }
        return null
    }

    fun fuzzyTag(tok: String): String? {
        if (pyLen(tok) < 5 || pyIsDigit(tok)) return null
        var best = 0.0
        var tag: String? = null
        val head = tok.codePointAt(0)
        val len = pyLen(tok)
        for ((form, t) in singles) {
            if (form.codePointAt(0) != head || kotlin.math.abs(pyLen(form) - len) > 2) continue
            val score = similarityFn(tok, form)
            if (score > best) {
                best = score
                tag = t
            }
        }
        return if (best >= fuzzy) tag else null
    }

    /** Non-overlapping mentions as (tag, start, end exclusive). */
    fun find(toks: List<String>): List<Mention> {
        val out = ArrayList<Mention>()
        var i = 0
        while (i < toks.size) {
            val match = matchAt(toks, i)
            if (match != null) {
                out.add(Mention(match.first, i, i + match.second))
                i += match.second
                continue
            }
            val fuzzy = fuzzyTag(toks[i])
            if (fuzzy != null) out.add(Mention(fuzzy, i, i + 1))
            i += 1
        }
        return out
    }

    /** Map a free-text capability (for example from a model) to a tag, or null. */
    fun mapPhrase(phrase: String): String? {
        for (mention in find(tokenize(phrase))) return mention.tag
        return null
    }
}
