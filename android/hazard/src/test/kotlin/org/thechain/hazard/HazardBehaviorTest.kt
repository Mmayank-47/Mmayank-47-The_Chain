package org.thechain.hazard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HazardBehaviorTest {
    @Test
    fun hazardAcceptedOnWeakEvidenceSafeIsNot() {
        val g = HazardGrid()
        val c = g.cellOf(100.0 to 0.0)
        val base = g.risk(c, 0.0)
        g.report("a", 100.0 to 0.0, "hazard", 0.0, 0.3)
        assertTrue(g.risk(c, 0.0) > base + 0.1)

        val h = HazardGrid()
        h.report("a", 100.0 to 0.0, "safe", 0.0, 1.0)
        assertEquals(base, h.risk(c, 0.0), 1e-12)
        val h2 = HazardGrid()
        h2.report("a", 100.0 to 0.0, "safe", 0.0, 0.4)
        h2.report("a2", 100.0 to 0.0, "safe", 0.0, 0.4)
        assertEquals(base, h2.risk(c, 0.0), 1e-12)
        h.report("a2", 100.0 to 0.0, "safe", 0.0, 0.1)
        assertTrue(h.risk(c, 0.0) < base)
    }

    @Test
    fun newerHazardVoidsSafeAndOneReporterCannotStack() {
        val g = HazardGrid()
        val c = g.cellOf(0.0 to 0.0)
        for (r in listOf("a", "b")) g.report(r, 0.0 to 0.0, "safe", 10.0, 1.0)
        val low = g.risk(c, 20.0)
        g.report("c", 0.0 to 0.0, "hazard", 15.0, 0.6)
        assertTrue(g.risk(c, 20.0) > low + 0.3)

        val stacked = HazardGrid()
        for (t in 0 until 50) stacked.report("spam", 0.0 to 0.0, "hazard", t.toDouble(), 0.3)
        val one = HazardGrid()
        one.report("spam", 0.0 to 0.0, "hazard", 49.0, 0.3)
        assertEquals(one.risk(c, 49.0), stacked.risk(c, 49.0), 1e-12)
        assertFalse(stacked.report("spam", 0.0 to 0.0, "hazard", 1.0, 0.9))
        assertFalse(stacked.report("spam", 0.0 to 0.0, "safe", 49.0, 1.0))
        assertTrue(stacked.report("spam", 0.0 to 0.0, "hazard", 50.0, 0.2))
    }

    @Test
    fun hazardsExpireAndMergeOrderConverges() {
        val g = HazardGrid(hazardHalfLife = 100.0)
        val c = g.cellOf(0.0 to 0.0)
        g.report("a", 0.0 to 0.0, "hazard", 0.0, 1.0)
        assertTrue(g.risk(c, 0.0) > 0.8)
        assertEquals(g.prior, g.risk(c, 5000.0), 1e-9)

        val reps = listOf(
            Triple("a", "hazard", 5.0 to 0.5),
            Triple("b", "safe", 6.0 to 0.9),
            Triple("a", "safe", 9.0 to 0.7),
            Triple("c", "hazard", 2.0 to 0.4),
        )
        val x = HazardGrid()
        val y = HazardGrid()
        reps.forEachIndexed { i, (reporter, kind, tw) ->
            val target = if (i % 2 == 0) y else x
            target.report(reporter, 10.0 to 10.0, kind, tw.first, tw.second)
        }
        val x2 = HazardGrid()
        val y2 = HazardGrid()
        x2.merge(x)
        x2.merge(y)
        y2.merge(y)
        y2.merge(x)
        val cell = x2.cellOf(10.0 to 10.0)
        assertEquals(y2.risk(cell, 30.0), x2.risk(cell, 30.0), 0.0)
        assertEquals(y2.hazardP(cell, 30.0), x2.hazardP(cell, 30.0), 0.0)
        assertEquals(y2.safeStrength(cell, 30.0), x2.safeStrength(cell, 30.0), 0.0)
    }

    @Test
    fun safeClaimCannotOverrideFloodPhysics() {
        val g = HazardGrid(flood = FloodModel(baseLevel = 5.0), dem = { 1.0 })
        val c = g.cellOf(0.0 to 0.0)
        for (r in listOf("a", "b", "c", "d")) g.report(r, 0.0 to 0.0, "safe", 0.0, 1.0)
        assertTrue(g.risk(c, 0.0) > 0.99)
    }

    @Test
    fun floodRateIsSteepestSeenAndNeverLowers() {
        val f = FloodModel(baseLevel = 1.0)
        assertTrue(f.observe(2.0, 100.0))
        assertFalse(f.observe(1.5, 200.0))
        assertTrue(f.observe(4.0, 300.0))
        assertEquals(2.0 / 200.0, f.rate, 1e-12)
        assertTrue(f.level(500.0) >= f.level(300.0))
        assertTrue(f.pFlooded(5.0, 0.0) < f.pFlooded(5.0, 400.0))
        assertTrue(f.pFlooded(8.0, 0.0) < f.pFlooded(5.0, 0.0))
        assertEquals(1.0, f.pFlooded(0.5, 0.0), 0.0)
    }

    @Test
    fun unknownKindRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HazardGrid().report("a", 0.0 to 0.0, "nope", 0.0, 1.0)
        }
    }

    @Test
    fun equalCostPathFollowsEarlierHeapTie() {
        val first = diamond(listOf("A" to "B", "A" to "C", "B" to "D", "C" to "D"))
        val second = diamond(listOf("A" to "C", "A" to "B", "C" to "D", "B" to "D"))
        val zero: (Pos, Double) -> Double = { _, _ -> 0.0 }
        assertEquals(listOf("A", "B", "D"), findRoute(first, "A", "D", zero)!!.path)
        assertEquals(listOf("A", "C", "D"), findRoute(second, "A", "D", zero)!!.path)
    }

    @Test
    fun moderateHazardReroutesAndStrongHazardCanBlock() {
        val grid = HazardGrid()
        grid.report("a", 100.0 to 0.0, "hazard", 0.0, 0.5)
        val reroute = findRoute(squareGraph(), "A", "B", grid.riskFn(), t0 = 0.0)
        assertNotNull(reroute)
        assertEquals(listOf("A", "C", "D", "B"), reroute!!.path)
        assertTrue(reroute.pSafe > 0.6)

        val blocked = HazardGrid()
        blocked.report("a", 100.0 to 0.0, "hazard", 0.0, 1.0)
        assertEquals(listOf("A", "C", "D", "B"), findRoute(squareGraph(), "A", "B", blocked.riskFn())!!.path)
        blocked.report("b", 0.0 to 100.0, "hazard", 0.0, 1.0)
        assertNull(findRoute(squareGraph(), "A", "B", blocked.riskFn()))
    }

    @Test
    fun risingFloodIsSeenAtTheTimeTheWalkerArrives() {
        val underpass: (Pos) -> Double = { p ->
            if (p.first > 50.0 && p.first < 150.0 && p.second < 30.0) 8.0 else 100.0
        }
        val grid = HazardGrid(flood = FloodModel(baseLevel = 0.0, rate = 0.08), dem = underpass)
        val slow = findRoute(squareGraph(), "A", "B", grid.riskFn(), t0 = 0.0, speed = 1.0)
        val fast = findRoute(squareGraph(), "A", "B", grid.riskFn(), t0 = 0.0, speed = 10.0)
        assertEquals(listOf("A", "C", "D", "B"), slow!!.path)
        assertEquals(listOf("A", "B"), fast!!.path)
    }
}

private fun diamond(edges: List<Pair<String, String>>): RoadGraph {
    val g = RoadGraph()
    g.addNode("A", 0.0 to 0.0)
    g.addNode("B", 1.0 to 0.0)
    g.addNode("C", 0.0 to 1.0)
    g.addNode("D", 1.0 to 1.0)
    for ((a, b) in edges) g.addEdge(a, b)
    return g
}
