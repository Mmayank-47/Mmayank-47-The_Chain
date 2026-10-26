package org.thechain.battery

import kotlin.math.max
import kotlin.math.min

/** Fractions of full charge per second: screen-off floor through flat-out ceiling. */
const val MIN_RATE = 2e-6
const val MAX_RATE = 2e-4

data class Sample(
    val t: Double,
    val level: Double,
    val charging: Boolean = false,
)

fun check(
    samples: List<Sample>,
    riseTol: Double = 0.02,
    minDt: Double = 30.0,
    flatSpan: Double = 3600.0,
): Pair<Boolean, List<String>> {
    val why = ArrayList<String>()
    val s = samples.sortedBy { it.t }
    if (s.any { it.level < 0.0 || it.level > 1.0 }) why.add("level out of range")
    for (i in 0 until s.size - 1) {
        val a = s[i]
        val b = s[i + 1]
        val dt = b.t - a.t
        if (dt <= 0.0) continue
        if (b.level > a.level + riseTol && !(a.charging || b.charging)) {
            why.add("level rose without charging")
        } else if (dt >= minDt && (a.level - b.level) / dt > MAX_RATE * 1.5) {
            why.add("drained faster than hardware allows")
        }
    }
    if (s.size >= 2 && s.last().t - s.first().t >= flatSpan &&
        s.first().level - s.last().level < 0.002 && s.none { it.charging }
    ) {
        why.add("flat curve over a long span")
    }
    val reasons = why.toSet().sorted()
    return reasons.isEmpty() to reasons
}

/** Least-squares drain rate. Falls back to [default] with fewer than 3 discharging samples. */
fun fitRate(samples: List<Sample>, default: Double = 3e-5): Double {
    val s = samples.filter { !it.charging }
    if (s.size < 3) return default
    val n = s.size.toDouble()
    var mt = 0.0
    var ml = 0.0
    for (x in s) {
        mt += x.t
        ml += x.level
    }
    mt /= n
    ml /= n
    var variance = 0.0
    var cov = 0.0
    for (x in s) {
        val dt = x.t - mt
        variance += dt * dt
        cov += dt * (x.level - ml)
    }
    if (variance == 0.0) return default
    val slope = cov / variance
    return min(MAX_RATE, max(MIN_RATE, -slope))
}

fun timeToSilence(level: Double, rate: Double): Double = max(0.0, level) / max(rate, MIN_RATE)

/**
 * Implausible curve is strong evidence of a fake. A clean multi-sample curve is mild support.
 */
fun batteryLlr(samples: List<Sample>): Double {
    if (samples.size < 2) return 0.0
    val ok = check(samples).first
    return if (ok && samples.size >= 3) 0.5 else if (!ok) -3.0 else 0.0
}
