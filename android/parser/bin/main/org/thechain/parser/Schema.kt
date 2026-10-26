package org.thechain.parser

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

/** Kinds a claim may take. Order matches the prompt schema. */
val KINDS: List<String> = listOf("need", "offer", "unknown")

/** Languages a claim may take. Order matches the prompt schema. */
val LANGS: List<String> = listOf("en", "hi", "mr", "mixed", "unknown")

/** START-style triage features. Order is the prompt order, not sorted urgency order. */
val FEATURES: List<String> = listOf(
    "not_breathing",
    "severe_bleeding",
    "unconscious",
    "trapped",
    "not_walking",
    "elderly",
    "child",
    "pregnant",
    "medication_dependent",
)

val FEATURE_WEIGHT: Map<String, Double> = mapOf(
    "not_breathing" to 0.50,
    "severe_bleeding" to 0.40,
    "unconscious" to 0.40,
    "trapped" to 0.25,
    "not_walking" to 0.15,
    "elderly" to 0.10,
    "child" to 0.15,
    "pregnant" to 0.15,
    "medication_dependent" to 0.20,
)

val BASE_URGENCY: Map<String, Double> = mapOf(
    "need" to 0.30,
    "unknown" to 0.20,
    "offer" to 0.0,
)

const val MAX_TAGS: Int = 8
const val MAX_LOC: Int = 80
const val MAX_QTY: Int = 10_000

private val KIND_SET = KINDS.toSet()
private val LANG_SET = LANGS.toSet()
private val FEATURE_SET = FEATURES.toSet()
private val KNOWN_TAG = Regex("^[a-z][a-z0-9_]{0,31}$")

class SchemaError(message: String) : IllegalArgumentException(message)

/**
 * CPython `round(x, ndigits)` for a finite double: correctly round to `ndigits`
 * decimal places, half to even, then parse that decimal back to a double.
 */
internal fun pythonRound(x: Double, ndigits: Int): Double {
    if (!x.isFinite()) return x
    return BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).toDouble()
}

/** Noisy-OR of feature weights over a base. Deterministic, monotone, never above 1. */
fun urgencyFromFeatures(kind: String, features: Iterable<String>): Double {
    if (kind == "offer") return 0.0
    var miss = 1.0 - (BASE_URGENCY[kind] ?: 0.2)
    for (f in features) miss *= 1.0 - (FEATURE_WEIGHT[f] ?: 0.0)
    return pythonRound(1.0 - miss, 4)
}

data class ParsedClaim(
    val kind: String,
    val capabilityTags: List<String>,
    val quantity: Int?,
    val urgency: Double,
    val features: List<String>,
    val locationText: String,
    val language: String,
    val confidence: Double,
    val source: String,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to kind,
        "capability_tags" to capabilityTags,
        "quantity" to quantity,
        "urgency" to urgency,
        "features" to features,
        "location_text" to locationText,
        "language" to language,
        "confidence" to confidence,
        "source" to source,
    )

    companion object {
        fun build(
            kind: String,
            tags: List<String>,
            quantity: Int?,
            features: Iterable<String>,
            locationText: String,
            language: String,
            confidence: Double,
            source: String,
        ): ParsedClaim {
            val kindNorm = if (kind in KIND_SET) kind else "unknown"
            val feats = if (kindNorm == "offer") {
                emptyList()
            } else {
                features.filter { it in FEATURE_SET }.toSet().sorted()
            }
            return ParsedClaim(
                kindNorm,
                tags.take(MAX_TAGS),
                quantity,
                urgencyFromFeatures(kindNorm, feats),
                feats,
                locationText.takeCodePoints(MAX_LOC),
                if (language in LANG_SET) language else "unknown",
                pythonRound(min(1.0, max(0.0, confidence)), 4),
                source,
            )
        }

        fun fromJson(j: Map<String, Any?>): ParsedClaim {
            val p = validate(j)
            val source = j["source"] as? String ?: p.source
            return build(
                p.kind,
                p.capabilityTags,
                p.quantity,
                p.features,
                p.locationText,
                p.language,
                p.confidence,
                source,
            )
        }
    }
}

private fun cleanText(s: Any?, cap: Int): String {
    if (s !is String) return ""
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val c = ch.code
        if (c <= 0x1F || c == 0x7F) sb.append(' ') else sb.append(ch)
    }
    return pySplit(sb.toString()).joinToString(" ").takeCodePoints(cap)
}

private fun isUnknownTag(t: String): Boolean {
    if (!t.startsWith("unknown:")) return false
    val rest = t.substring("unknown:".length)
    val n = pyLen(rest)
    if (n !in 1..40) return false
    var i = 0
    while (i < rest.length) {
        val cp = rest.codePointAt(i)
        if (!isTokenChar(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}

/** Strict validation of untrusted model output. Raises [SchemaError]; never trusts urgency. */
fun validate(obj: Any?): ParsedClaim {
    if (obj !is Map<*, *>) throw SchemaError("not an object")
    val kind = obj["kind"]
    if (kind !in KIND_SET) throw SchemaError("bad kind $kind")
    val conf = obj["confidence"]
    if (conf is Boolean || conf !is Number || !conf.toDouble().isFinite()) {
        throw SchemaError("bad confidence")
    }
    val rawTags = if (obj.containsKey("capability_tags")) obj["capability_tags"] else emptyList<Any>()
    if (rawTags !is List<*>) throw SchemaError("capability_tags must be a list")
    val tags = ArrayList<String>()
    for (item in rawTags) {
        if (item !is String) continue
        val t = pyStrip(item).lowercase(java.util.Locale.ROOT)
        if ((KNOWN_TAG.matches(t) || isUnknownTag(t)) && t !in tags) tags.add(t)
    }
    val quantity = quantize(obj["quantity"])
    val rawFeats = if (obj.containsKey("features")) obj["features"] else emptyList<Any>()
    val feats = if (rawFeats is List<*>) {
        rawFeats.mapNotNull { it as? String }.filter { it in FEATURE_SET }
    } else {
        emptyList()
    }
    val langRaw = if (obj.containsKey("language")) obj["language"] else "unknown"
    val lang = if (langRaw is String && langRaw in LANG_SET) langRaw else "unknown"
    return ParsedClaim.build(
        kind as String,
        tags,
        quantity,
        feats,
        cleanText(obj["location_text"], MAX_LOC),
        lang,
        conf.toDouble(),
        "llm",
    )
}

private fun quantize(q: Any?): Int? {
    if (q == null || q is Boolean || q !is Number) return null
    val d = q.toDouble()
    if (!d.isFinite() || d < 1.0) return null
    val truncated = if (d >= MAX_QTY) MAX_QTY else d.toInt()
    return min(truncated, MAX_QTY)
}
