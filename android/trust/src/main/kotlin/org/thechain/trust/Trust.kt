package org.thechain.trust

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Blend of graph trust and behavioural reputation. Strangers lean on the graph; as evidence
 * builds, the blend moves to observed behaviour. Collusion discount multiplies the result.
 */
class TrustEngine(
    val graph: VouchGraph = VouchGraph(),
    val rep: BetaRep = BetaRep(),
    val evidenceK: Double = 4.0,
    val clusterFactor: Double = 0.2,
) {
    private var disc: Map<String, Double>? = null
    private var discEdges: Int = -1

    private fun discounts(): Map<String, Double> {
        var nEdges = seedCount()
        for (v in graph.out.values) nEdges += v.size
        val cached = disc
        if (cached == null || nEdges != discEdges) {
            disc = discounts(graph, clusterFactor)
            discEdges = nEdges
        }
        return disc!!
    }

    private fun seedCount(): Int = graph.seeds.size

    fun trust(node: String, now: Double): Double {
        val g = graph.score(node)
        val n = rep.evidence(node, now)
        val w = n / (n + evidenceK)
        val t = (1.0 - w) * g + w * rep.mean(node, now)
        return max(0.0, min(1.0, t * (discounts()[node] ?: 1.0)))
    }

    /** Same trust number as a log-likelihood ratio, clamped to ±[limit]. */
    fun llr(node: String, now: Double, limit: Double = 3.0): Double {
        val t = min(max(trust(node, now), 1e-6), 1.0 - 1e-6)
        return max(-limit, min(limit, ln(t / (1.0 - t))))
    }
}
