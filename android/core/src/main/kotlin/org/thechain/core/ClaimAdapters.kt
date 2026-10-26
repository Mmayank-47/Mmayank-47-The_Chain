package org.thechain.core

import kotlin.math.floor

val CRITICAL_FEATURES = setOf("not_breathing", "severe_bleeding", "unconscious")

fun pyUnit(x: Any?, default: Double = 0.5): Double {
    val d = try {
        when (x) {
            is Boolean -> if (x) 1.0 else 0.0
            is Number -> x.toDouble()
            is String -> x.toDouble()
            else -> return default
        }
    } catch (_: Exception) {
        return default
    }
    return if (d.isFinite()) d.coerceIn(0.0, 1.0) else default
}

fun needOf(parsed: Map<String, Any?>): String? {
    val tags = stringSet(parsed["capability_tags"])
    val feats = stringSet(parsed["features"])
    if (feats.intersect(CRITICAL_FEATURES).isNotEmpty() || "medic" in tags) return "medic"
    return if ("boat" in tags) "boat" else null
}

private fun stringSet(value: Any?): Set<String> {
    val raw = value as? Iterable<*> ?: return emptySet()
    return raw.mapNotNull { it as? String }.toSet()
}

data class M3Claim(
    val id: String,
    val node: String,
    val src: String,
    val pos: Pair<Double, Double>,
    val t: Double,
    val urgency: Double = 0.5,
    val need: String? = null,
    val battery: Double = 0.5,
)

fun toM3(wc: WireClaim, battery: Double = 0.5): M3Claim =
    M3Claim(
        wc.id,
        wc.node,
        wc.src,
        wc.pos,
        wc.t,
        pyUnit(wc.parsed["urgency"]),
        needOf(wc.parsed),
        pyUnit(battery),
    )

/** Same grid as M9 disclosure. Exact coordinates stay off this string. */
fun cellOf(pos: Pair<Double, Double>, cellM: Double = 500.0): String {
    val x = floor(pos.first / cellM).toLong()
    val y = floor(pos.second / cellM).toLong()
    return "$x:$y"
}
