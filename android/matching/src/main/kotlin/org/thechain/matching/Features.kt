package org.thechain.matching

import org.thechain.battery.urgency as batteryUrgency
import kotlin.math.min

/**
 * Text flags that say something definite. Unknown flags stay unset.
 * not_walking -> canWalk false, not_breathing -> breathing false,
 * unconscious -> obeysCommands false, severe_bleeding -> perfusionOk false.
 */
fun toTriage(features: Iterable<String>): Features {
    var canWalk: Boolean? = null
    var breathing: Boolean? = null
    var perfusionOk: Boolean? = null
    var obeysCommands: Boolean? = null
    for (flag in features) {
        when (flag) {
            "not_walking" -> canWalk = false
            "not_breathing" -> breathing = false
            "unconscious" -> obeysCommands = false
            "severe_bleeding" -> perfusionOk = false
        }
    }
    return Features(
        canWalk = canWalk,
        breathing = breathing,
        perfusionOk = perfusionOk,
        obeysCommands = obeysCommands,
    )
}

/**
 * Noisy-OR of case urgency and a capped battery term. Time-to-silence can lift a case that is
 * about to go dark, but at most [weight] of the way.
 */
fun combineUrgency(triageU: Double, ttsS: Double?, weight: Double = 0.3): Double {
    val u = triageU.coerceIn(0.0, 1.0)
    if (ttsS == null || u <= 0.0) return u
    return min(1.0, 1.0 - (1.0 - u) * (1.0 - weight * batteryUrgency(ttsS)))
}

/**
 * Category, urgency, survival gain. A text-only EXPECTANT (airway not confirmed clear) is
 * promoted to IMMEDIATE. UNKNOWN falls back to the parser's own urgency.
 */
fun claimScores(
    features: Iterable<String>,
    parsedUrgency: Double = 0.3,
    kind: String = "need",
    ttsS: Double? = null,
): Triple<Category, Double, Double> {
    if (kind == "offer") return Triple(Category.UNKNOWN, 0.0, 0.0)
    var (cat, u, g) = scores(toTriage(features))
    if (cat == Category.EXPECTANT) {
        cat = Category.IMMEDIATE
        val score = SCORES.getValue(cat)
        u = score.first
        g = score.second
    }
    if (cat == Category.UNKNOWN) u = parsedUrgency.coerceIn(0.0, 1.0)
    return Triple(cat, pythonRound(combineUrgency(u, ttsS), 4), g)
}
