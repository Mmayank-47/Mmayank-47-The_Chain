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
                 bonus_rate: float = 1.8, bonus_burst: float = 7.0, max_keys: int = 4096):
        self.reg, self.cp = registry, copresence
        self.base_rate, self.base_burst = base_rate, base_burst
        self.bonus_rate, self.bonus_burst, self.max_keys = bonus_rate, bonus_burst, max_keys
        self._b: Dict[str, TokenBucket] = {}

    def budget(self, node: str, now: float):
        w = self.cp.weight(node, now) if self.cp else 0.0
        return self.base_rate + self.bonus_rate * w, self.base_burst + self.bonus_burst * w

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
