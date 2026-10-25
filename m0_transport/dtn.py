"""Delay-tolerant carry store + spray-and-wait token rules.

Epidemic classes (tokens=None): distress/dispatch/hazard flood while people are in contact.
Spray classes (int tokens): chatter. Binary spray: hand over half your tokens; with one
token left you only forward to a peer inside the message's destination cell ('wait' phase).
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Dict, Iterator, Optional, Tuple

from core.bloom import BloomFilter
from core.types import Message, Priority


@dataclass
class Carried:
    msg: Message
    tokens: Optional[int]
    received: float
    from_id: Optional[str] = None


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
        """Never trust a peer's claimed token count above our own policy.

        Epidemic classes carry no tokens at all, so whatever a peer sent is discarded: a hostile
        `tok: 0` on a distress message would otherwise park it in the spray 'wait' phase and stop
        it spreading from this phone. Malformed or non-positive counts on spray classes get the cap.
        """
        cap = self.tokens_for(p)
        if cap is None:
            return None
        if isinstance(incoming, bool) or not isinstance(incoming, int) or incoming < 1:
            return cap
        return min(incoming, cap)


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
    """Bounded buffer of messages this phone is physically carrying with per-origin and per-neighbour quotas."""

    def __init__(self, capacity: int = 512, max_per_origin: Optional[int] = 32,
                 max_per_neighbour: Optional[int] = 64):
        self.capacity = capacity
        self.max_per_origin = max_per_origin
        self.max_per_neighbour = max_per_neighbour
        self._c: Dict[str, Carried] = {}
        self._seen: Dict[str, float] = {}   # msg_id -> expiry, survives eviction (anti re-accept)
        self._origin_counts: Counter[str] = Counter()
        self._neighbour_counts: Counter[str] = Counter()

    def __len__(self):
        return len(self._c)

    def get(self, mid: str) -> Optional[Carried]:
        return self._c.get(mid)

    def items(self) -> Iterator[Tuple[str, Carried]]:
        return iter(list(self._c.items()))

    def seen(self, mid: str) -> bool:
        return mid in self._seen

    def origin_count(self, origin: str) -> int:
        return self._origin_counts.get(origin, 0)

    def neighbour_count(self, neighbour: str) -> int:
        return self._neighbour_counts.get(neighbour, 0)

    def add(self, msg: Message, tokens: Optional[int], now: float,
            from_id: Optional[str] = None) -> bool:
        if msg.msg_id in self._seen:
            return False
        # Per-origin quota: one sender cannot fill the store with DISTRESS
        if self.max_per_origin is not None and self._origin_counts[msg.origin] >= self.max_per_origin:
            if not self._evict_for_origin(msg):
                return False
        # Per-neighbour quota: one peer cannot exhaust carry capacity
        if from_id is not None and self.max_per_neighbour is not None and self._neighbour_counts[from_id] >= self.max_per_neighbour:
            if not self._evict_for_neighbour(from_id, msg):
                return False
        if len(self._c) >= self.capacity and not self._evict_for(msg):
            return False
        self._c[msg.msg_id] = Carried(msg, tokens, now, from_id)
        self._seen[msg.msg_id] = msg.created + msg.ttl_s
        self._origin_counts[msg.origin] += 1
        if from_id is not None:
            self._neighbour_counts[from_id] += 1
        return True

    def _evict_for(self, msg: Message) -> bool:
        # drop the lowest-priority, oldest carried message, only if new one outranks it
        worst = max(self._c.values(), key=lambda c: (int(c.msg.priority), -c.received))
        if int(worst.msg.priority) <= int(msg.priority):
            return False
        self._remove_entry(worst.msg.msg_id)
        return True

    def _evict_for_origin(self, msg: Message) -> bool:
        candidates = [c for c in self._c.values() if c.msg.origin == msg.origin]
        if not candidates:
            return False
        worst = max(candidates, key=lambda c: (int(c.msg.priority), -c.received))
        if int(worst.msg.priority) <= int(msg.priority):
            return False
        self._remove_entry(worst.msg.msg_id)
        return True

    def _evict_for_neighbour(self, from_id: str, msg: Message) -> bool:
        candidates = [c for c in self._c.values() if c.from_id == from_id]
        if not candidates:
            return False
        worst = max(candidates, key=lambda c: (int(c.msg.priority), -c.received))
        if int(worst.msg.priority) <= int(msg.priority):
            return False
        self._remove_entry(worst.msg.msg_id)
        return True

    def _remove_entry(self, mid: str) -> Optional[Carried]:
        c = self._c.pop(mid, None)
        if c is not None:
            self._origin_counts[c.msg.origin] -= 1
            if self._origin_counts[c.msg.origin] <= 0:
                del self._origin_counts[c.msg.origin]
            if c.from_id is not None:
                self._neighbour_counts[c.from_id] -= 1
                if self._neighbour_counts[c.from_id] <= 0:
                    del self._neighbour_counts[c.from_id]
        return c

    def remove(self, mid: str):
        self._remove_entry(mid)

    def expire(self, now: float):
        for mid in [m for m, c in self._c.items() if c.msg.expired(now)]:
            self._remove_entry(mid)
        for mid in [m for m, e in self._seen.items() if now > e]:
            del self._seen[mid]

    def bloom(self) -> BloomFilter:
        b = BloomFilter.for_capacity(len(self._c))
        for mid in self._c:
            b.add(mid)
        return b
