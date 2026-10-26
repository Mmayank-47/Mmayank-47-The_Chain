package org.thechain.merge

import org.thechain.core.Hlc
import org.thechain.core.Signer
import org.thechain.core.canonical
import org.thechain.core.sha256Hex

fun eventBody(node: String, seq: Int, prev: String, hlc: Hlc, kind: String, data: Map<String, Any?>): Map<String, Any?> =
    mapOf("n" to node, "s" to seq, "p" to prev, "h" to hlc.toJson(), "k" to kind, "d" to data)

data class Event(
    val node: String,
    val seq: Int,
    val prev: String,
    val hlc: Hlc,
    val kind: String,
    val data: Map<String, Any?>,
    val hash: String,
    val sig: String,
) {
    fun toJson(): Map<String, Any?> = eventBody(node, seq, prev, hlc, kind, data) + mapOf("x" to hash, "g" to sig)

    companion object {
        fun make(node: String, seq: Int, prev: String, hlc: Hlc, kind: String, data: Map<String, Any?>, signer: Signer): Event {
            val hash = sha256Hex(canonical(eventBody(node, seq, prev, hlc, kind, data)))
            return Event(node, seq, prev, hlc, kind, data, hash, signer.sign(node, hash.toByteArray(Charsets.UTF_8)))
        }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(j: Map<String, Any?>): Event = Event(
            j["n"] as String,
            (j["s"] as Number).toInt(),
            j["p"] as String,
            Hlc.fromJson(j["h"] as List<*>),
            j["k"] as String,
            j["d"] as Map<String, Any?>,
            j["x"] as String,
            j["g"] as String,
        )
    }
}

class EventStore(val signer: Signer, val maxPending: Int = 256) {
    val logs: MutableMap<String, MutableList<Event>> = linkedMapOf()
    val pending: MutableMap<String, MutableMap<Int, Event>> = linkedMapOf()
    val forks: MutableSet<String> = linkedSetOf()
    val forkEvidence: MutableMap<String, Pair<Event, Event>> = linkedMapOf()
    val suspects: MutableSet<String> = linkedSetOf()
    val onFork: MutableList<(String) -> Unit> = mutableListOf()
    var droppedQuarantined: Int = 0

    private fun valid(ev: Event): Boolean {
        if (ev.node.isEmpty() || ev.seq < 0) return false
        val body = eventBody(ev.node, ev.seq, ev.prev, ev.hlc, ev.kind, ev.data)
        return sha256Hex(canonical(body)) == ev.hash && signer.verify(ev.node, ev.hash.toByteArray(Charsets.UTF_8), ev.sig)
    }

    private fun fork(node: String, ours: Event, theirs: Event) {
        if (node in forks) return
        forks.add(node)
        forkEvidence[node] = ours to theirs
        pending.remove(node)
        for (cb in onFork.toList()) {
            try {
                cb(node)
            } catch (_: Exception) {
            }
        }
    }

    fun add(ev: Event): List<Event> {
        if (!valid(ev)) return emptyList()
        if (ev.node in forks) {
            droppedQuarantined += 1
            return emptyList()
        }
        val log = logs.getOrPut(ev.node) { mutableListOf() }
        if (ev.seq < log.size) {
            if (log[ev.seq].hash != ev.hash) fork(ev.node, log[ev.seq], ev)
            return emptyList()
        }
        if (ev.seq > log.size) {
            val p = pending.getOrPut(ev.node) { linkedMapOf() }
            if (p.size < maxPending) p.putIfAbsent(ev.seq, ev)
            return emptyList()
        }
        val out = mutableListOf<Event>()
        var cur: Event? = ev
        while (cur != null) {
            val expect = if (log.isEmpty()) "" else log.last().hash
            if (cur.prev != expect) {
                fork(ev.node, if (log.isEmpty()) cur else log.last(), cur)
                break
            }
            log.add(cur)
            out.add(cur)
            cur = pending[ev.node]?.remove(log.size)
        }
        return out
    }

    fun summary(): Map<String, List<Any>> =
        logs.filter { it.value.isNotEmpty() }.mapValues { (_, l) -> listOf(l.size, l.last().hash) }

    fun missing(remote: Map<String, List<Any>>): List<Event> {
        val out = mutableListOf<Event>()
        for ((node, log) in logs) {
            val slot = remote[node]
            val rc = (slot?.getOrNull(0) as? Number)?.toInt() ?: 0
            val rh = slot?.getOrNull(1) as? String ?: ""
            if (rc > 0 && rc <= log.size && log[rc - 1].hash != rh) suspects.add(node)
            if (rc < log.size) out.addAll(log.subList(rc, log.size))
        }
        return out
    }
}
