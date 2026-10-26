package org.thechain.hazard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class ParticleFilterTest {
    @Test
    fun predictShiftsEveryParticleByTheInjectedNoise() {
        val rng = ScriptedRandom(gausses = List(16) { 0.0 })
        val pf = ParticleFilter(0.0 to 0.0, sigma = 3.0, n = 4, rng = rng)
        val (origin, radius0) = pf.estimate()
        assertEquals(0.0, origin.first, 0.0)
        assertEquals(0.0, origin.second, 0.0)
        assertEquals(0.0, radius0, 0.0)

        pf.predict(5.0, -2.0, noise = 1.0)
        val (moved, radius1) = pf.estimate()
        assertEquals(5.0, moved.first, 1e-12)
        assertEquals(-2.0, moved.second, 1e-12)
        assertEquals(0.0, radius1, 0.0)
    }

    @Test
    fun updateGpsResamplesWhenTheCloudCollapses() {
        // Four particles on the unit square. A tight GPS fix at the origin concentrates weight
        // on the first particle, systematic resampling copies it, then roughening spreads them.
        val rng = ScriptedRandom(
            gausses = listOf(
                0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                3.0, 0.0, 0.0, 4.0, -3.0, 0.0, 0.0, -4.0,
            ),
            doubles = listOf(0.0),
        )
        val pf = ParticleFilter(0.0 to 0.0, sigma = 1.0, n = 4, rng = rng)
        val (before, radiusBefore) = pf.estimate()
        assertEquals(0.5, before.first, 1e-12)
        assertEquals(0.5, before.second, 1e-12)
        assertEquals(sqrt(0.5), radiusBefore, 1e-12)

        pf.updateGps(0.0 to 0.0, sigma = 0.1)
        val (after, radius90) = pf.estimate(0.9)
        assertEquals(0.0, after.first, 1e-9)
        assertEquals(0.0, after.second, 1e-9)
        assertEquals(4.0, radius90, 1e-12)
        assertEquals(3.0, pf.estimate(0.5).second, 1e-12)
    }

    @Test
    fun updateRangeAndAltitudeReweightWithoutPythonRng() {
        val range = ParticleFilter(
            0.0 to 0.0,
            sigma = 1.0,
            n = 2,
            rng = ScriptedRandom(gausses = listOf(0.0, 0.0, 10.0, 0.0)),
        )
        range.updateRange(0.0 to 0.0, dist = 0.0, sigma = 5.0)
        val (mean, radius) = range.estimate()
        assertEquals(1.1920292202211755, mean.first, 1e-9)
        assertEquals(0.0, mean.second, 1e-12)
        assertEquals(8.807970779778824, radius, 1e-9)

        val alt = ParticleFilter(
            0.0 to 0.0,
            sigma = 1.0,
            n = 2,
            rng = ScriptedRandom(gausses = listOf(0.0, 0.0, 10.0, 0.0)),
        )
        alt.updateAltitude({ pos -> pos.first }, alt = 0.0, sigma = 5.0)
        val (altMean, altRadius) = alt.estimate()
        assertEquals(mean.first, altMean.first, 1e-12)
        assertEquals(mean.second, altMean.second, 1e-12)
        assertEquals(radius, altRadius, 1e-12)
        assertTrue(altMean.first < 5.0)
    }

    @Test
    fun sameInjectedSourceIsDeterministic() {
        fun run(): Pair<Pos, Double> {
            val rng = ScriptedRandom(gausses = listOf(0.2, -0.4, 1.0, 0.5, 0.0, 0.0, 0.1, -0.2))
            val pf = ParticleFilter(10.0 to 10.0, sigma = 2.0, n = 2, rng = rng)
            pf.predict(1.0, 0.0, 0.5)
            pf.updateGps(11.0 to 10.0, sigma = 4.0)
            return pf.estimate()
        }
        val a = run()
        val b = run()
        assertEquals(a.first.first, b.first.first, 0.0)
        assertEquals(a.first.second, b.first.second, 0.0)
        assertEquals(a.second, b.second, 0.0)
    }
}

private class ScriptedRandom(
    gausses: List<Double>,
    doubles: List<Double> = emptyList(),
) : ParticleRandom {
    private val g = ArrayDeque(gausses)
    private val d = ArrayDeque(doubles)

    override fun nextDouble(): Double = d.removeFirst()

    override fun gauss(mu: Double, sigma: Double): Double = g.removeFirst()
}
