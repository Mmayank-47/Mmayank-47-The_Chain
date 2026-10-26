package org.thechain.matching

import kotlin.math.min

typealias Pos = Pair<Double, Double>

data class Claim(
    val id: String,
    val pos: Pos,
    val pValid: Double,
    val urgency: Double,
    val survivalGain: Double,
    val need: String? = null,
    val trust: Double = 0.5,
)

data class Responder(
    val id: String,
    val pos: Pos,
    val skills: Set<String> = emptySet(),
    val battery: Double = 1.0,
)

fun pairUtility(
    c: Claim,
    r: Responder,
    routeLen: Double,
    pSafe: Double,
    pReach: Double,
    oppCost: Double,
    lossValue: Double = 1.0,
    speed: Double = 1.5,
    horizon: Double = 3600.0,
    timeWeight: Double = 0.3,
): Double {
    if (!c.need.isNullOrEmpty() && c.need !in r.skills) return Double.NEGATIVE_INFINITY
    val benefit = c.pValid * c.urgency * c.survivalGain * pReach
    val risk = (1.0 - pSafe) * lossValue
    val travel = timeWeight * min(1.0, routeLen / speed / horizon)
    return benefit - risk - travel - oppCost
}

/**
 * P = pValid * pRouteSafe * trust * pReach must clear a threshold that rises with danger.
 * A floor on pValid stops a trusted claimant from carrying a shaky claim.
 */
fun gate(
    pValid: Double,
    pRouteSafe: Double,
    trust: Double,
    pReach: Double,
    base: Double = 0.2,
    slope: Double = 0.5,
    minValid: Double = 0.5,
): String {
    if (pValid < minValid) return "probe"
    val p = pValid * pRouteSafe * trust * pReach
    val thr = min(0.95, base + slope * (1.0 - pRouteSafe))
    if (p >= thr) return "dispatch"
    if (pValid < 0.6 || trust < 0.4) return "probe"
    if (pRouteSafe < 0.7) return "scout"
    return "hold"
}
