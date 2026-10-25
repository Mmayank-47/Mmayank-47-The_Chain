"""MeshNode: beacons + neighbor table + gossip engine on top of any Radio."""
from __future__ import annotations

import json
import random
from typing import Callable, Dict, Optional

from core.bloom import BloomFilter
from core.types import Message
from .gossip import GossipEngine, NeighborInfo
from .ingress import IngressGuard
from .radio_adapter import Radio, SimMedium, SimRadio


class MeshNode:
    def __init__(self, node_id: str, radio: Radio, engine: GossipEngine, *,
                 hello_interval: float = 2.0, neighbor_timeout: float = 6.0,
                 get_cell: Callable[[], Optional[str]] = lambda: None,
                 get_battery: Callable[[], float] = lambda: 1.0,
                 guard: Optional[IngressGuard] = None):
        """guard: ingress filter for everything off the air (see ingress.py). None = trust the wire,
        which is only for unit tests of the gossip logic itself."""
        self.id, self.radio, self.engine, self.guard = node_id, radio, engine, guard
        self.hello_interval, self.neighbor_timeout = hello_interval, neighbor_timeout
        self.get_cell, self.get_battery = get_cell, get_battery
        self.neighbors: Dict[str, NeighborInfo] = {}
        self._last_hello = -1e9

    def originate(self, msg: Message, now: float) -> bool:
        return self.engine.originate(msg, now)

    def tick(self, now: float):
        if now - self._last_hello >= self.hello_interval:
            hello = {"t": "hello", "id": self.id, "cell": self.get_cell(),
                     "bat": self.get_battery(), "bf": self.engine.store.bloom().to_json()}
            self.radio.broadcast(json.dumps(hello).encode(), now)
            self._last_hello = now
        for frm, data in self.radio.poll(now):
            self._handle(frm, data, now)
        for pid in [p for p, n in self.neighbors.items()
                    if now - n.last_seen > self.neighbor_timeout]:
            del self.neighbors[pid]
        for tx in self.engine.step(self.neighbors, now, self.get_battery()):
            frame = {"t": "data", "m": tx.msg.to_wire(), "tok": tx.tokens}
            self.radio.send(tx.to, json.dumps(frame).encode(), now)

    def _handle(self, frm: str, data: bytes, now: float):
        g = self.guard
        if g is not None and not g.frame_ok(data):
            return
        try:
            f = json.loads(data)
            if f["t"] == "hello":
                if g is not None:
                    h = g.hello(f, len(data))
                    if h is None:
                        return
                    self.neighbors[frm] = NeighborInfo(frm, h[0], h[1], h[2], now)
                else:
                    self.neighbors[frm] = NeighborInfo(frm, BloomFilter.from_json(f["bf"]),
                                                       f.get("cell"), float(f.get("bat", 1.0)), now)
            elif f["t"] == "data":
                if frm in self.neighbors:
                    self.neighbors[frm].last_seen = now
                msg = Message.from_wire(f["m"])
                if g is not None:
                    if len(data) > g.limits.max_frame_bytes:
                        g.dropped["frame_size"] += 1
                        return
                    msg = g.admit(msg, frm, now)
                    if msg is None:
                        return
                self.engine.receive(msg, f.get("tok"), now, frm)
        except (ValueError, KeyError, TypeError):
            pass    # malformed frame from a hostile peer: drop silently


def build_sim_node(node_id: str, medium: SimMedium, *, seed: int = 0,
                   on_deliver=None, **kw) -> MeshNode:
    eng = GossipEngine(node_id, rng=random.Random(f"{seed}:{node_id}"), on_deliver=on_deliver)
    return MeshNode(node_id, SimRadio(node_id, medium), eng, **kw)
