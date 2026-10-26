package org.thechain.hazard

import kotlin.math.floor
import kotlin.math.pow

/**
 * Hazard map as a CRDT grid. Last report per (cell, reporter) wins, so merge order converges.
 * Hazard reports are accepted on weak evidence. Safe reports lift risk only when enough distinct
 * reporters agree, and only if no newer hazard report exists. Flood physics is never overridden.
 */
data class Report(
    val reporter: String,
    val cell: Cell,
    val kind: String,
    val t: Double,
    val weight: Double,
) : Comparable<Report> {
    override fun compareTo(other: Report): Int {
        val byT = t.compareTo(other.t)
        if (byT != 0) return byT
        val hazard = kind == "hazard"
        val otherHazard = other.kind == "hazard"
        if (hazard != otherHazard) return if (hazard) 1 else -1
        return weight.compareTo(other.weight)
    }
}

class HazardGrid(
    val cellM: Double = 50.0,
    val hazardHalfLife: Double = 1800.0,
    val safeHalfLife: Double = 900.0,
    val hazardGain: Double = 0.8,
    val safeGain: Double = 0.5,
    val minSafeReporters: Int = 2,
    val minSafeWeight: Double = 1.0,
    val prior: Double = 0.1,
    val flood: FloodModel? = null,
    val dem: ((Pos) -> Double)? = null,
) {
    private val reports = LinkedHashMap<Pair<Cell, String>, Report>()

    fun cellOf(pos: Pos): Cell =
        floor(pos.first / cellM).toInt() to floor(pos.second / cellM).toInt()

    fun center(cell: Cell): Pos =
        (cell.first + 0.5) * cellM to (cell.second + 0.5) * cellM

    fun report(reporter: String, pos: Pos, kind: String, t: Double, weight: Double): Boolean {
        require(kind == "hazard" || kind == "safe") { kind }
        return put(Report(reporter, cellOf(pos), kind, t, weight.coerceIn(0.0, 1.0)))
    }

    private fun put(r: Report): Boolean {
        val key = r.cell to r.reporter
        val cur = reports[key]
        if (cur == null || r > cur) {
            reports[key] = r
            return true
        }
        return false
    }

    fun merge(other: HazardGrid) {
        for (r in other.reports.values) put(r)
    }

    private fun live(cell: Cell, kind: String, now: Double): List<Pair<Report, Double>> {
        val halfLife = if (kind == "hazard") hazardHalfLife else safeHalfLife
        val out = ArrayList<Pair<Report, Double>>()
        for ((key, r) in reports) {
            if (key.first == cell && r.kind == kind && r.t <= now) {
                val decayed = r.weight * 0.5.pow((now - r.t) / halfLife)
                if (decayed >= 0.01) out.add(r to decayed)
            }
        }
        return out
    }

    fun hazardP(cell: Cell, now: Double): Double {
        var q = 1.0
        for ((_, weight) in live(cell, "hazard", now)) q *= 1.0 - hazardGain * weight
        return 1.0 - q
    }

    fun safeStrength(cell: Cell, now: Double): Double {
        val newestHazard = live(cell, "hazard", now).maxOfOrNull { it.first.t } ?: Double.NEGATIVE_INFINITY
        val safe = live(cell, "safe", now).filter { it.first.t > newestHazard }
        if (safe.map { it.first.reporter }.toSet().size < minSafeReporters) return 0.0
        if (safe.sumOf { it.second } < minSafeWeight) return 0.0
        var q = 1.0
        for ((_, weight) in safe) q *= 1.0 - safeGain * weight
        return 1.0 - q
    }

    fun risk(cell: Cell, now: Double): Double {
        val s = safeStrength(cell, now)
        var keep = (1.0 - prior * (1.0 - s)) * (1.0 - hazardP(cell, now))
        val water = flood
        val elevation = dem
        if (water != null && elevation != null) {
            keep *= 1.0 - water.pFlooded(elevation(center(cell)), now)
        }
        return 1.0 - keep
    }

    fun riskFn(): (Pos, Double) -> Double = { pos, t -> risk(cellOf(pos), t) }
}
