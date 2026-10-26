package org.thechain.hazard

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Injected randomness so predict/update/estimate do not depend on CPython's Mersenne Twister.
 * [nextDouble] is uniform in [0, 1). [gauss] matches `random.Random.gauss(mu, sigma)`.
 */
interface ParticleRandom {
    fun nextDouble(): Double
    fun gauss(mu: Double, sigma: Double): Double
}

private class BoxMullerRandom(seed: Long = 0L) : ParticleRandom {
    private val random = Random(seed)

    override fun nextDouble(): Double = random.nextDouble()

    override fun gauss(mu: Double, sigma: Double): Double {
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return mu + sigma * z
    }
}

/**
 * Location as a distribution. [estimate] returns mean position and the radius that holds weight `q`.
 * Downstream code should use the radius, never the bare point.
 */
class ParticleFilter(
    pos: Pos,
    sigma: Double,
    val n: Int = 300,
    private val rng: ParticleRandom = BoxMullerRandom(),
) {
    private val p: MutableList<DoubleArray> = MutableList(n) {
        doubleArrayOf(pos.first + rng.gauss(0.0, sigma), pos.second + rng.gauss(0.0, sigma))
    }
    private var w: DoubleArray = DoubleArray(n) { 1.0 / n }

    /** IMU step: move every particle by the measured displacement plus drift noise. */
    fun predict(dx: Double, dy: Double, noise: Double) {
        for (q in p) {
            q[0] += dx + rng.gauss(0.0, noise)
            q[1] += dy + rng.gauss(0.0, noise)
        }
    }

    private fun reweight(lik: (DoubleArray) -> Double) {
        val next = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            next[i] = w[i] * max(lik(p[i]), 1e-12)
            sum += next[i]
        }
        var sq = 0.0
        for (i in 0 until n) {
            next[i] /= sum
            sq += next[i] * next[i]
        }
        w = next
        if (1.0 / sq < n / 2.0) resample()
    }

    /** Systematic resampling plus 1 m roughening so particles do not collapse. */
    private fun resample() {
        var c = w[0]
        var i = 0
        val u0 = rng.nextDouble() / n
        val next = ArrayList<DoubleArray>(n)
        for (k in 0 until n) {
            val t = u0 + k.toDouble() / n
            while (t > c && i < n - 1) {
                i += 1
                c += w[i]
            }
            val src = p[i]
            next.add(doubleArrayOf(src[0] + rng.gauss(0.0, 1.0), src[1] + rng.gauss(0.0, 1.0)))
        }
        p.clear()
        p.addAll(next)
        w = DoubleArray(n) { 1.0 / n }
    }

    fun updateGps(pos: Pos, sigma: Double) {
        val denom = 2.0 * sigma * sigma
        reweight { q -> exp(-((q[0] - pos.first) * (q[0] - pos.first) + (q[1] - pos.second) * (q[1] - pos.second)) / denom) }
    }

    /** BLE/UWB range to a neighbour whose own position is known. */
    fun updateRange(anchor: Pos, dist: Double, sigma: Double) {
        val denom = 2.0 * sigma * sigma
        reweight { q ->
            val d = hypot2(q[0], q[1], anchor)
            exp(-(d - dist) * (d - dist) / denom)
        }
    }

    /** Barometric altitude against the preloaded elevation model. */
    fun updateAltitude(dem: (Pos) -> Double, alt: Double, sigma: Double) {
        val denom = 2.0 * sigma * sigma
        reweight { q ->
            val e = dem(q[0] to q[1]) - alt
            exp(-(e * e) / denom)
        }
    }

    fun estimate(q: Double = 0.9): Pair<Pos, Double> {
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += w[i] * p[i][0]
            my += w[i] * p[i][1]
        }
        val mean = mx to my
        val ds = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            ds.add(hypot2(p[i][0], p[i][1], mean) to w[i])
        }
        ds.sortWith(compareBy({ it.first }, { it.second }))
        var acc = 0.0
        for ((d, weight) in ds) {
            acc += weight
            if (acc >= q) return mean to d
        }
        return mean to ds.last().first
    }
}

private fun hypot2(x: Double, y: Double, pos: Pos): Double =
    kotlin.math.hypot(x - pos.first, y - pos.second)
