package org.thechain.hazard

import kotlin.math.exp
import kotlin.math.max

/**
 * Monotonic flood-rise model: water only rises. Confirmed observations can raise the level, never
 * lower it. The projected rate is the steepest rise ever seen.
 */
class FloodModel(
    baseLevel: Double = 0.0,
    rate: Double = 0.0,
    val softness: Double = 0.5,
) {
    private var level0: Double = baseLevel
    private var t0: Double = 0.0
    var rate: Double = rate
        private set
    private var last: Pair<Double, Double>? = null

    fun level(t: Double): Double = level0 + this.rate * max(0.0, t - t0)

    /** Report confirmed water at [level] metres at time [t]. True if it raised the model. */
    fun observe(level: Double, t: Double): Boolean {
        if (level <= this.level(t)) return false
        val prev = last
        if (prev != null && t > prev.second) {
            rate = max(rate, (level - prev.first) / (t - prev.second))
        }
        level0 = level
        t0 = t
        last = level to t
        return true
    }

    fun pFlooded(elevation: Double, t: Double): Double {
        val margin = elevation - level(t)
        return if (margin <= 0.0) 1.0 else exp(-margin / softness)
    }
}
