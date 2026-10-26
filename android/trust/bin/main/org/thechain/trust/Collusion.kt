package org.thechain.trust

/**
 * Kosaraju, iterative, over sorted nodes and sorted outgoing edges.
 * Component membership matches the Python reference.
 */
internal fun sccs(nodes: List<String>, out: Map<String, Map<String, Double>>): List<Set<String>> {
    val seen = HashSet<String>()
    val order = ArrayList<String>()
    for (s in nodes) {
        if (s in seen) continue
        seen.add(s)
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        stack.addLast(s to neighborIter(out, s))
        while (stack.isNotEmpty()) {
            val (n, it) = stack.last()
            var pushed = false
            while (it.hasNext()) {
                val m = it.next()
                if (m !in seen) {
                    seen.add(m)
                    stack.addLast(m to neighborIter(out, m))
                    pushed = true
                    break
                }
            }
            if (!pushed) {
                order.add(n)
                stack.removeLast()
            }
        }
    }

    val rev = LinkedHashMap<String, MutableList<String>>()
    for (n in nodes) rev[n] = mutableListOf()
    val sources = out.keys.sorted()
    for (u in sources) {
        for (v in out.getValue(u).keys.sorted()) {
            rev[v]?.add(u)
        }
    }

    val comps = ArrayList<Set<String>>()
    val done = HashSet<String>()
    for (s in order.asReversed()) {
        if (s in done) continue
        val comp = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(s)
        done.add(s)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            comp.add(n)
            for (m in rev.getValue(n)) {
                if (m !in done) {
                    done.add(m)
                    stack.addLast(m)
                }
            }
        }
        comps.add(comp)
    }
    return comps
}

private fun neighborIter(out: Map<String, Map<String, Double>>, n: String): Iterator<String> =
    (out[n]?.keys?.sorted() ?: emptyList()).iterator()

/**
 * Strongly connected groups of at least [minSize] with no credentialed root and almost no
 * vouches coming in from outside.
 */
fun findClusters(g: VouchGraph, minSize: Int = 3, maxExternalIn: Double = 0.2): List<Set<String>> {
    val nodes = g.nodes().sorted()
    val flagged = ArrayList<Set<String>>()
    for (comp in sccs(nodes, g.out)) {
        if (comp.size < minSize || comp.any { it in g.seeds }) continue
        var inside = 0
        var outside = 0
        for ((u, vs) in g.out) {
            for (v in vs.keys) {
                if (v in comp) {
                    if (u in comp) inside++ else outside++
                }
            }
        }
        if (inside > 0 && outside.toDouble() / (inside + outside) <= maxExternalIn) {
            flagged.add(comp)
        }
    }
    return flagged
}

/** node -> multiplier. 1.0 means no discount. */
fun discounts(
    g: VouchGraph,
    factor: Double = 0.2,
    minSize: Int = 3,
    maxExternalIn: Double = 0.2,
): Map<String, Double> {
    val d = HashMap<String, Double>()
    for (comp in findClusters(g, minSize, maxExternalIn)) {
        for (n in comp) d[n] = factor
    }
    return d
}
