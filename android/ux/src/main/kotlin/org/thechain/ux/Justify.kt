package org.thechain.ux

/**
 * Template-grounded justification. Safety percents are floored. Every prompt ends with an abort line.
 */
const val CORROB_LLR = 1.5

private val PLACE = Regex("(?U)[^\\w\\s.\\-\u0900-\u097F]")
private val MANIPULATIVE = Regex(
    "urgent|hurry|immediately|right now|asap|last chance|only you|must|!|जल्दी|तुरंत|लवकर|ताबडतोब",
    RegexOption.IGNORE_CASE,
)
private val NUM = Regex("(?U)\\d+(?:\\.\\d+)?")
private val HOLE = Regex("\\{([A-Za-z0-9_]+)}")

data class Facts(
    val tags: List<String>,
    val distM: Double,
    val pSafe: Double,
    val pReach: Double,
    val battery: Double,
    val routeVia: String = "",
    val flooded: List<String> = emptyList(),
    val worstRisk: Double = 0.0,
    val quantity: Int? = null,
    val place: String = "",
    val radiusM: Double? = null,
    val verifiers: Int = 0,
    val skill: String? = null,
    val nearest: Boolean = false,
    val gate: String = "dispatch",
    val abortWaterCm: Int = 15,
    val abortBatteryPct: Int = 15,
)

data class Justification(
    val kind: String,
    val lang: String,
    val text: String,
    val lines: List<String>,
    val risk: String,
    val abort: String,
    val grounding: Map<String, Any?>,
)

fun verifiersFromParts(parts: Map<String, Double>): Int =
    maxOf(0, pyRoundToInt((parts["corroboration"] ?: 0.0) / CORROB_LLR))

fun cleanPlace(s: String, cap: Int = 40): String =
    PLACE.replace(s, " ").split(Regex("(?U)\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(cap)

fun lint(text: String): List<String> =
    MANIPULATIVE.findAll(text).map { it.value.lowercase() }.toSet().sorted()

fun riskLevel(f: Facts): String {
    if (f.pSafe < 0.7 || f.worstRisk >= 0.5 || f.pReach < 0.9) return "high"
    if (f.pSafe < 0.9 || f.worstRisk >= 0.2 || f.battery - f.abortBatteryPct / 100.0 < 0.2) return "moderate"
    return "low"
}

fun justify(f: Facts, lang: String = "en"): Justification? {
    if (f.gate != "dispatch" && f.gate != "scout") return null
    val language = if (lang in LANGS) lang else "en"
    val t = T.getValue(language)
    val what = f.tags.take(2).joinToString(", ") { label(language, it) }.ifEmpty { label(language, "help") }
    val grounding = linkedMapOf<String, Any?>("dist_m" to f.distM, "tags" to f.tags)
    val lines = mutableListOf(t.getValue("need").pyFormat(mapOf("what" to what, "dist" to fmtDist(language, f.distM))))
    val place = cleanPlace(f.place)
    if (place.isNotEmpty()) {
        lines.add(t.getValue("near").pyFormat(mapOf("place" to place)))
        grounding["place"] = place
    }
    if (f.gate == "dispatch") {
        lines.add(
            if (f.verifiers >= 1) {
                t.getValue("verified").pyFormat(mapOf("n" to f.verifiers.toString()))
            } else {
                t.getValue("unverified")
            },
        )
        grounding["verifiers"] = f.verifiers
        if (f.skill != null && f.nearest) {
            lines.add(t.getValue("skill").pyFormat(mapOf("skill" to label(language, f.skill))))
        }
    } else {
        lines.add(t.getValue("scout"))
    }
    val risky = f.pSafe < 0.9 || f.worstRisk >= 0.2
    val routeKey = if (risky) "route_risk" else "route_ok"
    lines.add(
        t.getValue(routeKey).pyFormat(
            mapOf("via" to cleanPlace(f.routeVia).ifEmpty { "?" }, "p" to pct(f.pSafe).toString()),
        ),
    )
    grounding["route_via"] = f.routeVia
    grounding["p_safe"] = pct(f.pSafe)
    val names = f.flooded.map { cleanPlace(it) }.filter { it.isNotEmpty() }.joinToString(", ")
    if (names.isNotEmpty()) lines.add(t.getValue("avoid").pyFormat(mapOf("names" to names)))
    if (f.gate == "dispatch") {
        val batKey = if (f.pReach >= 0.9) "bat_ok" else "bat_low"
        lines.add(t.getValue(batKey).pyFormat(mapOf("pct" to pct(f.battery).toString())))
        grounding["battery"] = pct(f.battery)
    }
    if (f.radiusM != null) {
        val rounded = pyRoundToInt(f.radiusM / 10.0) * 10
        val shown = if (rounded == 0) 10 else rounded
        lines.add(t.getValue("radius").pyFormat(mapOf("r" to shown.toString())))
    }
    val cond = t.getValue("cond").pyFormat(mapOf("cm" to f.abortWaterCm.toString(), "pct" to f.abortBatteryPct.toString()))
    val abort = t.getValue("abort").pyFormat(mapOf("cond" to cond))
    lines.add(abort)
    val text = lines.joinToString(" ")
    val bad = lint(text)
    if (bad.isNotEmpty()) throw IllegalArgumentException("manipulative wording in template output: $bad")
    val kind = if (f.gate == "scout") "scout" else "dispatch"
    return Justification(kind, language, text, lines.toList(), riskLevel(f), abort, grounding)
}

fun constrainedPolish(
    j: Justification,
    generate: ((String) -> String)? = null,
    maxLen: Int = 400,
): Justification {
    if (generate == null) return j
    val prompt = "Rewrite as one short, calm paragraph. Keep every number and place name exactly. " +
        "Add no facts, no pressure words.\n" + j.text
    val out = try {
        generate(prompt).split(Regex("(?U)\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    } catch (_: Exception) {
        return j
    }
    val abortCore = j.abort.trimEnd { it == '.' || it == '।' }
    if (out.isEmpty() || out.length > maxLen || lint(out).isNotEmpty() ||
        numbersIn(out) != numbersIn(j.text) || abortCore !in out
    ) {
        return j
    }
    return j.copy(text = out)
}

private fun pct(x: Double): Int = (x.coerceIn(0.0, 1.0) * 100.0).toInt()

private fun numbersIn(text: String): List<String> = NUM.findAll(text).map { it.value }.toList().sorted()

private fun String.pyFormat(values: Map<String, String>): String =
    HOLE.replace(this) { match ->
        values[match.groupValues[1]] ?: error("missing format key ${match.groupValues[1]}")
    }
