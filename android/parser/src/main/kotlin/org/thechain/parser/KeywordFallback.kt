package org.thechain.parser

/**
 * Deterministic keyword parser: the floor M2 never drops below.
 * Confidence is capped at 0.85 so a model-backed parse can always outrank it.
 */
const val MAX_TOKENS: Int = 200

private val KE_NEXT = setOf("paas", "pass", "nazdeek", "samne")

private class PhraseIndex(val map: Map<String, String>, val max: Int)

private fun indexPhrases(table: Map<String, String>): PhraseIndex {
    val map = HashMap<String, String>()
    var mx = 1
    for ((phrase, value) in table) {
        val toks = tokenize(phrase)
        if (toks.isEmpty()) continue
        map[phraseKey(toks)] = value
        if (toks.size > mx) mx = toks.size
    }
    return PhraseIndex(map, mx)
}

private val NEED_INDEX = indexPhrases(NEED_CUES)
private val OFFER_INDEX = indexPhrases(OFFER_CUES)
private val FEAT_INDEX = run {
    val map = HashMap<String, String>()
    var mx = 1
    for ((feature, forms) in FEATURE_PHRASES) {
        for (form in forms) {
            val toks = tokenize(form)
            if (toks.isEmpty()) continue
            map[phraseKey(toks)] = feature
            if (toks.size > mx) mx = toks.size
        }
    }
    PhraseIndex(map, mx)
}
private val NEG: Set<String> = NEGATIONS.map { tokenize(it).first() }.toSet()
private val NUM: Map<String, Int> = run {
    val out = LinkedHashMap<String, Int>()
    for ((word, value) in NUMBERS) {
        val toks = tokenize(word)
        if (toks.isNotEmpty()) out[toks[0]] = value
    }
    out
}
private val PEOPLE_TOK: Set<String> = PEOPLE.map { tokenize(it).first() }.toSet()
private val DEFAULT_ONTOLOGY = Ontology()

private data class Hit(val start: Int, val end: Int, val value: String)

private fun scan(toks: List<String>, index: PhraseIndex): List<Hit> {
    val out = ArrayList<Hit>()
    var i = 0
    while (i < toks.size) {
        val limit = minOf(index.max, toks.size - i)
        for (n in limit downTo 1) {
            val value = index.map[phraseKey(toks, i, n)]
            if (value != null) {
                out.add(Hit(i, i + n, value))
                i += n - 1
                break
            }
        }
        i += 1
    }
    return out
}

private fun numberOf(tok: String): Int? {
    if (pyIsDigit(tok) && pyLen(tok) <= 4) {
        val n = pyDecimalInt(tok)
        return if (n > 0) n else null
    }
    return NUM[tok]
}

private fun quantity(toks: List<String>, anchors: List<Int>): Int? {
    for (anchor in anchors) {
        for (j in intArrayOf(anchor - 1, anchor - 2)) {
            if (j >= 0) {
                val n = numberOf(toks[j])
                if (n != null) return n
            }
        }
    }
    return null
}

private fun unknownTag(toks: List<String>, cues: List<Hit>, taken: Set<Int>): String? {
    for (cue in cues) {
        val got = ArrayList<String>()
        if (cue.value == "a") {
            val end = minOf(toks.size, cue.end + 5)
            for (j in cue.end until end) {
                if (takeUnknown(toks, j, taken, got)) break
            }
        } else {
            val stop = maxOf(-1, cue.start - 5)
            var j = cue.start - 1
            while (j > stop) {
                if (takeUnknown(toks, j, taken, got)) break
                j--
            }
        }
        if (got.isNotEmpty()) {
            val parts = if (cue.value == "a") got else got.reversed()
            return "unknown:" + parts.joinToString("_")
        }
    }
    return null
}

/** @return true when the scan should stop (two tokens, or a gap after a token). */
private fun takeUnknown(toks: List<String>, j: Int, taken: Set<Int>, got: MutableList<String>): Boolean {
    val t = toks[j]
    val ok = j !in taken && t !in STOP && numberOf(t) == null && pyLen(t) >= 3 && !pyIsDigit(t)
    if (ok) {
        got.add(t)
        return got.size == 2
    }
    return got.isNotEmpty()
}

