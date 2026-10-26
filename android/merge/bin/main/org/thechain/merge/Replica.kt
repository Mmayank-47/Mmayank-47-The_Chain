package org.thechain.merge

import org.thechain.core.ClockDrift
import org.thechain.core.DispatchState
import org.thechain.core.HlClock
import org.thechain.core.MAX_DRIFT_MS
import org.thechain.core.Signer

class Replica(
    val id: String,
    val signer: Signer,
    val nowMs: () -> Long,
    leaseMs: Long = 60_000,
    maxDriftMs: Long? = MAX_DRIFT_MS.toLong(),
) {
    val clock = HlClock(id, nowMs, maxDriftMs)
    var rejectedDrift: Int = 0
    val store = EventStore(signer)
    val book = DispatchBook(leaseMs)
    val onEmit: MutableList<(Event) -> Unit> = mutableListOf()
    val onApply: MutableList<(Event) -> Unit> = mutableListOf()

    private fun emit(kind: String, data: Map<String, Any?>): Event {
        val log = store.logs[id].orEmpty()
        val ev = Event.make(id, log.size, if (log.isEmpty()) "" else log.last().hash, clock.now(), kind, data, signer)
        for (e in store.add(ev)) applied(e)
        for (cb in onEmit) cb(ev)
        return ev
    }

    fun applied(e: Event) {
        book.apply(e)
        for (cb in onApply) cb(e)
    }

    fun emitKind(kind: String, data: Map<String, Any?>): Event = emit(kind, data)

    fun openClaim(claim: String, cell: String, urgency: Double): Event =
        emit("claim", mapOf("claim" to claim, "cell" to cell, "urgency" to urgency))

    fun dispatch(claim: String, responder: String): String {
        val did = makeDid(claim, responder)
        emit("dispatch", mapOf("did" to did))
        return did
    }

    fun setState(did: String, state: DispatchState): Event {
        own(did)
        return emit("state", mapOf("did" to did, "state" to state.wire))
    }

    fun heartbeat(did: String): Event {
        own(did)
        return emit("beat", mapOf("did" to did))
    }

    private fun own(did: String) {
        if (splitDid(did).second != id) throw IllegalArgumentException("only the dispatched responder reports its own progress")
    }

    fun ingest(events: List<Event>): Int {
        var n = 0
        for (ev in events) {
            if (!clock.driftOk(ev.hlc)) {
                rejectedDrift += 1
                continue
            }
            for (e in store.add(ev)) {
                if (e.node != id && clock.driftOk(e.hlc)) {
                    try {
                        clock.update(e.hlc)
                    } catch (_: ClockDrift) {
                    }
                }
                applied(e)
                n += 1
            }
        }
        return n
    }

    fun summary(): Map<String, List<Any>> = store.summary()

    fun eventsFor(remote: Map<String, List<Any>>): List<Event> = store.missing(remote)

    fun plan(now: Long? = null): Plan = book.plan(now ?: nowMs())
}
