package org.thechain.transport

import org.thechain.core.BloomFilter
import org.thechain.core.Message
import org.thechain.core.Priority

data class Carried(
    val msg: Message,
    var tokens: Int?,
    val received: Double,
    val fromId: String? = null,
)

class SprayPolicy(table: Map<Priority, Int?>? = null) {
    val table: Map<Priority, Int?> = table ?: DEFAULT

    fun tokensFor(p: Priority): Int? = table[p]

    /**
     * Never trust a peer's claimed token count above our own policy.
     * Epidemic classes carry no tokens; malformed spray counts get the cap.
     */
    fun clamp(p: Priority, incoming: Int?): Int? {
        val cap = tokensFor(p) ?: return null
        if (incoming == null || incoming < 1) return cap
        return minOf(incoming, cap)
    }

    companion object {
        val DEFAULT: Map<Priority, Int?> = mapOf(
            Priority.DISTRESS to null,
            Priority.DISPATCH to null,
            Priority.HAZARD to null,
            Priority.CHATTER to 4,
        )
    }
}

/** -> (send?, tokensGiven, tokensKept). */
fun planHandoff(c: Carried, peerCell: String?): Triple<Boolean, Int?, Int?> {
    val t = c.tokens
    if (t == null) return Triple(true, null, null)
    if (t > 1) {
        val give = t / 2
        return Triple(true, give, t - give)
    }
    if (c.msg.destCell != null && peerCell == c.msg.destCell) {
        return Triple(true, 1, 0)
    }
    return Triple(false, 0, t)
}

/**
 * Bounded buffer of messages this phone is physically carrying
 * with per-origin and per-neighbour quotas.
 */
class CarryStore(
    val capacity: Int = 512,
    val maxPerOrigin: Int? = 32,
    val maxPerNeighbour: Int? = 64,
) {
    private val c = linkedMapOf<String, Carried>()
    private val seenExpiry = linkedMapOf<String, Double>()
    private val originCounts = mutableMapOf<String, Int>()
    private val neighbourCounts = mutableMapOf<String, Int>()

    val size: Int get() = c.size

    fun get(mid: String): Carried? = c[mid]

    fun items(): List<Pair<String, Carried>> = c.entries.map { it.key to it.value }

    fun seen(mid: String): Boolean = mid in seenExpiry

    fun originCount(origin: String): Int = originCounts[origin] ?: 0

    fun neighbourCount(neighbour: String): Int = neighbourCounts[neighbour] ?: 0

    fun add(
        msg: Message,
        tokens: Int?,
        now: Double = 0.0,
        fromId: String? = null,
    ): Boolean {
        if (msg.msgId in seenExpiry) return false
        if (maxPerOrigin != null && (originCounts[msg.origin] ?: 0) >= maxPerOrigin) {
            if (!evictForOrigin(msg)) return false
        }
        if (fromId != null && maxPerNeighbour != null &&
            (neighbourCounts[fromId] ?: 0) >= maxPerNeighbour
        ) {
            if (!evictForNeighbour(fromId, msg)) return false
        }
        if (c.size >= capacity && !evictFor(msg)) return false
        c[msg.msgId] = Carried(msg, tokens, now, fromId)
        seenExpiry[msg.msgId] = msg.created + msg.ttlS
        originCounts[msg.origin] = (originCounts[msg.origin] ?: 0) + 1
        if (fromId != null) {
            neighbourCounts[fromId] = (neighbourCounts[fromId] ?: 0) + 1
        }
        return true
    }

    private fun worstKey(candidates: Collection<Carried>): Carried =
        candidates.maxWith(compareBy({ it.msg.priority.ordinal }, { -it.received }))

    private fun evictFor(msg: Message): Boolean {
        if (c.isEmpty()) return false
        val worst = worstKey(c.values)
        if (worst.msg.priority.ordinal <= msg.priority.ordinal) return false
        removeEntry(worst.msg.msgId)
        return true
    }

    private fun evictForOrigin(msg: Message): Boolean {
        val candidates = c.values.filter { it.msg.origin == msg.origin }
        if (candidates.isEmpty()) return false
        val worst = worstKey(candidates)
        if (worst.msg.priority.ordinal <= msg.priority.ordinal) return false
        removeEntry(worst.msg.msgId)
        return true
    }

    private fun evictForNeighbour(fromId: String, msg: Message): Boolean {
        val candidates = c.values.filter { it.fromId == fromId }
        if (candidates.isEmpty()) return false
        val worst = worstKey(candidates)
        if (worst.msg.priority.ordinal <= msg.priority.ordinal) return false
        removeEntry(worst.msg.msgId)
        return true
    }

    private fun removeEntry(mid: String): Carried? {
        val removed = c.remove(mid) ?: return null
        val oc = (originCounts[removed.msg.origin] ?: 1) - 1
        if (oc <= 0) originCounts.remove(removed.msg.origin) else originCounts[removed.msg.origin] = oc
        val fid = removed.fromId
        if (fid != null) {
            val nc = (neighbourCounts[fid] ?: 1) - 1
            if (nc <= 0) neighbourCounts.remove(fid) else neighbourCounts[fid] = nc
        }
        return removed
    }

    fun remove(mid: String) {
        removeEntry(mid)
    }

    fun expire(now: Double) {
        val dead = c.filter { (_, carried) -> carried.msg.expired(now) }.keys.toList()
        for (mid in dead) removeEntry(mid)
        val stale = seenExpiry.filter { (_, e) -> now > e }.keys.toList()
        for (mid in stale) seenExpiry.remove(mid)
    }

    fun bloom(): BloomFilter {
        val b = BloomFilter.forCapacity(c.size)
        for (mid in c.keys) b.add(mid)
        return b
    }
}
