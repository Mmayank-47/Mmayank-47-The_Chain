"""Delay-tolerant carry store + spray-and-wait token rules.

Epidemic classes (tokens=None): distress/dispatch/hazard flood while people are in contact.
Spray classes (int tokens): chatter. Binary spray: hand over half your tokens; with one
token left you only forward to a peer inside the message's destination cell ('wait' phase).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterator, Optional, Tuple

from core.bloom import BloomFilter
from core.types import Message, Priority


@dataclass
class Carried:
    msg: Message
    tokens: Optional[int]
    received: float


class SprayPolicy:
    DEFAULT: Dict[Priority, Optional[int]] = {
        Priority.DISTRESS: None, Priority.DISPATCH: None,
        Priority.HAZARD: None, Priority.CHATTER: 4,
    }

    def __init__(self, table: Optional[Dict[Priority, Optional[int]]] = None):
        self.table = dict(self.DEFAULT if table is None else table)

    def tokens_for(self, p: Priority) -> Optional[int]:
        return self.table.get(p)

    def clamp(self, p: Priority, incoming: Optional[int]) -> Optional[int]:
        """Never trust a peer's claimed token count above our own policy."""
        cap = self.tokens_for(p)
        if cap is None:
            return incoming
        return cap if incoming is None else min(incoming, cap)


def plan_handoff(c: Carried, peer_cell: Optional[str]) -> Tuple[bool, Optional[int], Optional[int]]:
    """-> (send?, tokens_given, tokens_kept)."""
    t = c.tokens
    if t is None:
        return True, None, None
    if t > 1:
        give = t // 2
        return True, give, t - give
    if c.msg.dest_cell is not None and peer_cell == c.msg.dest_cell:
        return True, 1, 0
    return False, 0, t


class CarryStore:
    """Bounded buffer of messages this phone is physically carrying."""

    def __init__(self, capacity: int = 512):
        self.capacity = capacity
        self._c: Dict[str, Carried] = {}
        self._seen: Dict[str, float] = {}   # msg_id -> expiry, survives eviction (anti re-accept)

    def __len__(self):
        return len(self._c)

    def get(self, mid: str) -> Optional[Carried]:
        return self._c.get(mid)

    def items(self) -> Iterator[Tuple[str, Carried]]:
        return iter(list(self._c.items()))

    def seen(self, mid: str) -> bool:
        return mid in self._seen

    def add(self, msg: Message, tokens: Optional[int], now: float) -> bool:
        if msg.msg_id in self._seen:
            return False
        if len(self._c) >= self.capacity and not self._evict_for(msg):
            return False
        self._c[msg.msg_id] = Carried(msg, tokens, now)
        self._seen[msg.msg_id] = msg.created + msg.ttl_s
        return True

    def _evict_for(self, msg: Message) -> bool:
        # drop the lowest-priority, oldest carried message, only if new one outranks it
        worst = max(self._c.values(), key=lambda c: (int(c.msg.priority), -c.received))
        if int(worst.msg.priority) <= int(msg.priority):
            return False
        del self._c[worst.msg.msg_id]
        return True

    def remove(self, mid: str):
        self._c.pop(mid, None)

    def expire(self, now: float):
        for mid in [m for m, c in self._c.items() if c.msg.expired(now)]:
            del self._c[mid]
        for mid in [m for m, e in self._seen.items() if now > e]:
            del self._seen[mid]

    def bloom(self) -> BloomFilter:
        b = BloomFilter.for_capacity(len(self._c))
        for mid in self._c:
            b.add(mid)
        return b
