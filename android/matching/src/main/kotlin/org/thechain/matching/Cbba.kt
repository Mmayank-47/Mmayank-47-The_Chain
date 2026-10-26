package org.thechain.matching

import org.thechain.core.Signer
import org.thechain.core.canonical
import kotlin.math.abs

data class Entry(
    val epoch: Int,
    val task: String?,
    val bid: Double,
    val sig: String? = null,
)

/** Canonical bytes for signing an auction bid. Bid is round-half-even to 9 decimal places. */
fun entryDigest(agentId: String, epoch: Int, task: String?, bid: Double): ByteArray =
    canonical(listOf(agentId, epoch, task, pythonRound(bid, 9)))

private fun beats(bid: Double, who: String, otherBid: Double, otherWho: String): Boolean =
    bid > otherBid + 1e-12 || (abs(bid - otherBid) <= 1e-12 && who < otherWho)

/**
 * CBBA bidder, bundle size 1. [signer] is optional; when set, own entries are signed and
 * incoming entries are dropped unless the author signature verifies.
 */
class Bidder(
    val id: String,
    utilities: Map<String, Double>,
    val signer: Signer? = null,
) {
    var utilities: Map<String, Double> = utilities.toMap()
        private set
    val table: MutableMap<String, Entry> = linkedMapOf(id to makeEntry(0, null, 0.0))

    private fun makeEntry(epoch: Int, task: String?, bid: Double): Entry {
        val sig = signer?.sign(id, entryDigest(id, epoch, task, bid))
        return Entry(epoch, task, bid, sig)
    }

    fun setUtilities(u: Map<String, Double>) {
        utilities = u.toMap()
    }

    /** Lease expired or agent gone: free whatever it was holding. */
    fun forget(agent: String) {
        if (agent != id) table.remove(agent)
    }

    private fun bestOther(task: String): Pair<Double, String>? {
        var best: Pair<Double, String>? = null
        for ((agent, entry) in table) {
            if (agent != id && entry.task == task && (best == null || beats(entry.bid, agent, best.first, best.second))) {
                best = entry.bid to agent
            }
        }
        return best
    }

    fun merge(incoming: Map<String, Entry>): Boolean {
        var changed = false
        val verifier = signer
        for ((agent, entry) in incoming) {
            if (agent == id) continue
            if (verifier != null) {
                val sig = entry.sig
                if (sig.isNullOrEmpty() || !verifier.verify(agent, entryDigest(agent, entry.epoch, entry.task, entry.bid), sig)) {
                    continue
                }
            }
            val cur = table[agent]
            if (cur == null || entry.epoch > cur.epoch) {
                table[agent] = entry
                changed = true
            }
        }
        return changed
    }

    fun step(): Boolean {
        val own = table.getValue(id)
        if (own.task != null) {
            val bid = utilities[own.task] ?: Double.NEGATIVE_INFINITY
            val other = bestOther(own.task)
            if (bid > 0.0 && (other == null || beats(bid, id, other.first, other.second))) {
                if (abs(bid - own.bid) > 1e-12) {
                    table[id] = makeEntry(own.epoch + 1, own.task, bid)
                    return true
                }
                return false
            }
        }
        var best: Pair<Double, String>? = null
        for ((task, bid) in utilities) {
            if (bid <= 0.0) continue
            val other = bestOther(task)
            if (other != null && !beats(bid, id, other.first, other.second)) continue
            if (best == null || bid > best.first + 1e-12 || (abs(bid - best.first) <= 1e-12 && task < best.second)) {
                best = bid to task
            }
        }
        val target = if (best != null) {
            makeEntry(own.epoch + 1, best.second, best.first)
        } else {
            makeEntry(own.epoch + 1, null, 0.0)
        }
        if (target.task == own.task && target.bid == own.bid) return false
        table[id] = target
        return true
    }

    /** task -> owning agent, as far as this phone knows. */
    fun assignment(): Map<String, String> {
        val own = LinkedHashMap<String, Pair<Double, String>>()
        for ((agent, entry) in table) {
            val task = entry.task ?: continue
            val cur = own[task]
            if (cur == null || beats(entry.bid, agent, cur.first, cur.second)) {
                own[task] = entry.bid to agent
            }
        }
        return own.mapValues { it.value.second }
    }

    fun myTask(): String? {
        val task = table.getValue(id).task ?: return null
        return if (assignment()[task] == id) task else null
    }
}

/** Synchronous rounds over [links] until nothing changes. Returns rounds used. */
fun gossip(
    bidders: Map<String, Bidder>,
    links: Iterable<Pair<String, String>>,
    maxRounds: Int = 100,
): Int {
    val linkList = links.toList()
    for (rnd in 1..maxRounds) {
        var changed = false
        val snap = LinkedHashMap<String, Map<String, Entry>>()
        for ((agent, bidder) in bidders) snap[agent] = LinkedHashMap(bidder.table)
        for ((left, right) in linkList) {
            if (bidders.getValue(left).merge(snap.getValue(right))) changed = true
            if (bidders.getValue(right).merge(snap.getValue(left))) changed = true
        }
        for (bidder in bidders.values) {
            if (bidder.step()) changed = true
        }
        if (!changed) return rnd
    }
    throw RuntimeException("auction did not converge")
}
