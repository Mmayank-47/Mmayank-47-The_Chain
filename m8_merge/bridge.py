"""Glue: M8 events ride M0 as Messages (epidemic flood, so partitions heal when carried across)."""
from __future__ import annotations

from core.types import Message, Priority
from .event_log import Event
from .replica import Replica

KIND = "m8.event"


def to_message(ev: Event, now: float) -> Message:
    prio = Priority.DISTRESS if ev.kind == "claim" else Priority.DISPATCH
    return Message(KIND, prio, ev.node, ev.to_json(), ev.hlc, created=now)


def attach(replica: Replica, node) -> None:
    """node: m0_transport.node.MeshNode"""
    replica.on_emit.append(lambda ev: node.originate(to_message(ev, replica.now_ms() / 1000), replica.now_ms() / 1000))

    def deliver(msg: Message):
        if msg.kind == KIND:
            replica.ingest([Event.from_json(msg.payload)])
    node.engine.on_deliver = deliver
