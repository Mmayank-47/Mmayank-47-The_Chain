package org.thechain.merge

import org.thechain.core.Dispatcher
import org.thechain.core.Handler
import org.thechain.core.Message
import org.thechain.core.eventPriority
import org.thechain.transport.MeshNode

const val M8_KIND = "m8.event"

fun toMessage(ev: Event, now: Double): Message {
    val prio = eventPriority(ev.kind)
    return Message(M8_KIND, prio, ev.node, ev.toJson(), ev.hlc, created = now)
}

/**
 * Wire an M8 [Replica] onto a [MeshNode]: local emits become epidemic messages; deliveries of
 * [M8_KIND] are ingested. With a [Dispatcher], deliveries are routed by kind.
 */
fun attach(replica: Replica, node: MeshNode, dispatcher: Dispatcher? = null) {
    replica.onEmit.add { ev ->
        val now = replica.nowMs() / 1000.0
        node.originate(toMessage(ev, now), now)
    }
    val deliver = Handler { msg ->
        if (msg.kind == M8_KIND) {
            replica.ingest(listOf(Event.fromJson(msg.payload)))
        }
    }
    if (dispatcher == null) {
        node.engine.onDeliver = { deliver.on(it) }
    } else {
        dispatcher.register(M8_KIND, deliver)
        node.engine.onDeliver = { dispatcher(it) }
    }
}
