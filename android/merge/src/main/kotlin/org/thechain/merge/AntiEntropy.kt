package org.thechain.merge

/**
 * Rate-limited summary exchange with batched backfill, transport-agnostic.
 * Frames are plain maps; the caller moves them over M0.
 */
class AntiEntropy(
    val replica: Replica,
    val minInterval: Double = 5.0,
    val batch: Int = 32,
    val maxNodes: Int = 512,
    val maxPeers: Int = 256,
    val maxBacklog: Int = 4096,
) {
    private val sent = linkedMapOf<String, Double>()
    private val recv = linkedMapOf<String, Double>()
    private val backlog = linkedMapOf<String, MutableList<Event>>()
    val dropped: MutableMap<String, Int> = mutableMapOf()

    private fun touch(table: MutableMap<String, *>, peer: String, value: Any) {
        @Suppress("UNCHECKED_CAST")
        val t = table as MutableMap<String, Any>
        if (peer !in t && t.size >= maxPeers) {
            t.remove(t.keys.first())
        }
        t[peer] = value
    }

    private fun due(table: Map<String, Double>, peer: String, now: Double): Boolean {
        val last = table[peer] ?: return true
        return now - last >= minInterval
    }

    private fun validSummary(s: Any?): Boolean {
        if (s !is Map<*, *> || s.size > maxNodes) return false
        for ((node, v) in s) {
            if (node !is String || node.isEmpty() || node.length > 64) return false
            val pair = when (v) {
                is List<*> -> v
                is Pair<*, *> -> listOf(v.first, v.second)
                else -> return false
            }
            if (pair.size != 2) return false
            val n = pair[0]
            val h = pair[1]
            if (n is Boolean || n !is Number || n.toInt() < 0 || h !is String || h.length > 64) return false
        }
        return true
    }

    /** Summary frame for [peer], or null if we asked them less than [minInterval] ago. */
    fun request(peer: String, now: Double): Map<String, Any?>? {
        if (!due(sent, peer, now)) return null
        touch(sent, peer, now)
        return mapOf("t" to "ae.sum", "s" to replica.summary())
    }

    fun onSummary(peer: String, frame: Map<String, Any?>, now: Double): Map<String, Any?>? {
        val s = frame["s"]
        if (!validSummary(s)) {
            bump("bad_summary")
            return null
        }
        if (!due(recv, peer, now)) {
            bump("summary_rate")
            return null
        }
        touch(recv, peer, now)
        @Suppress("UNCHECKED_CAST")
        val remote = (s as Map<String, Any?>).mapValues { (_, v) ->
            when (v) {
                is List<*> -> v.map { it as Any }
                is Pair<*, *> -> listOf(v.first as Any, v.second as Any)
                else -> error("unreachable")
            }
        }
        val missing = replica.eventsFor(remote)
        touch(backlog, peer, missing.take(maxBacklog).toMutableList())
        return nextBatch(peer)
    }

    fun nextBatch(peer: String): Map<String, Any?>? {
        val q = backlog[peer] ?: return null
        if (q.isEmpty()) {
            backlog.remove(peer)
            return null
        }
        val head = q.take(batch)
        val rest = q.drop(batch)
        if (rest.isNotEmpty()) backlog[peer] = rest.toMutableList() else backlog.remove(peer)
        return mapOf("t" to "ae.ev", "e" to head.map { it.toJson() }, "more" to rest.isNotEmpty())
    }

    fun pendingFor(peer: String): Int = backlog[peer]?.size ?: 0

    fun onEvents(peer: String, frame: Map<String, Any?>, now: Double = 0.0): Int {
        val raw = frame["e"]
        if (raw !is List<*>) {
            bump("bad_events")
            return 0
        }
        if (raw.size > batch) {
            bump("oversize_batch")
            return 0
        }
        val evs = ArrayList<Event>()
        for (j in raw) {
            try {
                @Suppress("UNCHECKED_CAST")
                evs.add(Event.fromJson(j as Map<String, Any?>))
            } catch (_: Exception) {
                bump("bad_event")
            }
        }
        return replica.ingest(evs)
    }

    private fun bump(why: String) {
        dropped[why] = (dropped[why] ?: 0) + 1
    }
}
