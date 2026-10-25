"""Per-identity token buckets keyed by hardware key, with a budget that grows with co-presence weight.

Unenrolled senders get nothing. Because the key is the hardware key id, rotating pseudonyms or
claimed identities on one phone share one budget.
"""
from __future__ import annotations

from typing import Dict, Optional

from core.ratelimit import TokenBucket

from .copresence import CoPresence
from .keys import Registry


class IdentityLimiter:
    def __init__(self, registry: Registry, copresence: Optional[CoPresence] = None,
                 base_rate: float = 0.2, base_burst: float = 3.0,
                 bonus_rate: float = 1.8, bonus_burst: float = 7.0, max_keys: int = 4096,
                 stranger_rate: float = 0.0, stranger_burst: float = 0.0,
                 max_strangers: int = 1024):
        self.reg, self.cp = registry, copresence
        self.base_rate, self.base_burst = base_rate, base_burst
        self.bonus_rate, self.bonus_burst, self.max_keys = bonus_rate, bonus_burst, max_keys
        self.stranger_rate, self.stranger_burst = stranger_rate, stranger_burst
        self.max_strangers = max_strangers
        self._b: Dict[str, TokenBucket] = {}
        self._strangers: Dict[str, TokenBucket] = {}

    def budget(self, node: str, now: float):
        w = self.cp.weight(node, now) if self.cp else 0.0
        return self.base_rate + self.bonus_rate * w, self.base_burst + self.bonus_burst * w

    def is_enrolled(self, node: str) -> bool:
        return self.reg.key_id_of(node) is not None

    def allow(self, node: str, now: float, cost: float = 1.0) -> bool:
        kid = self.reg.key_id_of(node)
        if kid is None:
            return False
        rate, burst = self.budget(node, now)
        b = self._b.get(kid)
        if b is None:
            if len(self._b) >= self.max_keys:
                return False
            b = self._b[kid] = TokenBucket(rate, burst)
            b.t = now
        b.rate, b.burst = rate, burst
        return b.allow(now, cost)

    def allow_stranger(self, node: str, now: float, cost: float = 1.0) -> bool:
        if self.reg.key_id_of(node) is not None:
            return False
        if self.stranger_burst <= 0.0 and self.stranger_rate <= 0.0:
            return False
        b = self._strangers.get(node)
        if b is None:
            if len(self._strangers) >= self.max_strangers:
                return False
            b = self._strangers[node] = TokenBucket(self.stranger_rate, self.stranger_burst)
            b.t = now
        b.rate, b.burst = self.stranger_rate, self.stranger_burst
        return b.allow(now, cost)
