package org.thechain.hazard

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class HazardVectorTest {
    private val spec = Vectors.load("hazard")

    @Test
    fun matchesPythonVectors() {
        val g = squareGraph()
        val plain = findRoute(g, "A", "B", HazardGrid().riskFn())
        val plainSpec = spec.getJSONObject("plain")
        check(plain != null)
        assertEquals(plainSpec.getJSONArray("path").strings(), plain.path)
        assertEquals(plainSpec.getDouble("length"), plain.length, 1e-9)
        assertEquals(plainSpec.getDouble("cost"), plain.cost, 1e-9)
        assertEquals(plainSpec.getDouble("p_safe"), plain.pSafe, 1e-9)
        assertEquals(plainSpec.getDouble("worst"), plain.worstRisk, 1e-9)

        val grid = HazardGrid()
        grid.report("h", 100.0 to 0.0, "hazard", 0.0, 0.9)
        val blocked = findRoute(g, "A", "B", grid.riskFn())
        val detour = findRoute(g, "A", "B", grid.riskFn())
        assertEquals(spec.getBoolean("direct_after_hazard_is_none"), blocked == null)
        assertEquals(spec.getBoolean("detour_none_same_grid"), detour == null)
        assertEquals(spec.getDouble("risk"), grid.risk(grid.cellOf(100.0 to 0.0), 0.0), 1e-9)

        val flood = FloodModel()
        assertEquals(spec.getBoolean("flood_raised"), flood.observe(1.2, 10.0))
        assertEquals(spec.getBoolean("flood_ignored_lower"), flood.observe(0.2, 11.0))
        assertEquals(spec.getDouble("flood_level"), flood.level(20.0), 1e-9)
        assertEquals(spec.getDouble("p_flooded_under"), flood.pFlooded(0.5, 20.0), 1e-9)
        assertEquals(spec.getDouble("p_flooded_high"), flood.pFlooded(50.0, 20.0), 1e-9)
    }
}

internal fun squareGraph(): RoadGraph {
    val g = RoadGraph()
    for ((n, p) in listOf("A" to (0.0 to 0.0), "B" to (200.0 to 0.0), "C" to (0.0 to 200.0), "D" to (200.0 to 200.0))) {
        g.addNode(n, p)
    }
    for ((a, b) in listOf("A" to "B", "A" to "C", "C" to "D", "D" to "B")) g.addEdge(a, b)
    return g
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
