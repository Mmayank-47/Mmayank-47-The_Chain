"""MeshNode: beacons + neighbor table + gossip engine on top of any Radio."""
from __future__ import annotations

import json
import random
from typing import Callable, Dict, Optional

from core import crypto
from core.bloom import BloomFilter
from core.types import Message, Priority, canonical
from .gossip import GossipEngine, NeighborInfo
from .ingress import AE_FRAMES, IngressGuard
from .profile import AirtimeTracker, RadioProfile
from .radio_adapter import Radio, SimMedium, SimRadio
from .wire import Fragmenter, WireCodec


class MeshNode:
    def __init__(self, node_id: str, radio: Radio, engine: GossipEngine, *,
                 hello_interval: float = 2.0, neighbor_timeout: float = 6.0,
                 get_cell: Callable[[], Optional[str]] = lambda: None,
                 get_battery: Callable[[], float] = lambda: 1.0,
                 guard: Optional[IngressGuard] = None,
                 signer: Optional[crypto.Signer] = None,
                 compact_wire: bool = False,
                 profile: Optional[RadioProfile] = None,
                 airtime_tracker: Optional[AirtimeTracker] = None):
        """guard: ingress filter for everything off the air (see ingress.py). None = trust the wire,
        which is only for unit tests of the gossip logic itself."""
        self.id, self.radio, self.engine, self.guard = node_id, radio, engine, guard
        self.signer = signer
        self.hello_interval, self.neighbor_timeout = hello_interval, neighbor_timeout
        self.get_cell, self.get_battery = get_cell, get_battery
        self.neighbors: Dict[str, NeighborInfo] = {}
        # frame type -> handler(from_id, frame, now) for the anti-entropy frames in ingress.AE_FRAMES.
        # With a guard they only get here after IngressGuard.ae_frame() has admitted them.
        self.frame_handlers: Dict[str, Callable[[str, dict, float], None]] = {}
        self._last_hello = -1e9
        self._seq = 0
        self.compact_wire = compact_wire
        self.fragmenter = Fragmenter()
        self.airtime_tracker = airtime_tracker or (AirtimeTracker(profile) if profile else None)

    def originate(self, msg: Message, now: float) -> bool:
        return self.engine.originate(msg, now)

    def _send_frame(self, to: Optional[str], frame: dict, now: float, broadcast: bool = False,
                    prio: Priority = Priority.CHATTER):
        if self.signer is not None:
            self._seq += 1
            frame["snd"] = self.id
            frame["seq"] = self._seq
            body = {k: v for k, v in frame.items() if k != "sig"}
            frame["sig"] = self.signer.sign(self.id, canonical(body))

        if self.compact_wire:
            raw_payload = WireCodec.encode(frame, self.signer)
            packets = self.fragmenter.fragment(raw_payload, self.id)
        else:
            packets = [json.dumps(frame).encode()]

        for pkt in packets:
            if self.airtime_tracker is not None:
                if not self.airtime_tracker.can_transmit(len(pkt), now, priority=prio):
                    continue
                self.airtime_tracker.record_tx(len(pkt), now)

            if broadcast:
                self.radio.broadcast(pkt, now)
            else:
                self.radio.send(to, pkt, now)

    def tick(self, now: float):
        if now - self._last_hello >= self.hello_interval:
            hello = {"t": "hello", "id": self.id, "cell": self.get_cell(),
                     "bat": self.get_battery(), "bf": self.engine.store.bloom().to_json()}
            self._send_frame(None, hello, now, broadcast=True, prio=Priority.HAZARD)
            self._last_hello = now
        for frm, data in self.radio.poll(now):
            self._handle(frm, data, now)
        for pid in [p for p, n in self.neighbors.items()
                    if now - n.last_seen > self.neighbor_timeout]:
            del self.neighbors[pid]
        for tx in self.engine.step(self.neighbors, now, self.get_battery()):
            frame = {"t": "data", "m": tx.msg.to_wire(), "tok": tx.tokens}
            self._send_frame(tx.to, frame, now, broadcast=False, prio=tx.msg.priority)

    def _handle(self, frm: str, data: bytes, now: float):
        g = self.guard
        # Check reassembly if packet is a fragment
        data = self.fragmenter.reassemble(data, now)
        if data is None:
            return  # Incomplete fragments, waiting for remainder

        if g is not None and not g.frame_ok(data):
            return
        try:
            f = WireCodec.decode(data) if (data and data[0] == 0xC1) else json.loads(data)
            if not f:
                return
            auth_sender = g.authenticate_frame(frm, f) if g is not None else frm
            if auth_sender is None:
                return
            sender_id = auth_sender

            if f["t"] == "hello":
                if g is not None:
                    h = g.hello(f, len(data))
                    if h is None:
                        return
                    self.neighbors[sender_id] = NeighborInfo(sender_id, h[0], h[1], h[2], now)
                else:
                    self.neighbors[sender_id] = NeighborInfo(sender_id, BloomFilter.from_json(f["bf"]),
                                                       f.get("cell"), float(f.get("bat", 1.0)), now)
            elif f["t"] == "data":
                if sender_id in self.neighbors:
                    self.neighbors[sender_id].last_seen = now
                msg = Message.from_wire(f["m"])
                if g is not None:
                    if len(data) > g.limits.max_frame_bytes:
                        g.dropped["frame_size"] += 1
                        return
                    # The rate limiter keys on the authenticated identity
                    msg = g.admit(msg, sender_id, now)
                    if msg is None:
                        return
                self.engine.receive(msg, f.get("tok"), now, sender_id)
            elif f["t"] in AE_FRAMES:
                if g is not None:
                    f = g.ae_frame(f, len(data), sender_id, now)
                    if f is None:
                        return
                handler = self.frame_handlers.get(f["t"])
                if handler is not None:
                    handler(sender_id, f, now)
        except (ValueError, KeyError, TypeError):
            pass    # malformed frame from a hostile peer: drop silently


def build_sim_node(node_id: str, medium: SimMedium, *, seed: int = 0,
                   on_deliver=None, signer=None, **kw) -> MeshNode:
    eng = GossipEngine(node_id, rng=random.Random(f"{seed}:{node_id}"), on_deliver=on_deliver)
    return MeshNode(node_id, SimRadio(node_id, medium), eng, signer=signer, **kw)
