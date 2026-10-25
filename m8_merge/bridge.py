"""Glue: M8 events ride M0 as Messages (epidemic flood, so partitions heal when carried across)."""
from __future__ import annotations

from core.types import Message, event_priority
from .event_log import Event
from .replica import Replica

KIND = "m8.event"


def to_message(ev: Event, now: float) -> Message:
    prio = event_priority(ev.kind)   # same rule M0's ingress applies, so both ends agree
    return Message(KIND, prio, ev.node, ev.to_json(), ev.hlc, created=now)


def attach(replica: Replica, node, dispatcher=None) -> None:
    """node: m0_transport.node.MeshNode. With a core.dispatcher.Dispatcher the node's deliveries are
    routed by kind (and other layers can register next to M8); without one this keeps the old
    behaviour of claiming node.engine.on_deliver outright."""
    replica.on_emit.append(lambda ev: node.originate(to_message(ev, replica.now_ms() / 1000), replica.now_ms() / 1000))

    def deliver(msg: Message):
        if msg.kind == KIND:
            replica.ingest([Event.from_json(msg.payload)])
    if dispatcher is None:
        node.engine.on_deliver = deliver
    else:
        dispatcher.register(KIND, deliver)
        node.engine.on_deliver = dispatcher
