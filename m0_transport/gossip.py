"""Adaptive gossip: decide who gets what each tick, within an airtime byte budget."""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Optional, Set

from core.bloom import BloomFilter
from core.types import Message, Priority
from .dtn import CarryStore, SprayPolicy, plan_handoff
from .priority_queue import PriorityOutbox


@dataclass
class NeighborInfo:
    id: str
    have: object = field(default_factory=set)   # BloomFilter or set of msg_ids
    cell: Optional[str] = None
    battery: float = 1.0
    last_seen: float = 0.0


@dataclass
class Transmission:
    to: str
    msg: Message
    tokens: Optional[int]


class GossipEngine:
    def __init__(self, node_id: str, *, store: Optional[CarryStore] = None,
                 policy: Optional[SprayPolicy] = None, base_fanout: int = 3,
                 byte_budget: int = 4096, resend_after: float = 5.0,
                 rng: Optional[random.Random] = None,
                 on_deliver: Optional[Callable[[Message], None]] = None,
                 watchdog: Optional[Any] = None,
                 trust_fn: Optional[Callable[[str, float], float]] = None):
        self.node_id = node_id
        self.store = store or CarryStore()
        self.policy = policy or SprayPolicy()
        self.base_fanout = base_fanout
        self.byte_budget = byte_budget
        self.resend_after = resend_after
        self.rng = rng or random.Random()
        self.on_deliver = on_deliver
        self.watchdog = watchdog
        self.trust_fn = trust_fn
        self.outbox = PriorityOutbox()
        self._sent: Dict[str, Dict[str, float]] = {}   # msg_id -> peer -> time

    # ---- ingress -------------------------------------------------------
    def originate(self, msg: Message, now: float) -> bool:
        if not msg.created:
            msg.created = now
        return self.store.add(msg, self.policy.tokens_for(msg.priority), now, from_id=self.node_id)

    def receive(self, msg: Message, tokens: Optional[int], now: float,
                from_id: Optional[str] = None) -> bool:
        if msg.expired(now):
            return False
        if self.watchdog is not None and from_id is not None:
            self.watchdog.observe_forward(from_id, msg.msg_id, now)
        if not self.store.add(msg, self.policy.clamp(msg.priority, tokens), now, from_id=from_id):
            if from_id:
                self._mark(msg.msg_id, from_id, now)
            return False
        if from_id:
            self._mark(msg.msg_id, from_id, now)
        if self.on_deliver:
            self.on_deliver(msg)
        return True

    def _mark(self, mid: str, peer: str, now: float):
        self._sent.setdefault(mid, {})[peer] = now

    def _recently_sent(self, mid: str, peer: str, now: float) -> bool:
        t = self._sent.get(mid, {}).get(peer)
        return t is not None and now - t < self.resend_after

    # ---- policy --------------------------------------------------------
    def fanout(self, n_lacking: int, density: int, battery: float, prio: Priority) -> int:
        """Adaptive: shrink in dense crowds (broadcast storms) and on low battery;
        distress never drops below 2 so it keeps moving."""
        k = self.base_fanout
        if density > 10:
            k -= 1
        if battery < 0.5:
            k -= 1
        if battery < 0.15:
            k = 1
        k = max(1, k)
        if prio == Priority.DISTRESS:
            k = max(k, 2)
        return min(k, n_lacking)

    # ---- egress --------------------------------------------------------
    def step(self, neighbors: Dict[str, NeighborInfo], now: float,
             battery: float = 1.0) -> list:
        self.store.expire(now)
        for mid in [m for m in self._sent if not self.store.seen(m)]:
            del self._sent[mid]

        # Watchdog forwarding deadline check & multipath reroute
        if self.watchdog is not None:
            failed = self.watchdog.tick(now)
            for failed_peer, failed_mid, is_crit in failed:
                if failed_mid in self._sent and failed_peer in self._sent[failed_mid]:
                    del self._sent[failed_mid][failed_peer]
                c = self.store.get(failed_mid)
                if c is not None:
                    alt = [n for n in neighbors.values()
                           if n.id != failed_peer
                           and (self.watchdog is None or self.watchdog.is_trusted(n.id, now))]
                    for n in alt[:2]:
                        self.outbox.push((n.id, failed_mid), c.msg.priority, (n.id, failed_mid))

        density = len(neighbors)
        for mid, c in self.store.items():
            lacking = [n for n in neighbors.values()
                       if mid not in n.have and not self._recently_sent(mid, n.id, now)]
            if c.tokens is not None and c.tokens <= 1:      # wait phase
                lacking = [n for n in lacking
                           if c.msg.dest_cell is not None and n.cell == c.msg.dest_cell]
            if not lacking:
                continue
            k = self.fanout(len(lacking), density, battery, c.msg.priority)
            if self.trust_fn is not None:
                lacking.sort(key=lambda n: (-self.trust_fn(n.id, now), -n.battery, self.rng.random()))
            elif self.watchdog is not None:
                lacking.sort(key=lambda n: (-float(self.watchdog.is_trusted(n.id, now)), -n.battery, self.rng.random()))
            else:
                lacking.sort(key=lambda n: (-n.battery, self.rng.random()))
            for n in lacking[:k]:
                self.outbox.push((n.id, mid), c.msg.priority, (n.id, mid))
        return self._drain(neighbors, now)

    def _drain(self, neighbors: Dict[str, NeighborInfo], now: float) -> list:
        out, used = [], 0
        while True:
            item = self.outbox.pop()
            if item is None:
                break
            peer, mid = item
            n, c = neighbors.get(peer), self.store.get(mid)
            if n is None or c is None or mid in n.have or self._recently_sent(mid, peer, now):
                continue
            size = len(c.msg.wire_bytes())
            if out and used + size > self.byte_budget:
                self.outbox.push(item, c.msg.priority, item)    # keep for next tick
                break
            ok, give, keep = plan_handoff(c, n.cell)
            if not ok:
                continue
            used += size
            self._mark(mid, peer, now)
            if self.watchdog is not None:
                self.watchdog.track(peer, mid, now, is_critical=(c.msg.priority == Priority.DISTRESS))
            out.append(Transmission(peer, c.msg, give))
            if keep is not None:
                if keep <= 0:
                    self.store.remove(mid)
                else:
                    c.tokens = keep
        return out