private fun location(toks: List<String>, taken: Set<Int>): String {
    fun ok(j: Int) = j !in taken && toks[j] !in STOP
    val cands = ArrayList<List<String>>()
    for (i in toks.indices) {
        val t = toks[i]
        if (t in PREP_EN) {
            var j = i + 1
            while (j < toks.size && toks[j] in ARTICLES) j++
            val got = ArrayList<String>()
            while (j < toks.size && got.size < 4 && ok(j)) {
                got.add(toks[j])
                j++
            }
            cands.add(got)
        }
        val isPost = t in POST || (t == "ke" && i + 1 < toks.size && toks[i + 1] in KE_NEXT)
        if (isPost) {
            val got = ArrayList<String>()
            var j = i - 1
            while (j >= 0 && got.size < 3 && ok(j)) {
                got.add(toks[j])
                j--
            }
            cands.add(got.reversed())
        }
    }
    val filtered = cands.filter { it.isNotEmpty() && it.any { token -> !pyIsDigit(token) } }
    if (filtered.isEmpty()) return ""
    var best = filtered[0]
    for (cand in filtered) if (cand.size > best.size) best = cand
    return best.joinToString(" ")
}

fun detectLanguage(toks: List<String>): String {
    if (toks.isEmpty()) return "unknown"
    var hi = 0
    var mr = 0
    var en = 0
    for (t in toks) {
        if (t in HI_MARK) hi++
        if (t in MR_MARK) mr++
        if (t in EN_MARK) en++
    }
    val hasDev = toks.any { tokenHasDev(it) }
    val hasLat = toks.any { tokenHasLatin(it) }
    if (hasDev && hasLat) return "mixed"
    val indic = hi + mr
    if (indic != 0 && en != 0) return "mixed"
    if (indic != 0 || hasDev) return if (mr > hi) "mr" else "hi"
    return "en"
}

private fun tokenHasDev(tok: String): Boolean {
    var i = 0
    while (i < tok.length) {
        val cp = tok.codePointAt(i)
        if (cp in 0x0900..0x097F) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun tokenHasLatin(tok: String): Boolean {
    var i = 0
    while (i < tok.length) {
        val cp = tok.codePointAt(i)
        if (cp <= 127 && isPyAlpha(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

fun parse(text: String, ontology: Ontology? = null): ParsedClaim {
    val onto = ontology ?: DEFAULT_ONTOLOGY
    val toks = tokenize(text).take(MAX_TOKENS)
    val lang = detectLanguage(toks)
    if (toks.isEmpty()) {
        return ParsedClaim.build("unknown", emptyList(), null, emptyList(), "", lang, 0.0, "keyword")
    }
    val featHits = scan(toks, FEAT_INDEX)
    val featSpans = HashSet<Int>()
    for (hit in featHits) for (k in hit.start until hit.end) featSpans.add(k)
    val caps = onto.find(toks).filter { mention ->
        mention.tag in KEEP_BOTH || (mention.start until mention.end).none { it in featSpans }
    }
    val need = scan(toks, NEED_INDEX)
    val offer = scan(toks, OFFER_INDEX)
    val feats = featHits.map { it.value }.toSet().sorted()
    val kind = when {
        need.isNotEmpty() -> "need"
        offer.isNotEmpty() -> if (toks.any { it in NEG }) "need" else "offer"
        feats.isNotEmpty() -> "need"
        else -> "unknown"
    }
    val tags = ArrayList<String>()
    for (mention in caps) if (mention.tag !in tags) tags.add(mention.tag)
    val taken = HashSet<Int>()
    for (mention in caps) for (k in mention.start until mention.end) taken.add(k)
    taken.addAll(featSpans)
    for (hit in need) for (k in hit.start until hit.end) taken.add(k)
    for (hit in offer) for (k in hit.start until hit.end) taken.add(k)
    if (tags.isEmpty() && kind != "unknown") {
        val cues = (need + offer).sortedWith(compareBy({ it.start }, { it.end }, { it.value }))
        val unknown = unknownTag(toks, cues, taken)
        if (unknown != null) tags.add(unknown)
    }
    val anchors = ArrayList<Int>()
    for (mention in caps) anchors.add(mention.start)
    for (i in toks.indices) if (toks[i] in PEOPLE_TOK) anchors.add(i)
    anchors.sort()
    val qty = quantity(toks, anchors)
    val loc = location(toks, taken)
    var conf = 0.35
    if (kind != "unknown") conf += 0.20
    if (tags.any { !it.startsWith("unknown:") }) conf += 0.20
    if (loc.isNotEmpty()) conf += 0.10
    if (qty != null || feats.isNotEmpty()) conf += 0.10
    return ParsedClaim.build(kind, tags, qty, feats, loc, lang, minOf(conf, 0.85), "keyword")
}
