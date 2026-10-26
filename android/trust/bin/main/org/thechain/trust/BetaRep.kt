package org.thechain.trust

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** event -> (alpha weight, beta weight). Failures weigh more than successes. */
val EVENTS: Map<String, Pair<Double, Double>> = mapOf(
    "verified_arrival" to (1.0 to 0.0),
    "confirmed_hazard" to (0.5 to 0.0),
    "corroborated_claim" to (0.5 to 0.0),
    "failed_claim" to (0.0 to 3.0),
    "failed_challenge" to (0.0 to 3.0),
    "false_hazard" to (0.0 to 2.0),
    "equivocation" to (0.0 to 6.0),
    "forward_success" to (0.5 to 0.0),
    "forward_failure" to (0.0 to 3.0),
)

/**
 * Subjective Beta reputation. Evidence is pseudo-counts on a cold-start prior and decays
 * toward that prior with [halfLifeS].
 */
class BetaRep(
    val priorA: Double = 1.0,
    val priorB: Double = 1.0,
    val halfLifeS: Double = 6 * 3600.0,
    val maxEventWeight: Double = 3.0,
) {
    private val ev = HashMap<String, Triple<Double, Double, Double>>()

    init {
        require(priorA > 0.0 && priorB > 0.0 && halfLifeS > 0.0) {
            "prior and half-life must be positive"
        }
    }

    private fun decayed(node: String, now: Double): Pair<Double, Double> {
        val (a, b, t) = ev[node] ?: Triple(0.0, 0.0, now)
        val f = 0.5.pow(max(0.0, now - t) / halfLifeS)
        return a * f to b * f
    }

    fun record(node: String, event: String, now: Double, weight: Double = 1.0) {
        val weights = EVENTS[event] ?: throw IllegalArgumentException("unknown event '$event'")
        val w = max(0.0, min(weight, maxEventWeight))
        val (a, b) = decayed(node, now)
        ev[node] = Triple(a + weights.first * w, b + weights.second * w, now)
    }

    /** How much decayed evidence backs the score. 0 for a stranger. */
    fun evidence(node: String, now: Double): Double {
        val (a, b) = decayed(node, now)
        return a + b
    }

    fun mean(node: String, now: Double): Double {
        val (a, b) = decayed(node, now)
        return (priorA + a) / (priorA + a + priorB + b)
    }

    fun std(node: String, now: Double): Double {
        val (a, b) = decayed(node, now)
        val alpha = priorA + a
        val beta = priorB + b
        return sqrt(alpha * beta / ((alpha + beta) * (alpha + beta) * (alpha + beta + 1.0)))
    }
}
