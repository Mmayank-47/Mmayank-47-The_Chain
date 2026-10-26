package org.thechain.hazard

import java.util.PriorityQueue
import kotlin.math.max

class RoadGraph {
    val pos: MutableMap<String, Pos> = LinkedHashMap()
    private val adj: MutableMap<String, LinkedHashMap<String, Double>> = LinkedHashMap()

    fun addNode(n: String, pos: Pos) {
        this.pos[n] = pos
    }

    fun addEdge(a: String, b: String) {
        val d = dist(pos.getValue(a), pos.getValue(b))
        adj.getOrPut(a) { LinkedHashMap() }[b] = d
        adj.getOrPut(b) { LinkedHashMap() }[a] = d
    }

    fun neighbors(n: String): Map<String, Double> = adj[n] ?: emptyMap()

    fun edgeLength(a: String, b: String): Double = adj.getValue(a).getValue(b)
}

data class Route(
    val path: List<String>,
    val length: Double,
    val cost: Double,
    val pSafe: Double,
    val worstRisk: Double,
)

/**
 * Worst risk along the edge. Each sample is judged at the moment we would be standing there.
 */
fun edgeRisk(
    g: RoadGraph,
    a: String,
    b: String,
    risk: (Pos, Double) -> Double,
    t: Double,
    speed: Double = 1.5,
    step: Double = 25.0,
): Double {
    val (x0, y0) = g.pos.getValue(a)
    val (x1, y1) = g.pos.getValue(b)
    val d = g.edgeLength(a, b)
    val samples = max(1, (d / step).toInt())
    val n = samples.toDouble()
    var worst = Double.NEGATIVE_INFINITY
    for (i in 0..samples) {
        val x = x0 + (x1 - x0) * i / n
        val y = y0 + (y1 - y0) * i / n
        val at = t + d * i / n / speed
        val r = risk(x to y, at)
        if (r > worst) worst = r
    }
    return worst
}

private data class HeapItem(val f: Double, val tie: Int, val node: String) : Comparable<HeapItem> {
    override fun compareTo(other: HeapItem): Int {
        val byF = f.compareTo(other.f)
        if (byF != 0) return byF
        val byTie = tie.compareTo(other.tie)
        if (byTie != 0) return byTie
        return node.compareTo(other.node)
    }
}

/**
 * A* over an offline road graph. Edge cost is `length * (1 + a*risk + b*risk^3)`.
 * Equal f-scores expand in push order: the heap key is (f, tie, node) and [tie] increments
 * on every push, matching Python's heapq.
 */
fun findRoute(
    g: RoadGraph,
    src: String,
    dst: String,
    risk: (Pos, Double) -> Double,
    t0: Double = 0.0,
    speed: Double = 1.5,
    a: Double = 2.0,
    b: Double = 20.0,
    blockAbove: Double = 0.8,
): Route? {
    val best = HashMap<String, Double>()
    val length = HashMap<String, Double>()
    val safe = HashMap<String, Double>()
    val worst = HashMap<String, Double>()
    val prev = HashMap<String, String>()
    best[src] = 0.0
    length[src] = 0.0
    safe[src] = 1.0
    worst[src] = 0.0
    val heap = PriorityQueue<HeapItem>()
    heap.add(HeapItem(dist(g.pos.getValue(src), g.pos.getValue(dst)), 0, src))
    var tie = 0
    val done = HashSet<String>()
    while (heap.isNotEmpty()) {
        val n = heap.poll().node
        if (n in done) continue
        done.add(n)
        if (n == dst) {
            val path = ArrayList<String>()
            var cur: String? = n
            while (cur != null) {
                path.add(cur)
                cur = prev[cur]
            }
            path.reverse()
            return Route(path, length.getValue(n), best.getValue(n), safe.getValue(n), worst.getValue(n))
        }
        val ln = length.getValue(n)
        val ps = safe.getValue(n)
        val w = worst.getValue(n)
        val arrive = t0 + ln / speed
        for ((m, d) in g.neighbors(n)) {
            if (m in done) continue
            val e = edgeRisk(g, n, m, risk, arrive, speed)
            if (e >= blockAbove) continue
            val cost = best.getValue(n) + d * (1.0 + a * e + b * e * e * e)
            val known = best[m]
            if (known == null || cost < known) {
                best[m] = cost
                prev[m] = n
                length[m] = ln + d
                safe[m] = ps * (1.0 - e)
                worst[m] = max(w, e)
                tie += 1
                val f = cost + dist(g.pos.getValue(m), g.pos.getValue(dst))
                heap.add(HeapItem(f, tie, m))
            }
        }
    }
    return null
}
