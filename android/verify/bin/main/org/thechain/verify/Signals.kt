package org.thechain.verify

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Claims farther apart than this describe different places (above GPS noise). */
const val SAME_PLACE_M = 100.0

/** Copy-pasted coordinates. */
const val SAME_SPOT_M = 1.0

const val CORROB_M = 150.0
const val CORROB_WINDOW_S = 600.0
const val FANOUT_WINDOW_S = 600.0

/**
 * A claim as this phone can see it. [node] is the physical device, [src] is the identity
 * the claim asserts. [id] ordering matches Python tuple keys `(t, id)`.
 */
data class ObservedClaim<Id : Comparable<Id>, N>(
    val id: Id,
    val node: N,
    val src: N,
    val pos: Pair<Double, Double>,
    val t: Double,
)

/** Every claim this phone has seen, indexed by physical device. */
class Ledger<Id : Comparable<Id>, N> {
    val byId = LinkedHashMap<Id, ObservedClaim<Id, N>>()
    val byNode = LinkedHashMap<N, MutableList<ObservedClaim<Id, N>>>()
    var latest: Double = 0.0
        private set

    fun add(c: ObservedClaim<Id, N>): Boolean {
        if (c.id in byId) return false
        byId[c.id] = c
        byNode.getOrPut(c.node) { mutableListOf() }.add(c)
        latest = max(latest, c.t)
        return true
    }
}

private fun <Id : Comparable<Id>, N> earlier(o: ObservedClaim<Id, N>, c: ObservedClaim<Id, N>): Boolean {
    if (o.t < c.t) return true
    if (o.t > c.t) return false
    return o.id < c.id
}

private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
    hypot(a.first - b.first, a.second - b.second)

/** The device that sent the claim must be the identity it speaks for. */
fun <Id : Comparable<Id>, N> identityBinding(c: ObservedClaim<Id, N>): Double =
    if (c.src == c.node) 0.5 else -4.0

/** One phone cannot be in many places: penalise earlier claims from this device elsewhere. */
fun <Id : Comparable<Id>, N> nodeSpread(ledger: Ledger<Id, N>, c: ObservedClaim<Id, N>): Double {
    var k = 0
    for (o in ledger.byNode[c.node].orEmpty()) {
        if (o.id != c.id && earlier(o, c) && dist(o.pos, c.pos) > SAME_PLACE_M) k++
    }
    return -1.5 * min(k.toDouble(), 3.0)
}

/** One device speaking as many identities at about the same time. */
fun <Id : Comparable<Id>, N> identityFanout(ledger: Ledger<Id, N>, c: ObservedClaim<Id, N>): Double {
    val srcs = HashSet<N>()
    for (o in ledger.byNode[c.node].orEmpty()) {
        if (o.id != c.id && abs(o.t - c.t) <= FANOUT_WINDOW_S) srcs.add(o.src)
    }
    srcs.remove(c.src)
    return -0.5 * min(srcs.size.toDouble(), 6.0)
}

/** Identical coordinates asserted by different identities (copy-paste crowd). */
fun <Id : Comparable<Id>, N> duplicateSpot(ledger: Ledger<Id, N>, c: ObservedClaim<Id, N>): Double {
    val srcs = HashSet<N>()
    for (o in ledger.byId.values) {
        if (o.id != c.id && o.src != c.src && dist(o.pos, c.pos) <= SAME_SPOT_M) srcs.add(o.src)
    }
    return -1.0 * min(srcs.size.toDouble(), 3.0)
}

/**
 * Independent devices (other nodes, speaking for themselves) reporting the same place and time.
 * Same-device repeats never count.
 */
fun <Id : Comparable<Id>, N> corroboration(ledger: Ledger<Id, N>, c: ObservedClaim<Id, N>): Double {
    val nodes = HashSet<N>()
    for (o in ledger.byId.values) {
        if (o.node != c.node && o.src == o.node &&
            abs(o.t - c.t) <= CORROB_WINDOW_S && dist(o.pos, c.pos) <= CORROB_M
        ) {
            nodes.add(o.node)
        }
    }
    return 1.5 * min(nodes.size.toDouble(), 3.0)
}

/** Unrefreshed claims lose credibility, capped so old real victims are not written off. */
fun <Id : Comparable<Id>, N> staleness(c: ObservedClaim<Id, N>, now: Double, tau: Double = 1800.0): Double =
    -min(max(0.0, now - c.t) / tau, 1.0)

/**
 * Credential hook. True = valid signed credential matches the need, false = forged or revoked,
 * null = none.
 */
fun <Id : Comparable<Id>, N> credential(
    c: ObservedClaim<Id, N>,
    check: ((ObservedClaim<Id, N>) -> Boolean?)?,
): Double {
    if (check == null) return 0.0
    val r = check(c)
    return if (r == null) 0.0 else if (r) 3.0 else -4.0
}
