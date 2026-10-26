package org.thechain.transport

import org.thechain.core.BloomFilter
import org.thechain.core.Message
import org.thechain.core.Priority
import kotlin.random.Random

data class NeighborInfo(
    val id: String,
    var have: Any = mutableSetOf<String>(),
    var cell: String? = null,
    var battery: Double = 1.0,
    var lastSeen: Double = 0.0,
) {
    fun lacks(mid: String): Boolean = when (val h = have) {
        is BloomFilter -> mid !in h
        is Set<*> -> mid !in h
        else -> true
    }

    fun knows(mid: String): Boolean = !lacks(mid)
}

data class Transmission(
    val to: String,
    val msg: Message,
    val tokens: Int?,
)

/** Adaptive gossip: decide who gets what each tick, within an airtime byte budget. */
class GossipEngine(
    val nodeId: String,
    store: CarryStore? = null,
    policy: SprayPolicy? = null,
    val baseFanout: Int = 3,
    var byteBudget: Int = 4096,
    val resendAfter: Double = 5.0,
    val rng: Random = Random.Default,
    var onDeliver: ((Message) -> Unit)? = null,
    var watchdog: Watchdog? = null,
    var trustFn: ((String, Double) -> Double)? = null,
) {
    val store: CarryStore = store ?: CarryStore()
    val policy: SprayPolicy = policy ?: SprayPolicy()
    val outbox = PriorityOutbox()
    private val sent = mutableMapOf<String, MutableMap<String, Double>>()

    fun originate(msg: Message, now: Double): Boolean {
        if (msg.created == 0.0) msg.created = now
        return store.add(msg, policy.tokensFor(msg.priority), now, fromId = nodeId)
    }

    fun receive(msg: Message, tokens: Int?, now: Double, fromId: String? = null): Boolean {
        if (msg.expired(now)) return false
        if (watchdog != null && fromId != null) {
            watchdog!!.observeForward(fromId, msg.msgId, now)
        }
        if (!store.add(msg, policy.clamp(msg.priority, tokens), now, fromId = fromId)) {
            if (fromId != null) mark(msg.msgId, fromId, now)
            return false
        }
        if (fromId != null) mark(msg.msgId, fromId, now)
        onDeliver?.invoke(msg)
        return true
    }

    private fun mark(mid: String, peer: String, now: Double) {
        sent.getOrPut(mid) { mutableMapOf() }[peer] = now
    }

    private fun recentlySent(mid: String, peer: String, now: Double): Boolean {
        val t = sent[mid]?.get(peer) ?: return false
        return now - t < resendAfter
    }

    /**
     * Adaptive: shrink in dense crowds and on low battery;
     * distress never drops below 2 so it keeps moving.
     */
    fun fanout(nLacking: Int, density: Int, battery: Double, prio: Priority): Int {
        var k = baseFanout
        if (density > 10) k -= 1
        if (battery < 0.5) k -= 1
        if (battery < 0.15) k = 1
        k = maxOf(1, k)
        if (prio == Priority.DISTRESS) k = maxOf(k, 2)
        return minOf(k, nLacking)
    }

    fun step(
        neighbors: Map<String, NeighborInfo>,
        now: Double,
        battery: Double = 1.0,
    ): List<Transmission> {
        store.expire(now)
        for (mid in sent.keys.filter { !store.seen(it) }.toList()) {
            sent.remove(mid)
        }

        val wd = watchdog
        if (wd != null) {
            for ((failedPeer, failedMid, _) in wd.tick(now)) {
                sent[failedMid]?.remove(failedPeer)
                val c = store.get(failedMid)
                if (c != null) {
                    val alt = neighbors.values.filter {
                        it.id != failedPeer && wd.isTrusted(it.id, now)
                    }
                    for (n in alt.take(2)) {
                        outbox.push(n.id to failedMid, c.msg.priority, n.id to failedMid)
                    }
                }
            }
        }

        val density = neighbors.size
        for ((mid, c) in store.items()) {
            var lacking = neighbors.values.filter {
                it.lacks(mid) && !recentlySent(mid, it.id, now)
            }
            if (c.tokens != null && c.tokens!! <= 1) {
                lacking = lacking.filter {
                    c.msg.destCell != null && it.cell == c.msg.destCell
                }
            }
            if (lacking.isEmpty()) continue
            val k = fanout(lacking.size, density, battery, c.msg.priority)
            val sorted = when {
                trustFn != null -> lacking.sortedWith(
                    compareByDescending<NeighborInfo> { trustFn!!(it.id, now) }
                        .thenByDescending { it.battery }
                        .thenBy { rng.nextDouble() },
                )
                wd != null -> lacking.sortedWith(
                    compareByDescending<NeighborInfo> {
                        if (wd.isTrusted(it.id, now)) 1.0 else 0.0
                    }.thenByDescending { it.battery }.thenBy { rng.nextDouble() },
                )
                else -> lacking.sortedWith(
                    compareByDescending<NeighborInfo> { it.battery }
                        .thenBy { rng.nextDouble() },
                )
            }
            for (n in sorted.take(k)) {
                outbox.push(n.id to mid, c.msg.priority, n.id to mid)
            }
        }
        return drain(neighbors, now)
    }

    @Suppress("UNCHECKED_CAST")
    private fun drain(
        neighbors: Map<String, NeighborInfo>,
        now: Double,
    ): List<Transmission> {
        val out = mutableListOf<Transmission>()
        var used = 0
        while (true) {
            val item = outbox.pop() as? Pair<String, String> ?: break
            val (peer, mid) = item
            val n = neighbors[peer]
            val c = store.get(mid)
            if (n == null || c == null || n.knows(mid) || recentlySent(mid, peer, now)) continue
            val size = c.msg.wireBytes().size
            if (out.isNotEmpty() && used + size > byteBudget) {
                outbox.push(item, c.msg.priority, item)
                break
            }
            val (ok, give, keep) = planHandoff(c, n.cell)
            if (!ok) continue
            used += size
            mark(mid, peer, now)
            watchdog?.track(
                peer,
                mid,
                now,
                isCritical = c.msg.priority == Priority.DISTRESS,
            )
            out.add(Transmission(peer, c.msg, give))
            if (keep != null) {
                if (keep <= 0) store.remove(mid) else c.tokens = keep
            }
        }
        return out
    }
}
