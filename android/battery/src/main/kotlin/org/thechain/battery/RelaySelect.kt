package org.thechain.battery

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

data class Peer(
    val id: String,
    val battery: Double,
    val neighbors: Int,
)

fun relayScore(p: Peer, targetDegree: Int = 6): Double =
    p.battery * min(1.0, p.neighbors.toDouble() / targetDegree)

/** Charged, well-connected peers first. Beacon-mode phones never relay. */
fun pickRelays(peers: List<Peer>, k: Int): List<Peer> {
    val ok = peers.filter { it.battery > BEACON_AT }
    return ok.sortedWith(compareByDescending<Peer> { relayScore(it) }.thenBy { it.id }).take(k)
}

/** Battery fraction for the walk (with safety margin) plus a reserve for on-site use. */
fun energyNeeded(
    routeM: Double,
    speed: Double = 1.5,
    activeRate: Double = 1.2e-4,
    margin: Double = 1.5,
    reserve: Double = 0.15,
): Double = routeM / speed * activeRate * margin + reserve

/**
 * P(responder still has charge on arrival). Normal CDF of the energy margin.
 * [sigma] is drain-rate uncertainty, as a fraction of full charge.
 */
fun pReach(
    level: Double,
    routeM: Double,
    sigma: Double = 0.05,
    speed: Double = 1.5,
    activeRate: Double = 1.2e-4,
    margin: Double = 1.5,
    reserve: Double = 0.15,
): Double {
    val z = (level - energyNeeded(routeM, speed, activeRate, margin, reserve)) / sigma
    return 0.5 * (1.0 + erf(z / sqrt(2.0)))
}

fun canDispatch(
    level: Double,
    routeM: Double,
    threshold: Double = 0.9,
    sigma: Double = 0.05,
    speed: Double = 1.5,
    activeRate: Double = 1.2e-4,
    margin: Double = 1.5,
    reserve: Double = 0.15,
): Boolean = pReach(level, routeM, sigma, speed, activeRate, margin, reserve) >= threshold

/** Abramowitz and Stegun 7.1.26. Saturates to ±1 for the large z values p_reach produces. */
internal fun erf(x: Double): Double {
    val sign = if (x < 0.0) -1.0 else 1.0
    val ax = abs(x)
    val t = 1.0 / (1.0 + 0.3275911 * ax)
    val poly = (((((1.061405429 * t) - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592
    val y = 1.0 - poly * t * exp(-ax * ax)
    return sign * y
}
