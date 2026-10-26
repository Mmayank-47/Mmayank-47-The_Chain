package org.thechain.merge

import org.thechain.core.DispatchState
import org.thechain.core.Hlc
import org.thechain.core.LwwMap
import kotlin.math.max

fun <T> expired(recs: Iterable<T>, nowMs: Long, leaseMs: Long, state: (T) -> DispatchState, alive: (T) -> Long, did: (T) -> String): List<T> =
    recs.filter { state(it) != DispatchState.RESOLVED && !isLive(alive(it), nowMs, leaseMs) }.sortedBy { did(it) }

fun makeDid(claim: String, responder: String): String = "$claim|$responder"

fun splitDid(did: String): Pair<String, String> {
    val i = did.lastIndexOf('|')
    require(i > 0) { "bad did" }
    return did.substring(0, i) to did.substring(i + 1)
}

data class Rec(
    val did: String,
    val claim: String,
    val responder: String,
    var state: DispatchState = DispatchState.PROPOSED,
    var created: Hlc? = null,
    var beatMs: Long = 0,
) {
    fun aliveMs(): Long = org.thechain.merge.aliveMs(created?.wall ?: 0L, beatMs)
}

data class Plan(
    val winners: MutableMap<String, String> = linkedMapOf(),
    val redirect: MutableList<String> = mutableListOf(),
    val reopen: MutableList<String> = mutableListOf(),
)

private val INF = Hlc(1L shl 62, 0, "")

class DispatchBook(val leaseMs: Long = 60_000) {
    val claims = LwwMap()
    val recs: MutableMap<String, Rec> = linkedMapOf()

    fun apply(ev: Event) {
        val d = ev.data
        when (ev.kind) {
            "claim" -> claims.set(d["claim"] as String, mapOf("cell" to d["cell"], "urgency" to d["urgency"]), ev.hlc)
            "dispatch", "state", "beat" -> {
                val (claim, resp) = splitDid(d["did"] as String)
                if (ev.kind != "dispatch" && ev.node != resp) return
                val r = recs.getOrPut(d["did"] as String) { Rec(d["did"] as String, claim, resp) }
                if (ev.kind == "dispatch") {
                    r.created = if (r.created == null) ev.hlc else minOf(r.created!!, ev.hlc)
                } else {
                    r.beatMs = max(r.beatMs, ev.hlc.wall)
                    if (ev.kind == "state") r.state = maxOf(r.state, DispatchState.fromWire((d["state"] as Number).toInt()))
                }
            }
        }
    }

    fun plan(nowMs: Long): Plan {
        val p = Plan()
        val byClaim = linkedMapOf<String, MutableList<Rec>>()
        for (r in recs.values) byClaim.getOrPut(r.claim) { mutableListOf() }.add(r)
        for ((claim, rs) in byClaim) {
            if (rs.any { it.state == DispatchState.RESOLVED }) continue
            val live = rs.filter { isLive(it.aliveMs(), nowMs, leaseMs) }
            if (live.isEmpty()) {
                p.reopen.add(claim)
                continue
            }
            val win = live.minWith(compareBy({ -it.state.wire }, { it.created ?: INF }, { it.responder }))
            p.winners[claim] = win.did
            p.redirect.addAll(live.filter { it !== win && it.state < DispatchState.ARRIVED }.map { it.did })
        }
        return p
    }

    fun expiredDispatches(nowMs: Long): List<Rec> =
        expired(recs.values, nowMs, leaseMs, { it.state }, { it.aliveMs() }, { it.did })
}
