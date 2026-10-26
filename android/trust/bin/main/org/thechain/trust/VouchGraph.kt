package org.thechain.trust

import kotlin.math.min

/**
 * Personalised PageRank seeded from credentialed roots.
 * Iteration walks sorted nodes and sorted outgoing edges so scores match the Python reference.
 */
class VouchGraph(
    seeds: Iterable<String> = emptyList(),
    val alpha: Double = 0.15,
    val iters: Int = 50,
) {
    private val seedSet = LinkedHashSet<String>().apply { addAll(seeds) }
    val seeds: Set<String> get() = seedSet

    private val outMap = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    val out: Map<String, Map<String, Double>> get() = outMap

    private var cache: Map<String, Double>? = null

    fun addSeed(node: String) {
        seedSet.add(node)
        cache = null
    }

    /** Re-vouching replaces the weight. Self-vouches and non-positive weights are ignored. */
    fun vouch(u: String, v: String, w: Double = 1.0) {
        if (u == v || w <= 0.0) return
        outMap.getOrPut(u) { LinkedHashMap() }[v] = w
        cache = null
    }

    fun nodes(): Set<String> {
        val n = HashSet<String>()
        n.addAll(seedSet)
        n.addAll(outMap.keys)
        for (vs in outMap.values) n.addAll(vs.keys)
        return n
    }

    /** Stationary trust mass. Sums to 1. All zeros without seeds. */
    fun rank(): Map<String, Double> {
        val nodeList = nodes().sorted()
        if (seedSet.isEmpty()) return nodeList.associateWith { 0.0 }
        val tele = LinkedHashMap<String, Double>()
        val share = 1.0 / seedSet.size
        for (s in seedSet) tele[s] = share
        var pr = LinkedHashMap<String, Double>()
        for (n in nodeList) pr[n] = tele[n] ?: 0.0
        val teleSeeds = tele.keys.toList()
        repeat(iters) {
            val nxt = LinkedHashMap<String, Double>()
            for (n in nodeList) nxt[n] = alpha * (tele[n] ?: 0.0)
            for (u in nodeList) {
                val mass = pr.getValue(u) * (1.0 - alpha)
                val outs = outMap[u]
                if (!outs.isNullOrEmpty()) {
                    val edges = outs.entries.sortedBy { it.key }
                    var tot = 0.0
                    for (e in edges) tot += e.value
                    for (e in edges) {
                        val v = e.key
                        nxt[v] = nxt.getValue(v) + mass * e.value / tot
                    }
                } else {
                    for (s in teleSeeds) {
                        nxt[s] = nxt.getValue(s) + mass * tele.getValue(s)
                    }
                }
            }
            pr = nxt
        }
        return pr
    }

    /**
     * 0..1. Roots are 1.0. Others are measured against the root teleport floor.
     */
    fun scores(): Map<String, Double> {
        val hit = cache
        if (hit != null) return hit
        val pr = rank()
        val ref = if (seedSet.isNotEmpty()) alpha / seedSet.size else 1.0
        val scaled = LinkedHashMap<String, Double>()
        for ((n, p) in pr) scaled[n] = min(1.0, p / ref)
        cache = scaled
        return scaled
    }

    /** Strangers (not in the graph) get [default]. */
    fun score(node: String, default: Double = 0.5): Double = scores()[node] ?: default
}
